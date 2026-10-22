package local.ae2dashboard.storage;

import com.google.gson.Gson;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import local.ae2dashboard.io.BoundedOutputStream;
import local.ae2dashboard.model.*;

/** Owned by one writer. Readers use independent, short-lived query-only connections. */
public final class HistoryStore implements AutoCloseable {
  private final Path path;
  private Connection writer;
  private final SqliteWatchdog watchdog = new SqliteWatchdog();
  private final long budget;
  private final long maximumGap;
  private final Semaphore readers = new Semaphore(2);
  private final Map<String, Snapshot> previous = new HashMap<>();
  private long previousBytes;
  private final Gson gson = new Gson();
  private long lastMaintenance;
  private long lastActivityRefresh;
  private volatile long retainedFrom;
  private volatile boolean budgetInterrupted;
  private volatile String status = "recording";
  private volatile long bytes;
  private final String worldId;
  private final Map<String, Long> statisticsRevisions = new ConcurrentHashMap<>();
  private final AtomicLong revisionSequence = new AtomicLong(System.currentTimeMillis());
  private volatile String appendingNetwork;
  private volatile boolean maintaining;

  public HistoryStore(Path directory, long budgetBytes) throws SQLException, IOException {
    this(directory, budgetBytes, 10_000);
  }

  public HistoryStore(Path directory, long budgetBytes, long sampleIntervalMillis)
      throws SQLException, IOException {
    maximumGap = Math.max(30_000, sampleIntervalMillis * 3);
    Files.createDirectories(directory);
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException e) {
      throw new SQLException("Bundled SQLite driver is unavailable", e);
    }
    path = directory.resolve("history.sqlite");
    budget = budgetBytes;
    try {
      writer = openWriter();
      schema();
      worldId = meta("worldId", UUID.randomUUID().toString());
      retainedFrom = Long.parseLong(meta("retainedFrom", "0"));
    } catch (SQLException | RuntimeException e) {
      watchdog.close();
      if (writer != null) writer.close();
      throw e;
    }
  }

  private Connection openWriter() throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    try {
      execute(connection, "PRAGMA busy_timeout=1000");
      // Inspect before creating anything; never reset an unfamiliar database.
      if (scalar(connection, "PRAGMA user_version") > 2)
        throw new SQLException("Unsupported newer database schema");
      execute(connection, "PRAGMA journal_mode=WAL");
      execute(connection, "PRAGMA synchronous=NORMAL");
      execute(connection, "PRAGMA wal_autocheckpoint=256");
      execute(connection, "PRAGMA journal_size_limit=16777216");
      execute(
          connection, "PRAGMA cache_size=-" + Math.max(128, Math.min(8192, budget / 64 / 1024)));
      long maxPages = Math.max(256, (budget * 7 / 8) / scalar(connection, "PRAGMA page_size"));
      execute(connection, "PRAGMA max_page_count=" + maxPages);
      return connection;
    } catch (SQLException | RuntimeException e) {
      try {
        connection.close();
      } catch (SQLException close) {
        e.addSuppressed(close);
      }
      throw e;
    }
  }

  private void ensureWriter() throws SQLException {
    if (writer == null) writer = openWriter();
  }

  private void discardWriter() throws SQLException {
    previous.clear();
    previousBytes = 0;
    persistedSegments.clear();
    status = budgetInterrupted ? "paused:space" : "paused:write";
    Connection failed = writer;
    writer = null;
    // Closing rolls back native transactions even when JDBC's auto-commit flag is stale.
    if (failed != null) failed.close();
  }

  private WriteTransaction transaction(boolean guardCapacity) throws SQLException {
    return new WriteTransaction(guardCapacity);
  }

  private final class WriteTransaction implements AutoCloseable {
    private final SqliteWatchdog.Operation operation;
    private boolean committed;

    WriteTransaction(boolean guardCapacity) throws SQLException {
      ensureWriter();
      budgetInterrupted = false;
      operation =
          watchdog.watch(writer, 0, guardCapacity ? HistoryStore.this::checkCapacity : null);
      try {
        writer.setAutoCommit(false);
      } catch (SQLException | RuntimeException e) {
        operation.close();
        try {
          discardWriter();
        } catch (SQLException close) {
          e.addSuppressed(close);
        }
        throw e;
      }
    }

    void commit() throws SQLException {
      SqliteWatchdog.checkCurrent();
      writer.commit();
      writer.setAutoCommit(true);
      committed = true;
    }

    @Override
    public void close() throws SQLException {
      operation.close();
      if (!committed) discardWriter();
    }
  }

  private void checkCapacity() throws SQLException {
    try {
      if (directoryBytes() > budget - Math.min(16L * 1024 * 1024, budget / 8)) {
        budgetInterrupted = true;
        throw new SQLException("History directory reached its write reserve");
      }
    } catch (IOException e) {
      throw new SQLException("Cannot check history budget", e);
    }
  }

  private void schema() throws SQLException {
    writer.setAutoCommit(false);
    try {
      for (String sql :
          List.of(
              "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
              "CREATE TABLE IF NOT EXISTS networks(id TEXT PRIMARY KEY,created INTEGER,last_seen"
                  + " INTEGER,status TEXT)",
              "CREATE TABLE IF NOT EXISTS controller_anchors(network TEXT,anchor TEXT,PRIMARY"
                  + " KEY(network,anchor)) WITHOUT ROWID",
              "CREATE TABLE IF NOT EXISTS network_events(id INTEGER PRIMARY KEY,network TEXT,t"
                  + " INTEGER,type TEXT,related TEXT)",
              "CREATE INDEX IF NOT EXISTS events_time ON network_events(network,t)",
              "CREATE TABLE IF NOT EXISTS resources(id TEXT PRIMARY KEY,group_id TEXT,type"
                  + " TEXT,registry_id TEXT,name TEXT,unit TEXT,amount_per_unit INTEGER,identity"
                  + " TEXT)",
              "CREATE TABLE IF NOT EXISTS series(network TEXT,resource TEXT,last_active"
                  + " INTEGER,PRIMARY KEY(network,resource)) WITHOUT ROWID",
              "CREATE INDEX IF NOT EXISTS series_activity ON series(last_active)",
              "CREATE INDEX IF NOT EXISTS series_resource ON series(resource)",
              "CREATE TABLE IF NOT EXISTS sample_frames(network TEXT,segment TEXT,t INTEGER,PRIMARY"
                  + " KEY(network,t)) WITHOUT ROWID",
              "CREATE INDEX IF NOT EXISTS frames_time ON sample_frames(t)",
              "CREATE TABLE IF NOT EXISTS coverage(network TEXT,segment TEXT,tier INTEGER,bucket"
                  + " INTEGER,first_at INTEGER,last_at INTEGER,samples INTEGER,PRIMARY"
                  + " KEY(network,tier,bucket,segment)) WITHOUT ROWID",
              "CREATE INDEX IF NOT EXISTS coverage_time ON coverage(tier,bucket)",
              "CREATE INDEX IF NOT EXISTS coverage_segment_last ON"
                  + " coverage(network,segment,last_at)",
              "CREATE TABLE IF NOT EXISTS raw_changes(network TEXT,resource TEXT,segment TEXT,t"
                  + " INTEGER,q TEXT,PRIMARY KEY(network,resource,segment,t)) WITHOUT ROWID",
              "CREATE INDEX IF NOT EXISTS raw_time ON raw_changes(t)",
              "CREATE TABLE IF NOT EXISTS rollup_1m(network TEXT,resource TEXT,segment TEXT,t"
                  + " INTEGER,first_at INTEGER,last_at INTEGER,first_q TEXT,last_q TEXT,min_q"
                  + " TEXT,max_q TEXT,samples INTEGER,PRIMARY KEY(network,resource,segment,t))"
                  + " WITHOUT ROWID",
              "CREATE INDEX IF NOT EXISTS minute_time ON rollup_1m(t)",
              "CREATE TABLE IF NOT EXISTS rollup_15m(network TEXT,resource TEXT,segment TEXT,t"
                  + " INTEGER,first_at INTEGER,last_at INTEGER,first_q TEXT,last_q TEXT,min_q"
                  + " TEXT,max_q TEXT,samples INTEGER,PRIMARY KEY(network,resource,segment,t))"
                  + " WITHOUT ROWID",
              "CREATE INDEX IF NOT EXISTS quarter_time ON rollup_15m(t)",
              "CREATE TABLE IF NOT EXISTS inventory_capabilities(network TEXT NOT NULL,resource"
                  + " TEXT NOT NULL,craftable INTEGER NOT NULL,infinite INTEGER NOT NULL,PRIMARY"
                  + " KEY(network,resource)) WITHOUT ROWID",
              "CREATE TABLE IF NOT EXISTS crafting_orders(id TEXT PRIMARY KEY,network TEXT NOT"
                  + " NULL,request_id TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT"
                  + " NULL,finished INTEGER NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,"
                  + " UNIQUE(network,request_id))",
              "CREATE INDEX IF NOT EXISTS orders_network_time ON crafting_orders(network,created"
                  + " DESC,id DESC)",
              "CREATE INDEX IF NOT EXISTS orders_finished ON crafting_orders(finished)"))
        execute(writer, sql);
      execute(writer, "PRAGMA user_version=2");
      writer.commit();
    } catch (SQLException e) {
      writer.rollback();
      throw e;
    } finally {
      writer.setAutoCommit(true);
    }
    if (!meta("identityContract", "ae2wi-1.2.0-pre-forge-1.20.1")
        .equals("ae2wi-1.2.0-pre-forge-1.20.1"))
      throw new SQLException("Unsupported identity contract");
  }

  private String meta(String key, String initial) throws SQLException {
    update(writer, "INSERT OR IGNORE INTO meta VALUES(?,?)", key, initial);
    try (var s = prepare(writer, "SELECT value FROM meta WHERE key=?", key);
        var r = s.executeQuery()) {
      if (!r.next()) throw new SQLException("Missing database metadata");
      return r.getString(1);
    }
  }

  public String worldId() {
    return worldId;
  }

  public String status() {
    return status;
  }

  public long bytes() {
    return bytes;
  }

  public List<Network> networks() throws SQLException {
    List<Network> result = new ArrayList<>();
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT * FROM networks ORDER BY created,id")) {
      while (r.next()) {
        Set<String> anchors = new TreeSet<>();
        try (var a =
                prepare(
                    c, "SELECT anchor FROM controller_anchors WHERE network=?", r.getString("id"));
            var ar = a.executeQuery()) {
          while (ar.next()) anchors.add(ar.getString(1));
        }
        result.add(
            new Network(
                r.getString("id"),
                r.getLong("created"),
                r.getLong("last_seen"),
                "merged".equals(r.getString("status")) ? "merged" : "unloaded",
                anchors));
      }
    }
    return result;
  }

  public void saveNetwork(Network network, String event, Set<String> related) throws SQLException {
    if (!network.status().equals("online")) forget(network.id());
    try (var transaction = transaction(false)) {
      update(
          writer,
          "INSERT INTO networks VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
              + " last_seen=excluded.last_seen,status=excluded.status",
          network.id(),
          network.createdAt(),
          network.lastSeen(),
          network.status());
      update(writer, "DELETE FROM controller_anchors WHERE network=?", network.id());
      for (String anchor : network.anchors())
        update(writer, "INSERT INTO controller_anchors VALUES(?,?)", network.id(), anchor);
      if (event != null)
        update(
            writer,
            "INSERT INTO network_events(network,t,type,related) VALUES(?,?,?,?)",
            network.id(),
            network.lastSeen(),
            event,
            gson.toJson(related));
      transaction.commit();
    }
  }

  public void append(Snapshot sample) throws SQLException, IOException {
    ensureWriter();
    maintain(sample.timestamp());
    long total = directoryBytes();
    if (total >= budget - 1_048_576
        || Files.getFileStore(path).getUsableSpace() < Math.min(16_777_216, budget / 8)) {
      status = "paused:space";
      forget(sample.networkId());
      throw new IOException("History budget or free disk exhausted");
    }
    Snapshot old = previous.get(sample.networkId());
    long estimatedWrite = 65536;
    for (var entry : sample.values().entrySet()) {
      var before = old == null ? null : old.values().get(entry.getKey());
      if (before == null)
        estimatedWrite += entry.getValue().resource().identity().length() * 3L + 2048;
      else if (!before.quantity().equals(entry.getValue().quantity())) estimatedWrite += 2048;
    }
    if (estimatedWrite > Math.min(64L * 1024 * 1024, budget / 8)
        || total + estimatedWrite >= budget) {
      status = "paused:batch";
      forget(sample.networkId());
      throw new IOException("Complete sample exceeds the bounded write budget");
    }
    if (old != null && sample.timestamp() <= old.timestamp()) return;
    try (var check =
            prepare(
                writer,
                "SELECT 1 FROM sample_frames WHERE network=? AND t=?",
                sample.networkId(),
                sample.timestamp());
        var duplicate = check.executeQuery()) {
      if (duplicate.next()) return;
    }
    boolean continuous =
        old != null
            && old.segment().equals(sample.segment())
            && sample.timestamp() > old.timestamp()
            && sample.timestamp() - old.timestamp() <= maximumGap;
    String segment = continuous ? sample.segment() : sample.segment() + ":" + sample.timestamp();
    // Keep the original segment token in memory, but persist a fresh token after a missing write.
    if (continuous) segment = persistedSegment(sample.networkId(), sample.segment());
    Set<String> keys = new HashSet<>(sample.values().keySet());
    if (continuous) keys.addAll(old.values().keySet());
    budgetInterrupted = false;
    appendingNetwork = sample.networkId();
    try (var transaction = transaction(true)) {
      update(
          writer,
          "INSERT OR IGNORE INTO sample_frames VALUES(?,?,?)",
          sample.networkId(),
          segment,
          sample.timestamp());
      for (long tier : new long[] {10_000, 60_000, 900_000}) {
        long bucket = sample.timestamp() / tier * tier;
        update(
            writer,
            "INSERT INTO coverage VALUES(?,?,?,?,?,?,1) ON CONFLICT(network,tier,bucket,segment) DO"
                + " UPDATE SET last_at=MAX(last_at,excluded.last_at),samples=samples+1",
            sample.networkId(),
            segment,
            tier,
            bucket,
            sample.timestamp(),
            sample.timestamp());
      }
      for (String id : keys) {
        checkInterrupted();
        Resource.Stored value = sample.values().get(id);
        Resource.Stored before = continuous ? old.values().get(id) : null;
        BigInteger q = value == null ? BigInteger.ZERO : value.quantity();
        BigInteger prior = before == null ? BigInteger.ZERO : before.quantity();
        Resource resource = value != null ? value.resource() : before.resource();
        if (before != null && !before.resource().identity().equals(resource.identity()))
          throw new SQLException("Resource identity collision");
        if (value != null && before == null) ensureResource(resource);
        if (!continuous || before == null)
          update(
              writer,
              "INSERT INTO series VALUES(?,?,?) ON CONFLICT(network,resource) DO UPDATE SET"
                  + " last_active=MAX(last_active,excluded.last_active)",
              sample.networkId(),
              id,
              q.signum() > 0 || value != null && value.craftable() ? sample.timestamp() : 0);
        if (!continuous || before == null || !q.equals(prior)) {
          update(
              writer,
              "INSERT OR REPLACE INTO raw_changes VALUES(?,?,?,?,?)",
              sample.networkId(),
              id,
              segment,
              sample.timestamp(),
              q.toString());
          aggregate(sample.networkId(), id, segment, sample.timestamp(), q, prior);
        }
      }
      saveCapabilities(sample);
      update(
          writer,
          "UPDATE networks SET last_seen=?,status='online' WHERE id=?",
          sample.timestamp(),
          sample.networkId());
      transaction.commit();
      var replaced = previous.put(sample.networkId(), sample);
      previousBytes += sample.estimatedBytes() - (replaced == null ? 0 : replaced.estimatedBytes());
      while (previous.size() > 128 || previousBytes > 512L * 1024 * 1024) {
        var oldest =
            previous.values().stream()
                .min(Comparator.comparingLong(Snapshot::timestamp))
                .orElseThrow();
        forget(oldest.networkId());
      }
      persistedSegments.put(sample.networkId(), segment);
      status = "recording";
      bytes = directoryBytes();
    } catch (SQLException | RuntimeException e) {
      forget(sample.networkId());
      status = budgetInterrupted ? "paused:space" : "paused:write";
      throw e;
    } finally {
      statisticsRevisions.put(sample.networkId(), revisionSequence.incrementAndGet());
      appendingNetwork = null;
    }
  }

  private final Map<String, String> persistedSegments = new HashMap<>();

  /** 标记属于完整网络观测；只改变化项，避免每次采样重写整个目录。 */
  private void saveCapabilities(Snapshot sample) throws SQLException {
    Map<String, Integer> previousFlags = new HashMap<>();
    try (var s =
            prepare(
                writer,
                "SELECT resource,craftable,infinite FROM inventory_capabilities WHERE network=?",
                sample.networkId());
        var r = s.executeQuery()) {
      while (r.next()) previousFlags.put(r.getString(1), r.getInt(2) | r.getInt(3) << 1);
    }
    for (var value : sample.values().values()) {
      String id = value.resource().id();
      int flags = (value.craftable() ? 1 : 0) | (value.infinite() ? 2 : 0);
      Integer old = previousFlags.remove(id);
      if (flags == 0) {
        if (old != null)
          update(
              writer,
              "DELETE FROM inventory_capabilities WHERE network=? AND resource=?",
              sample.networkId(),
              id);
      } else if (old == null || old != flags) {
        update(
            writer,
            "INSERT INTO inventory_capabilities VALUES(?,?,?,?) ON CONFLICT(network,resource)"
                + " DO UPDATE SET craftable=excluded.craftable,infinite=excluded.infinite",
            sample.networkId(),
            id,
            value.craftable() ? 1 : 0,
            value.infinite() ? 1 : 0);
      }
    }
    for (String id : previousFlags.keySet())
      update(
          writer,
          "DELETE FROM inventory_capabilities WHERE network=? AND resource=?",
          sample.networkId(),
          id);
  }

  private void forget(String network) {
    var old = previous.remove(network);
    if (old != null) previousBytes -= old.estimatedBytes();
    persistedSegments.remove(network);
  }

  private String persistedSegment(String network, String fallback) {
    return persistedSegments.getOrDefault(network, fallback);
  }

  private void ensureResource(Resource value) throws SQLException {
    try (var s = prepare(writer, "SELECT identity FROM resources WHERE id=?", value.id());
        var r = s.executeQuery()) {
      if (r.next() && !r.getString(1).equals(value.identity()))
        throw new SQLException("Resource identity collision");
    }
    update(
        writer,
        "INSERT OR IGNORE INTO resources VALUES(?,?,?,?,?,?,?,?)",
        value.id(),
        value.groupId(),
        value.type(),
        value.registryId(),
        value.name(),
        value.unit(),
        value.amountPerUnit(),
        value.identity());
  }

  private void aggregate(
      String network, String id, String segment, long t, BigInteger q, BigInteger before)
      throws SQLException {
    long bucket = t / 60_000 * 60_000;
    long first = t;
    try (var s =
            prepare(
                writer,
                "SELECT first_at FROM coverage WHERE network=? AND segment=? AND tier=60000 AND"
                    + " bucket=?",
                network,
                segment,
                bucket);
        var r = s.executeQuery()) {
      if (r.next()) first = r.getLong(1);
    }
    BigInteger firstQ = first < t ? before : q;
    merge(
        "rollup_1m",
        network,
        id,
        segment,
        bucket,
        first,
        t,
        firstQ,
        q,
        firstQ.min(q),
        firstQ.max(q),
        1);
    // Rebuild this resource's quarter from committed-in-transaction minute summaries; replay is
    // idempotent.
    long quarter = t / 900_000 * 900_000;
    long quarterFirst;
    try (var s =
            prepare(
                writer,
                "SELECT first_at FROM coverage WHERE network=? AND segment=? AND tier=900000 AND"
                    + " bucket=?",
                network,
                segment,
                quarter);
        var r = s.executeQuery()) {
      if (!r.next()) throw new SQLException("Missing quarter coverage");
      quarterFirst = r.getLong(1);
    }
    BigInteger quarterFirstQ = BigInteger.ZERO;
    long firstMinute = quarterFirst / 60_000 * 60_000;
    // A sparse quarter may start with unchanged minutes; keep their baseline too.
    try (var s =
            prepare(
                writer,
                "SELECT t,first_q,last_q FROM rollup_1m WHERE network=? AND resource=? AND"
                    + " segment=? AND t<=? ORDER BY t DESC LIMIT 1",
                network,
                id,
                segment,
                firstMinute);
        var r = s.executeQuery()) {
      if (r.next())
        quarterFirstQ =
            new BigInteger(r.getString(r.getLong(1) == firstMinute ? "first_q" : "last_q"));
    }
    try (var s =
            prepare(
                writer,
                "SELECT * FROM rollup_1m WHERE network=? AND resource=? AND segment=? AND t>=? AND"
                    + " t<? ORDER BY t",
                network,
                id,
                segment,
                quarter,
                quarter + 900_000);
        var r = s.executeQuery()) {
      long la = 0, count = 0;
      BigInteger lq = null, min = quarterFirstQ, max = quarterFirstQ;
      while (r.next()) {
        BigInteger a = new BigInteger(r.getString("min_q")),
            b = new BigInteger(r.getString("max_q"));
        min = min.min(a);
        max = max.max(b);
        la = r.getLong("last_at");
        lq = new BigInteger(r.getString("last_q"));
        count += r.getLong("samples");
      }
      if (lq != null) {
        update(
            writer,
            "INSERT OR REPLACE INTO rollup_15m VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            network,
            id,
            segment,
            quarter,
            quarterFirst,
            la,
            quarterFirstQ.toString(),
            lq.toString(),
            min.toString(),
            max.toString(),
            count);
      }
    }
  }

  private void merge(
      String table,
      String net,
      String id,
      String seg,
      long bucket,
      long first,
      long last,
      BigInteger fq,
      BigInteger lq,
      BigInteger min,
      BigInteger max,
      long count)
      throws SQLException {
    try (var s =
            prepare(
                writer,
                "SELECT * FROM " + table + " WHERE network=? AND resource=? AND segment=? AND t=?",
                net,
                id,
                seg,
                bucket);
        var r = s.executeQuery()) {
      if (r.next()) {
        first = r.getLong("first_at");
        fq = new BigInteger(r.getString("first_q"));
        min = min.min(new BigInteger(r.getString("min_q")));
        max = max.max(new BigInteger(r.getString("max_q")));
        count += r.getLong("samples");
      }
    }
    update(
        writer,
        "INSERT OR REPLACE INTO " + table + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        net,
        id,
        seg,
        bucket,
        first,
        last,
        fq.toString(),
        lq.toString(),
        min.toString(),
        max.toString(),
        count);
  }

  public History.Result history(
      String network, List<String> ids, long from, long to, long requested, long now)
      throws SQLException, InterruptedException {
    return history(network, ids, from, to, requested, now, 300_000);
  }

  public History.Result history(
      String network,
      List<String> ids,
      long from,
      long to,
      long requested,
      long now,
      long rateWindow)
      throws SQLException, InterruptedException {
    if (rateWindow != 60_000 && rateWindow != 300_000 && rateWindow != 900_000)
      throw new IllegalArgumentException("Invalid rate window");
    if (ids.isEmpty() || ids.size() > 6 || ids.stream().anyMatch(id -> id.length() > 256))
      throw new IllegalArgumentException("Choose 1 to 6 resources");
    long step = History.step(from, to, now, requested);
    long tier = now - from > 7 * History.DAY ? 900_000 : now - from > History.DAY ? 60_000 : 10_000;
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation()) {
      c.setAutoCommit(false);
      try {
        long start = Math.max(from, retainedFrom);
        List<Coverage> coverage = coverage(c, network, start, to, tier);
        List<History.Series> output = new ArrayList<>();
        String table = tier == 10_000 ? "raw_changes" : tier == 60_000 ? "rollup_1m" : "rollup_15m";
        for (String id : ids)
          output.add(readSeries(c, table, network, id, coverage, from, to, step, tier, rateWindow));
        long available = coverage.isEmpty() ? to : coverage.get(0).first();
        var result =
            new History.Result(
                from,
                to,
                step,
                available,
                start > from || available > from + Math.max(tier, 10_000),
                output);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        operation.close();
        try {
          c.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        throw e;
      }
    }
  }

  private List<Coverage> coverage(Connection c, String network, long start, long to, long tier)
      throws SQLException {
    List<Coverage> coverage = new ArrayList<>();
    String coverageSql =
        tier == 10_000
            ? "SELECT segment,t AS bucket,t AS first_at,t AS last_at,1 AS samples FROM"
                + " sample_frames WHERE network=? AND t>=? AND t<=? ORDER BY t LIMIT 260000"
            : "SELECT * FROM coverage WHERE network=? AND tier=? AND bucket>=? AND bucket<=?"
                + " ORDER BY bucket,segment LIMIT 260000";
    Object[] coverageArgs =
        tier == 10_000
            ? new Object[] {network, start, to}
            : new Object[] {network, tier, start / tier * tier, to};
    try (var s = prepare(c, coverageSql, coverageArgs);
        var r = s.executeQuery()) {
      while (r.next()) {
        checkInterrupted();
        coverage.add(
            new Coverage(
                r.getString("segment"),
                r.getLong("bucket"),
                r.getLong("first_at"),
                r.getLong("last_at"),
                r.getLong("samples")));
      }
    }
    coverage.removeIf(cv -> cv.first() < start || cv.last() > to);
    return coverage;
  }

  private record Coverage(String segment, long bucket, long first, long last, long samples) {}

  public History.StatisticsPage statistics(
      String network, boolean grouped, long from, long to, int offset, int limit, long now)
      throws SQLException, InterruptedException {
    History.step(from, to, now, 0);
    if (offset < 0 || limit < 1 || limit > 2000) throw new IllegalArgumentException("Invalid page");
    long tier = now - from > 7 * History.DAY ? 900_000 : now - from > History.DAY ? 60_000 : 10_000;
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation()) {
      c.setAutoCommit(false);
      try {
        long version =
            statisticsRevisions.computeIfAbsent(
                network, ignored -> revisionSequence.incrementAndGet());
        if (maintaining || network.equals(appendingNetwork)) throw new History.SnapshotChanged();
        List<Coverage> observations = coverage(c, network, Math.max(from, retainedFrom), to, tier);
        Coverage first = null, last = null;
        long count = 0;
        boolean oneSegment = true;
        for (Coverage cv : observations) {
          if (last == null
              || !last.segment().equals(cv.segment())
              || cv.first() - last.last() > Math.max(maximumGap, tier + 20_000)) {
            if (last != null) oneSegment = false;
            first = cv;
            count = 0;
          }
          last = cv;
          count += cv.samples();
        }
        boolean sufficient = first != null && last.last() > first.first() && count > 1;
        boolean complete =
            sufficient
                && oneSegment
                && first.first() <= from + Math.max(tier, 10_000)
                && last.last() >= to - Math.max(tier, 30_000);
        int total;
        String catalog = " FROM series WHERE network=? AND (resource LIKE 'group:%')=?";
        try (var s = prepare(c, "SELECT COUNT(*)" + catalog, network, grouped ? 1 : 0);
            var r = s.executeQuery()) {
          total = r.next() ? r.getInt(1) : 0;
        }
        String table = tier == 10_000 ? "raw_changes" : tier == 60_000 ? "rollup_1m" : "rollup_15m";
        String beginning =
            tier == 10_000 ? "q" : "CASE WHEN v.t=b.bucket THEN first_q ELSE last_q END";
        String ending = tier == 10_000 ? "q" : "last_q";
        String lookup =
            " FROM "
                + table
                + " v WHERE v.network=b.network AND v.segment=b.segment AND v.resource=r.resource"
                + " AND v.t<=%s ORDER BY v.t DESC LIMIT 1";
        List<History.Statistics> output = new ArrayList<>();
        try (var s =
                prepare(
                    c,
                    "WITH bounds AS (SELECT ? AS network,? AS segment,? AS first,? AS last,? AS"
                        + " bucket), catalog AS (SELECT resource"
                        + catalog
                        + " ORDER BY resource LIMIT ? OFFSET ?)"
                        + " SELECT r.resource,COALESCE((SELECT "
                        + beginning
                        + lookup.formatted("b.first")
                        + "),'0'),"
                        + " COALESCE((SELECT "
                        + ending
                        + lookup.formatted("b.last")
                        + "),'0') FROM catalog r CROSS JOIN bounds b",
                    network,
                    first == null ? "" : first.segment(),
                    first == null ? 0 : first.first(),
                    last == null ? 0 : last.last(),
                    first == null ? 0 : first.bucket(),
                    network,
                    grouped ? 1 : 0,
                    limit,
                    offset);
            var r = s.executeQuery()) {
          while (r.next()) {
            checkInterrupted();
            String delta =
                sufficient
                    ? new BigInteger(r.getString(3))
                        .subtract(new BigInteger(r.getString(2)))
                        .toString()
                    : null;
            output.add(
                new History.Statistics(
                    r.getString(1),
                    delta,
                    delta == null
                        ? null
                        : History.rate(new BigInteger(delta), last.last() - first.first()),
                    complete,
                    first == null ? 0 : first.first(),
                    last == null ? 0 : last.last(),
                    count,
                    !sufficient ? "insufficient" : complete ? "full" : "latest-continuous"));
          }
        }
        c.commit();
        if (maintaining
            || network.equals(appendingNetwork)
            || statisticsRevisions.get(network) != version) throw new History.SnapshotChanged();
        return new History.StatisticsPage(version, total, output);
      } catch (SQLException | RuntimeException e) {
        operation.close();
        try {
          c.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        throw e;
      }
    }
  }

  private record Change(
      long t,
      long first,
      long last,
      BigInteger firstQ,
      BigInteger lastQ,
      BigInteger min,
      BigInteger max) {}

  private History.Series readSeries(
      Connection c,
      String table,
      String network,
      String id,
      List<Coverage> coverage,
      long from,
      long to,
      long step,
      long tier,
      long rateWindow)
      throws SQLException {
    try (var s = prepare(c, "SELECT 1 FROM series WHERE network=? AND resource=?", network, id);
        var r = s.executeQuery()) {
      if (!r.next()) return new History.Series(id, List.of(), null, null, false);
    }
    Map<String, List<Change>> changes = new HashMap<>();
    for (String segment : coverage.stream().map(Coverage::segment).distinct().toList()) {
      List<Change> list = new ArrayList<>();
      String select =
          table.equals("raw_changes")
              ? "t,t AS first_at,t AS last_at,q AS first_q,q AS last_q,q AS min_q,q AS max_q"
              : "t,first_at,last_at,first_q,last_q,min_q,max_q";
      String clause = "network=? AND resource=? AND segment=?";
      try (var s =
              prepare(
                  c,
                  "SELECT "
                      + select
                      + " FROM "
                      + table
                      + " WHERE "
                      + clause
                      + " AND t<? ORDER BY t DESC LIMIT 1",
                  network,
                  id,
                  segment,
                  from / tier * tier);
          var r = s.executeQuery()) {
        if (r.next()) list.add(change(r));
      }
      try (var s =
              prepare(
                  c,
                  "SELECT "
                      + select
                      + " FROM "
                      + table
                      + " WHERE "
                      + clause
                      + " AND t>=? AND t<=? ORDER BY t LIMIT 260001",
                  network,
                  id,
                  segment,
                  from / tier * tier,
                  to);
          var r = s.executeQuery()) {
        while (r.next()) {
          checkInterrupted();
          list.add(change(r));
          if (list.size() > 260000) throw new SQLException("Query row limit");
        }
      }
      changes.put(segment, list);
    }
    Map<String, Integer> cursors = new HashMap<>();
    Map<String, BigInteger> values = new HashMap<>();
    List<History.Point> points = new ArrayList<>();
    long currentBucket = Long.MIN_VALUE, fa = 0, la = 0, samples = 0, lastCoverage = Long.MIN_VALUE;
    String currentSegment = null;
    BigInteger first = null, last = null, min = null, max = null;
    int gap = 0;
    for (Coverage cv : coverage) {
      if (cv.first() > to
          || cv.last() < from
          || (tier > 10_000 && (cv.first() < from || cv.last() > to))) continue;
      checkInterrupted();
      List<Change> list = changes.get(cv.segment());
      int index = cursors.getOrDefault(cv.segment(), 0);
      BigInteger q = values.getOrDefault(cv.segment(), BigInteger.ZERO), low = q, high = q;
      BigInteger firstValue = q;
      while (index < list.size() && list.get(index).t() <= cv.last()) {
        Change change = list.get(index++);
        q = change.lastQ();
        if (change.t() >= cv.bucket()) {
          firstValue = change.firstQ();
          low = change.min();
          high = change.max();
        } else {
          firstValue = q;
          low = q;
          high = q;
        }
      }
      cursors.put(cv.segment(), index);
      values.put(cv.segment(), q);
      long bucket = cv.bucket();
      boolean hole =
          lastCoverage != Long.MIN_VALUE
              && cv.first() - lastCoverage > Math.max(maximumGap, tier + 20_000);
      String pointSegment = cv.segment() + ":" + gap;
      if (hole) {
        gap++;
        pointSegment = cv.segment() + ":" + gap;
      }
      if (currentBucket != bucket || !Objects.equals(currentSegment, pointSegment)) {
        if (first != null)
          points.add(point(currentBucket, currentSegment, fa, la, samples, first, last, min, max));
        currentBucket = bucket;
        currentSegment = pointSegment;
        fa = cv.first();
        first = firstValue;
        min = low;
        max = high;
        samples = 0;
      }
      min = min.min(low);
      max = max.max(high);
      last = q;
      la = cv.last();
      samples += cv.samples();
      lastCoverage = cv.last();
    }
    if (first != null)
      points.add(point(currentBucket, currentSegment, fa, la, samples, first, last, min, max));
    return History.summarize(id, points, from, to, step, tier, rateWindow);
  }

  private static Change change(ResultSet r) throws SQLException {
    return new Change(
        r.getLong("t"),
        r.getLong("first_at"),
        r.getLong("last_at"),
        new BigInteger(r.getString("first_q")),
        new BigInteger(r.getString("last_q")),
        new BigInteger(r.getString("min_q")),
        new BigInteger(r.getString("max_q")));
  }

  private static History.Point point(
      long bucket,
      String segment,
      long firstAt,
      long lastAt,
      long count,
      BigInteger first,
      BigInteger last,
      BigInteger min,
      BigInteger max) {
    return new History.Point(
        bucket,
        segment,
        last.toString(),
        first.toString(),
        min.toString(),
        max.toString(),
        firstAt,
        lastAt,
        count,
        null);
  }

  public List<Resource> resources(int offset, int limit) throws SQLException {
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation();
        var s =
            prepare(
                c,
                "SELECT id,group_id,type,registry_id,name,unit,amount_per_unit,'' AS identity FROM"
                    + " resources ORDER BY id LIMIT ? OFFSET ?",
                Math.min(limit, 2000),
                offset);
        var r = s.executeQuery()) {
      List<Resource> list = new ArrayList<>();
      while (r.next()) list.add(resource(r));
      return list;
    }
  }

  public List<Resource> networkResources(String network, boolean grouped, int offset, int limit)
      throws SQLException {
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation();
        var s =
            prepare(
                c,
                "SELECT r.id,r.group_id,r.type,r.registry_id,r.name,r.unit,r.amount_per_unit,'' AS"
                    + " identity FROM resources r JOIN series s ON s.resource=r.id WHERE"
                    + " s.network=? AND (r.id LIKE 'group:%')=? ORDER BY r.id LIMIT ? OFFSET ?",
                network,
                grouped ? 1 : 0,
                Math.min(limit, 2000),
                offset);
        var r = s.executeQuery()) {
      List<Resource> result = new ArrayList<>();
      while (r.next()) result.add(resource(r));
      return result;
    }
  }

  public record StoredInventory(long timestamp, List<Resource.Stored> rows, int total) {}

  public record Observation(long timestamp, long nonZeroResources, long craftableResources) {}

  public Observation lastObservation(String network) throws SQLException {
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation();
        var s =
            prepare(
                c,
                "WITH latest AS (SELECT segment,last_at FROM coverage WHERE network=? AND"
                    + " tier=900000 ORDER BY last_at DESC LIMIT 1) SELECT last_at,(SELECT COUNT(*)"
                    + " FROM series s WHERE s.network=? AND s.resource NOT LIKE 'group:%' AND"
                    + " COALESCE((SELECT last_q FROM rollup_15m v WHERE v.network=s.network AND"
                    + " v.resource=s.resource AND v.segment=latest.segment ORDER BY t DESC LIMIT"
                    + " 1),'0')<>'0'),(SELECT COUNT(*) FROM inventory_capabilities c WHERE"
                    + " c.network=? AND c.resource NOT LIKE 'group:%' AND c.craftable=1) FROM"
                    + " latest",
                network,
                network,
                network);
        var r = s.executeQuery()) {
      return r.next()
          ? new Observation(r.getLong(1), r.getLong(2), r.getLong(3))
          : new Observation(0, 0, 0);
    }
  }

  public StoredInventory lastInventory(
      String network, boolean grouped, String group, int offset, int limit) throws SQLException {
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation()) {
      c.setAutoCommit(false);
      try {
        String segment;
        long timestamp;
        try (var s =
                prepare(
                    c,
                    "SELECT segment,last_at FROM coverage WHERE network=? AND tier=900000 ORDER BY"
                        + " last_at DESC LIMIT 1",
                    network);
            var r = s.executeQuery()) {
          if (!r.next()) {
            c.commit();
            return new StoredInventory(0, List.of(), 0);
          }
          segment = r.getString(1);
          timestamp = r.getLong(2);
        }
        String where =
            " FROM resources r JOIN series s ON s.resource=r.id LEFT JOIN inventory_capabilities"
                + " c ON c.network=s.network AND c.resource=r.id WHERE s.network=? AND (r.id LIKE"
                + " 'group:%')=? AND (?='' OR r.group_id=?)";
        int total;
        try (var s = prepare(c, "SELECT COUNT(*)" + where, network, grouped ? 1 : 0, group, group);
            var r = s.executeQuery()) {
          if (!r.next()) throw new SQLException("Missing resource count");
          total = r.getInt(1);
        }
        StringBuilder quantity = new StringBuilder("COALESCE(");
        for (String table : List.of("raw_changes", "rollup_1m", "rollup_15m")) {
          quantity
              .append("(SELECT ")
              .append(table.equals("raw_changes") ? "q" : "last_q")
              .append(" FROM ")
              .append(table)
              .append(
                  " v WHERE v.network=s.network AND v.resource=s.resource AND v.segment=? ORDER BY"
                      + " t DESC LIMIT 1),");
        }
        quantity.append("'0') AS quantity");
        var rows = new ArrayList<Resource.Stored>();
        try (var s =
                prepare(
                    c,
                    "SELECT r.id,r.group_id,r.type,r.registry_id,r.name,r.unit,r.amount_per_unit,''"
                        + " AS identity,"
                        + quantity
                        + ",COALESCE(c.craftable,0) AS craftable,COALESCE(c.infinite,0) AS infinite"
                        + where
                        + " ORDER BY r.id LIMIT ? OFFSET ?",
                    segment,
                    segment,
                    segment,
                    network,
                    grouped ? 1 : 0,
                    group,
                    group,
                    limit,
                    offset);
            var r = s.executeQuery()) {
          while (r.next())
            rows.add(
                new Resource.Stored(
                    resource(r),
                    new BigInteger(r.getString("quantity")),
                    r.getInt("craftable") != 0,
                    r.getInt("infinite") != 0));
        }
        c.commit();
        return new StoredInventory(timestamp, List.copyOf(rows), total);
      } catch (SQLException | RuntimeException e) {
        operation.close();
        try {
          c.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        throw e;
      }
    }
  }

  public void exportKeys(Path target) throws SQLException, IOException {
    Path temporary = Files.createTempFile(target.getParent(), "keys-", ".tmp");
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation();
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT * FROM resources WHERE identity NOT LIKE 'group:%' ORDER BY id");
        var out =
            new BufferedWriter(
                new OutputStreamWriter(
                    new BoundedOutputStream(Files.newOutputStream(temporary), 32L * 1024 * 1024),
                    java.nio.charset.StandardCharsets.UTF_8));
        var json = new com.google.gson.stream.JsonWriter(out)) {
      json.beginObject()
          .name("identityContract")
          .value("ae2wi-1.2.0-pre-forge-1.20.1")
          .name("entries")
          .beginArray();
      int count = 0;
      while (r.next()) {
        if (++count > 100000) throw new IOException("Key export limit");
        Resource resource = resource(r);
        gson.toJson(resource, Resource.class, json);
      }
      json.endArray().endObject();
    } catch (SQLException | IOException e) {
      Files.deleteIfExists(temporary);
      throw e;
    }
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Resource resource(ResultSet r) throws SQLException {
    return new Resource(
        r.getString("id"),
        r.getString("group_id"),
        r.getString("type"),
        r.getString("registry_id"),
        r.getString("name"),
        r.getString("unit"),
        r.getInt("amount_per_unit"),
        r.getString("identity"));
  }

  public List<Map<String, Object>> events(String network) throws SQLException {
    try (var lease = reader();
        var c = lease.connection();
        var operation = lease.operation();
        var s =
            prepare(
                c,
                "SELECT t,type,related FROM network_events WHERE network=? ORDER BY t DESC LIMIT"
                    + " 100",
                network);
        var r = s.executeQuery()) {
      List<Map<String, Object>> list = new ArrayList<>();
      while (r.next())
        list.add(
            Map.of("timestamp", r.getLong(1), "type", r.getString(2), "related", r.getString(3)));
      return list;
    }
  }

  public Crafting.Order findOrder(String network, String id) throws SQLException {
    try (var lease = reader()) {
      return readOrder(
          lease.connection(),
          "SELECT payload FROM crafting_orders WHERE network=? AND id=?",
          network,
          id);
    }
  }

  public Crafting.Order findOrderByRequest(String network, String requestId) throws SQLException {
    try (var lease = reader()) {
      return readOrder(
          lease.connection(),
          "SELECT payload FROM crafting_orders WHERE network=? AND request_id=?",
          network,
          requestId);
    }
  }

  /** 必须在唯一写入线程调用；返回已存在的同请求订单，不重复生成提交意图。 */
  public Crafting.Order insertOrderIntent(Crafting.Order order) throws SQLException {
    // 小事务可能在 watchdog 首轮之前结束，因此先检查写入预留。
    checkCapacity();
    try (var transaction = transaction(true)) {
      update(
          writer,
          "INSERT OR IGNORE INTO crafting_orders VALUES(?,?,?,?,?,?,?,?)",
          order.id(),
          order.networkId(),
          order.requestId(),
          order.createdAt(),
          order.updatedAt(),
          order.finishedAt(),
          order.state(),
          gson.toJson(order));
      Crafting.Order stored =
          readOrder(
              writer,
              "SELECT payload FROM crafting_orders WHERE network=? AND request_id=?",
              order.networkId(),
              order.requestId());
      if (stored == null) throw new SQLException("Order identifier collision");
      transaction.commit();
      return stored;
    }
  }

  /** 事件按序写入；旧进度和恢复消息不能覆盖已确认的终态。 */
  public void saveOrder(Crafting.Order order) throws SQLException {
    checkCapacity();
    try (var transaction = transaction(true)) {
      int changed =
          update(
              writer,
              "UPDATE crafting_orders SET updated=?,finished=?,state=?,payload=? WHERE id=?"
                  + " AND network=? AND updated<=? AND finished=0",
              order.updatedAt(),
              order.finishedAt(),
              order.state(),
              gson.toJson(order),
              order.id(),
              order.networkId(),
              order.updatedAt());
      if (changed == 0
          && readOrder(
                  writer,
                  "SELECT payload FROM crafting_orders WHERE id=? AND network=?",
                  order.id(),
                  order.networkId())
              == null) throw new SQLException("Missing persisted order intent");
      transaction.commit();
    }
  }

  public List<Crafting.Order> activeOrders() throws SQLException {
    try (var lease = reader();
        var s =
            prepare(
                lease.connection(),
                "SELECT payload FROM crafting_orders WHERE finished=0 ORDER BY created,id LIMIT"
                    + " 1025");
        var r = s.executeQuery()) {
      List<Crafting.Order> result = new ArrayList<>();
      while (r.next()) result.add(gson.fromJson(r.getString(1), Crafting.Order.class));
      if (result.size() > 1024) throw new SQLException("Active orders exceed the recovery limit");
      return List.copyOf(result);
    }
  }

  public Crafting.OrderPage orders(String network, boolean history, int limit, String cursor)
      throws SQLException {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid order page limit");
    long before = Long.MAX_VALUE;
    String beforeId = "~";
    if (cursor != null && !cursor.isEmpty()) {
      if (cursor.length() > 128) throw new IllegalArgumentException("Invalid order cursor");
      String decoded =
          new String(
              Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
      String[] parts = decoded.split(":", -1);
      if (parts.length != 2) throw new IllegalArgumentException("Invalid order cursor");
      before = Long.parseLong(parts[0]);
      beforeId = UUID.fromString(parts[1]).toString();
    }
    try (var lease = reader();
        var s =
            prepare(
                lease.connection(),
                "SELECT payload FROM crafting_orders WHERE network=? AND (finished>0)=? AND"
                    + " (created<? OR (created=? AND id<?)) ORDER BY created DESC,id DESC LIMIT ?",
                network,
                history ? 1 : 0,
                before,
                before,
                beforeId,
                limit + 1);
        var r = s.executeQuery()) {
      List<Crafting.Order> result = new ArrayList<>();
      while (r.next()) result.add(gson.fromJson(r.getString(1), Crafting.Order.class));
      String next = "";
      if (result.size() > limit) {
        result.remove(result.size() - 1);
        Crafting.Order last = result.get(result.size() - 1);
        next =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (last.createdAt() + ":" + last.id())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      return new Crafting.OrderPage(result, next);
    }
  }

  private Crafting.Order readOrder(Connection connection, String sql, Object... args)
      throws SQLException {
    try (var s = prepare(connection, sql, args);
        var r = s.executeQuery()) {
      return r.next() ? gson.fromJson(r.getString(1), Crafting.Order.class) : null;
    }
  }

  public void maintain(long now) throws SQLException, IOException {
    if (now - lastMaintenance < 60_000) return;
    lastMaintenance = now;
    ensureWriter();
    maintaining = true;
    try (var operation = watchdog.watch(writer, 0, null)) {
      if (now - lastActivityRefresh >= 3_600_000) {
        try (var touch =
            writer.prepareStatement(
                "UPDATE series SET last_active=MAX(last_active,?) WHERE network=? AND"
                    + " resource=?")) {
          for (Snapshot sample : previous.values())
            for (var value : sample.values().values())
              if (value.quantity().signum() > 0 || value.craftable()) {
                touch.setLong(1, sample.timestamp());
                touch.setString(2, sample.networkId());
                touch.setString(3, value.resource().id());
                touch.addBatch();
              }
          touch.executeBatch();
        }
        lastActivityRefresh = now;
      }
      List<String[]> expired = new ArrayList<>();
      try (var s =
              prepare(
                  writer,
                  "SELECT network,resource FROM series s WHERE last_active<? AND NOT EXISTS"
                      + " (SELECT 1 FROM inventory_capabilities c WHERE c.network=s.network"
                      + " AND c.resource=s.resource AND c.craftable=1 AND EXISTS(SELECT 1"
                      + " FROM networks n WHERE n.id=c.network AND n.last_seen>=?)) LIMIT 1000",
                  now - 30 * History.DAY,
                  now - 30 * History.DAY);
          var r = s.executeQuery()) {
        while (r.next()) expired.add(new String[] {r.getString(1), r.getString(2)});
      }
      for (String[] key : expired) {
        for (String table : List.of("raw_changes", "rollup_1m", "rollup_15m", "series"))
          update(
              writer, "DELETE FROM " + table + " WHERE network=? AND resource=?", key[0], key[1]);
      }
      prune("raw_changes", Math.max(retainedFrom, now - History.DAY));
      prune("rollup_1m", Math.max(retainedFrom, now - 7 * History.DAY));
      prune("rollup_15m", Math.max(retainedFrom, now - 30 * History.DAY));
      update(
          writer, "DELETE FROM sample_frames WHERE t<?", Math.max(retainedFrom, now - History.DAY));
      for (long tier : new long[] {10_000, 60_000, 900_000})
        update(
            writer,
            "DELETE FROM coverage WHERE tier=? AND bucket<?",
            tier,
            Math.max(
                    retainedFrom,
                    now
                        - (tier == 10_000
                            ? History.DAY
                            : tier == 60_000 ? 7 * History.DAY : 30 * History.DAY))
                - tier);
      update(writer, "DELETE FROM network_events WHERE t<?", now - 30 * History.DAY);
      update(
          writer,
          "DELETE FROM crafting_orders WHERE finished>0 AND finished<?",
          now - 30 * History.DAY);
      execute(writer, "PRAGMA wal_checkpoint(PASSIVE)");
      bytes = directoryBytes();
      long page = scalar(writer, "PRAGMA page_size"),
          free = scalar(writer, "PRAGMA freelist_count") * page;
      if (bytes - free > budget * 85 / 100) {
        for (int round = 0;
            round < 8
                && directoryBytes() - scalar(writer, "PRAGMA freelist_count") * page
                    > budget * 75 / 100;
            round++) {
          long oldest = scalar(writer, "SELECT COALESCE(MIN(bucket)," + now + ") FROM coverage");
          long nextCutoff = Math.min(now - maximumGap, oldest + 6 * 3_600_000);
          if (nextCutoff <= retainedFrom) break;
          retainedFrom = nextCutoff;
          for (String table : List.of("raw_changes", "rollup_1m", "rollup_15m"))
            prune(table, retainedFrom);
          update(writer, "DELETE FROM coverage WHERE bucket<?", retainedFrom);
          update(writer, "DELETE FROM sample_frames WHERE t<?", retainedFrom);
        }
        update(
            writer,
            "UPDATE meta SET value=? WHERE key='retainedFrom'",
            Long.toString(retainedFrom));
      }
      update(
          writer,
          "DELETE FROM series WHERE NOT EXISTS(SELECT 1 FROM raw_changes r WHERE"
              + " r.network=series.network AND r.resource=series.resource) AND NOT EXISTS(SELECT 1"
              + " FROM rollup_1m r WHERE r.network=series.network AND r.resource=series.resource)"
              + " AND NOT EXISTS(SELECT 1 FROM rollup_15m r WHERE r.network=series.network AND"
              + " r.resource=series.resource)");
      update(
          writer,
          "DELETE FROM resources WHERE NOT EXISTS(SELECT 1 FROM series s WHERE"
              + " s.resource=resources.id)");
      execute(writer, "PRAGMA wal_checkpoint(TRUNCATE)");
      update(
          writer,
          "DELETE FROM controller_anchors WHERE network IN(SELECT id FROM networks WHERE"
              + " last_seen<? AND NOT EXISTS(SELECT 1 FROM series s WHERE s.network=networks.id)"
              + " AND NOT EXISTS(SELECT 1 FROM crafting_orders o WHERE o.network=networks.id))",
          now - 30 * History.DAY);
      update(
          writer,
          "DELETE FROM networks WHERE last_seen<? AND NOT EXISTS(SELECT 1 FROM series s WHERE"
              + " s.network=networks.id) AND NOT EXISTS(SELECT 1 FROM crafting_orders o WHERE"
              + " o.network=networks.id)",
          now - 30 * History.DAY);
      update(
          writer,
          "DELETE FROM inventory_capabilities WHERE NOT EXISTS(SELECT 1 FROM networks n WHERE"
              + " n.id=inventory_capabilities.network) OR NOT EXISTS(SELECT 1 FROM series s WHERE"
              + " s.network=inventory_capabilities.network AND"
              + " s.resource=inventory_capabilities.resource)");
      bytes = directoryBytes();
    } catch (SQLException | RuntimeException e) {
      try {
        discardWriter();
      } catch (SQLException close) {
        e.addSuppressed(close);
      }
      throw e;
    } finally {
      statisticsRevisions.replaceAll((network, revision) -> revisionSequence.incrementAndGet());
      maintaining = false;
    }
  }

  private void prune(String table, long cutoff) throws SQLException {
    // Retain one seed per surviving segment/series; it is not an observation inside the retained
    // window.
    update(
        writer,
        "DELETE FROM "
            + table
            + " AS old WHERE t<? AND (EXISTS(SELECT 1 FROM "
            + table
            + " AS newer WHERE newer.network=old.network AND newer.resource=old.resource AND"
            + " newer.segment=old.segment AND newer.t>old.t AND newer.t<?) OR NOT EXISTS(SELECT 1"
            + " FROM coverage c WHERE c.network=old.network AND c.segment=old.segment AND"
            + " c.last_at>=?))",
        cutoff,
        cutoff,
        cutoff);
  }

  private long directoryBytes() throws IOException {
    try (var files = Files.list(path.getParent())) {
      long total = 0;
      for (Path file : files.toList()) if (Files.isRegularFile(file)) total += Files.size(file);
      return total;
    }
  }

  private record ReadLease(
      Connection connection, SqliteWatchdog.Operation operation, Semaphore permits)
      implements AutoCloseable {
    @Override
    public void close() throws SQLException {
      operation.close();
      try {
        connection.close();
      } finally {
        permits.release();
      }
    }
  }

  private ReadLease reader() throws SQLException {
    try {
      if (!readers.tryAcquire(50, TimeUnit.MILLISECONDS))
        throw new SQLException("History reader busy");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("Query cancelled", e);
    }
    Connection c = null;
    try {
      c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
      execute(c, "PRAGMA query_only=ON");
      execute(c, "PRAGMA busy_timeout=1000");
      execute(c, "PRAGMA cache_size=-4096");
      return new ReadLease(c, watchdog.watch(c, 3000, null), readers);
    } catch (SQLException | RuntimeException e) {
      if (c != null)
        try {
          c.close();
        } catch (SQLException close) {
          e.addSuppressed(close);
        }
      readers.release();
      throw e;
    }
  }

  private static void checkInterrupted() throws SQLException {
    SqliteWatchdog.checkCurrent();
  }

  private static PreparedStatement prepare(Connection c, String sql, Object... args)
      throws SQLException {
    checkInterrupted();
    var s = c.prepareStatement(sql);
    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
    return s;
  }

  private static int update(Connection c, String sql, Object... args) throws SQLException {
    try (var s = prepare(c, sql, args)) {
      return s.executeUpdate();
    }
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      return r.next() ? r.getLong(1) : 0;
    }
  }

  private static void execute(Connection c, String sql) throws SQLException {
    checkInterrupted();
    try (var s = c.createStatement()) {
      s.execute(sql);
    }
  }

  @Override
  public void close() throws SQLException {
    watchdog.close();
    if (writer == null) return;
    try {
      execute(writer, "PRAGMA wal_checkpoint(TRUNCATE)");
    } finally {
      writer.close();
    }
  }
}
