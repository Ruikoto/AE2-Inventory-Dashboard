package local.ae2dashboard.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import local.ae2dashboard.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HistoryStoreTest {
  @TempDir Path directory;
  private final Resource resource =
      new Resource("item1", "group:item:test:x", "item", "test:x", "Test", "个", 1, "{id:test:x}");

  private Snapshot sample(long t, String segment, String amount) {
    return Snapshot.of(
        "network",
        segment,
        t,
        t,
        amount == null
            ? List.of()
            : List.of(new Resource.Stored(resource, new BigInteger(amount))));
  }

  private HistoryStore open() throws Exception {
    var store = new HistoryStore(directory, 64 * 1024 * 1024);
    store.saveNetwork(new Network("network", 1, 1, "online", Set.of("a")), null, Set.of());
    return store;
  }

  @Test
  void historyUsesBoundedLookbackWithoutExpandingTheVisibleRange() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (int i = 0; i <= 90; i++)
        store.append(sample(t + i * 10000, "s", "" + (i * 10 + (i == 90 ? 100 : 0))));
      long[] windows = {60000, 300000, 900000};
      String[] rates = {"160", "80", "66.666667"};
      for (int i = 0; i < windows.length; i++) {
        var result =
            store.history(
                "network", List.of("item1"), t + 890000, t + 900000, 10000, t + 900000, windows[i]);
        var narrow = result.series().get(0);
        var wide =
            store
                .history("network", List.of("item1"), t, t + 900000, 10000, t + 900000, windows[i])
                .series()
                .get(0);
        var stats =
            store
                .statistics("network", false, t + 890000, t + 900000, 0, 10, t + 900000)
                .rows()
                .get(0);
        assertEquals(t + 890000, result.availableFrom());
        assertFalse(result.truncated());
        assertEquals(2, narrow.points().size());
        assertEquals("60", narrow.points().get(0).smoothedRate());
        assertEquals(rates[i], narrow.points().get(1).smoothedRate());
        assertEquals(wide.points().get(90).smoothedRate(), narrow.points().get(1).smoothedRate());
        assertNull(narrow.points().get(0).rate());
        assertEquals("110", narrow.delta());
        assertEquals("660", narrow.rate());
        assertEquals(2, narrow.statisticsSamples());
        assertEquals(stats.delta(), narrow.delta());
        assertEquals(stats.rate(), narrow.rate());
        assertEquals(stats.statisticsFrom(), narrow.statisticsFrom());
        assertEquals(stats.statisticsScope(), narrow.statisticsScope());
      }
      var linear =
          store
              .history("network", List.of("item1"), t + 830000, t + 890000, 10000, t + 900000)
              .series()
              .get(0);
      assertEquals("60", linear.points().get(0).smoothedRate());
      assertEquals("60", linear.delta());
      assertEquals("60", linear.rate());
      assertEquals(7, linear.statisticsSamples());
      var single =
          store
              .history("network", List.of("item1"), t + 900000, t + 910000, 10000, t + 910000)
              .series()
              .get(0);
      assertEquals("80", single.points().get(0).smoothedRate());
      assertNull(single.points().get(0).rate());
      assertNull(single.delta());
      assertNull(single.rate());
      assertEquals(1, single.statisticsSamples());
      assertEquals("insufficient", single.statisticsScope());
    }
  }

  @Test
  void coarseCoverageOrdersSegmentsByObservationTime() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (long tier : new long[] {60000, 900000}) {
        long start = t + (tier == 60000 ? 0 : 900000);
        long now = start + (tier == 60000 ? 2 : 8) * History.DAY;
        store.append(sample(start, "z", "9000"));
        store.append(sample(start + 10000, "z", "9010"));
        store.append(sample(start + 20000, "a", "20"));
        var first =
            store
                .history("network", List.of("item1"), start, start + 20000, tier, now)
                .series()
                .get(0);
        assertEquals(start + 20000, first.statisticsFrom());
        assertNull(first.delta());
        assertNull(first.points().get(1).smoothedRate());
        for (long offset = 30000; offset <= tier + 20000; offset += 10000)
          store.append(sample(start + offset, "a", "" + offset / 1000));
        for (long end : new long[] {start + tier - 10000, start + tier + 20000}) {
          var history =
              store.history("network", List.of("item1"), start, end, tier, now).series().get(0);
          var stats = store.statistics("network", false, start, end, 0, 10, now).rows().get(0);
          assertEquals(start + 20000, history.statisticsFrom());
          assertEquals(end, history.statisticsTo());
          assertEquals((end - start - 20000) / 10000 + 1, history.statisticsSamples());
          assertEquals("" + (end - start - 20000) / 1000, history.delta());
          assertEquals("60", history.rate());
          assertEquals(stats.statisticsFrom(), history.statisticsFrom());
          assertEquals(stats.statisticsTo(), history.statisticsTo());
          assertEquals(stats.statisticsSamples(), history.statisticsSamples());
          assertEquals(stats.delta(), history.delta());
          assertEquals(stats.rate(), history.rate());
          assertEquals("latest-continuous", history.statisticsScope());
        }
      }
    }
  }

  @Test
  void lookbackKeepsTheRequestedTierAtAgeAndRetentionBoundaries() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (int i = -90; i <= 11; i++)
        store.append(sample(t + i * 10000, "s", "" + (1000 + i * 10)));
      for (long age : new long[] {History.DAY, 7 * History.DAY}) {
        long step = age == History.DAY ? 10000 : 60000;
        var result =
            store.history("network", List.of("item1"), t, t + 110000, step, t + age, 900000);
        assertEquals(step, result.step());
        assertEquals(t, result.availableFrom());
        assertFalse(result.truncated());
        assertEquals("60", result.series().get(0).points().get(0).smoothedRate());
        assertEquals(12, result.series().get(0).statisticsSamples());
      }
      store.maintain(t + History.DAY);
      var retained =
          store
              .history("network", List.of("item1"), t, t + 110000, 10000, t + History.DAY)
              .series()
              .get(0);
      assertNull(retained.points().get(0).smoothedRate());
      assertEquals("60", retained.points().get(1).smoothedRate());
      assertEquals("1000", retained.points().get(0).quantity());
      assertEquals(12, retained.statisticsSamples());
      assertEquals("110", retained.delta());
    }
  }

  @Test
  void lookbackUsesRealZeroObservationsAndNeverBridgesRestartOrGap() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (int i = 0; i <= 6; i++)
        store.append(sample(t + i * 10000, "s", i < 5 ? null : "" + (50 + i * 10)));
      var added =
          store
              .history("network", List.of("item1"), t + 50000, t + 60000, 10000, t + 60000, 60000)
              .series()
              .get(0);
      assertEquals("120", added.points().get(0).smoothedRate());
      assertEquals("110", added.points().get(1).smoothedRate());
      assertEquals("10", added.delta());
      store.append(sample(t + 100000, "s", "5000"));
      var gap =
          store
              .history("network", List.of("item1"), t + 100000, t + 110000, 10000, t + 110000)
              .series()
              .get(0);
      assertNull(gap.points().get(0).smoothedRate());
      assertNull(gap.delta());
    }
    try (var store = open()) {
      store.append(sample(t + 110000, "s", "6000"));
      var restart =
          store
              .history("network", List.of("item1"), t + 110000, t + 120000, 10000, t + 120000)
              .series()
              .get(0);
      assertNull(restart.points().get(0).smoothedRate());
      assertNull(restart.delta());
    }
  }

  @Test
  void multipleNetworksStayContinuousAcrossMaintenanceAndRetention() throws Exception {
    long t = 10 * History.DAY, end = t + 420000;
    try (var store = open()) {
      for (int network = 0; network < 4; network++)
        store.saveNetwork(
            new Network("n" + network, t, t, "online", Set.of("a" + network)), null, Set.of());
      for (int i = 0; i <= 42; i++) {
        long at = t + i * 10000;
        for (int network = 0; network < 4; network++)
          store.append(
              Snapshot.of(
                  "n" + network,
                  "s",
                  at,
                  at,
                  List.of(
                      new Resource.Stored(resource, BigInteger.valueOf(network * 1000 + i * 10)))));
      }
      for (long age : new long[] {0, History.DAY}) {
        if (age != 0) store.maintain(end + age);
        for (int network = 0; network < 4; network++) {
          var history =
              store.history("n" + network, List.of("item1"), t, end, 0, end + age).series().get(0);
          var stats =
              store.statistics("n" + network, false, t, end, 0, 10, end + age).rows().get(0);
          assertEquals(1, history.points().stream().map(History.Point::segment).distinct().count());
          assertEquals(43, history.statisticsSamples());
          assertEquals("420", history.delta());
          assertEquals("60", history.rate());
          assertEquals(stats.delta(), history.delta());
          assertEquals(stats.statisticsSamples(), history.statisticsSamples());
          assertTrue(history.complete());
        }
      }
    }
  }

  @Test
  void quarterRetainsLeadingFlatMinutesForRisingAndFallingInventory() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (int direction : new int[] {1, -1}) {
        long start = t + (direction == 1 ? 0 : 900000);
        int initial = direction == 1 ? 100 : 200;
        for (int i = -1; i < 90; i++)
          store.append(
              sample(
                  start + i * 10000,
                  "s" + direction,
                  String.valueOf(initial + (i < 6 ? 0 : direction * 100))));
        var history =
            store
                .history(
                    "network",
                    List.of("item1"),
                    start,
                    start + 890000,
                    900000,
                    start + 8 * History.DAY)
                .series()
                .get(0);
        var stats =
            store
                .statistics("network", false, start, start + 890000, 0, 10, start + 8 * History.DAY)
                .rows()
                .get(0);
        assertEquals(String.valueOf(direction * 100), history.delta());
        assertEquals(history.delta(), stats.delta());
        assertEquals(String.valueOf(initial), history.points().get(0).firstQuantity());
        assertEquals("100", history.points().get(0).min());
        assertEquals("200", history.points().get(0).max());
      }
    }
  }

  @Test
  void quarterUsesActualRestartObservationAsBaseline() throws Exception {
    long quarter = 10 * History.DAY, start = quarter + 310000;
    try (var store = open()) {
      store.append(sample(quarter + 100000, "s", "500"));
    }
    try (var store = open()) {
      store.append(sample(start, "s", "100"));
      var first =
          store
              .history(
                  "network",
                  List.of("item1"),
                  start,
                  start + 10000,
                  900000,
                  start + 8 * History.DAY)
              .series()
              .get(0);
      assertEquals("100", first.points().get(0).firstQuantity());
      assertEquals("100", first.points().get(0).min());
      assertNull(first.delta());
      store.append(sample(start + 10000, "s", "100"));
      var history =
          store
              .history(
                  "network",
                  List.of("item1"),
                  start,
                  start + 10000,
                  900000,
                  start + 8 * History.DAY)
              .series()
              .get(0);
      var stats =
          store
              .statistics("network", false, start, start + 10000, 0, 10, start + 8 * History.DAY)
              .rows()
              .get(0);
      assertEquals("0", history.delta());
      assertEquals("0", history.rate());
      assertEquals("0", history.points().get(0).smoothedRate());
      assertEquals(history.delta(), stats.delta());
    }
  }

  @Test
  void quarterNewResourceAfterCoverageStillStartsAtZero() throws Exception {
    long start = 10 * History.DAY + 310000;
    try (var store = open()) {
      for (int i = 0; i <= 7; i++)
        store.append(sample(start + i * 10000, "s", i < 7 ? null : "100"));
      var history =
          store
              .history(
                  "network",
                  List.of("item1"),
                  start,
                  start + 70000,
                  900000,
                  start + 8 * History.DAY)
              .series()
              .get(0);
      var stats =
          store
              .statistics("network", false, start, start + 70000, 0, 10, start + 8 * History.DAY)
              .rows()
              .get(0);
      assertEquals("0", history.points().get(0).firstQuantity());
      assertEquals("0", history.points().get(0).min());
      assertEquals("100", history.delta());
      assertEquals(history.delta(), stats.delta());
    }
  }

  @Test
  void retentionKeepsTableAndDetailCompletenessConsistent() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (int i = 0; i <= 12; i++) store.append(sample(t + i * 10000, "s", "" + (100 + i)));
      var cutoff = HistoryStore.class.getDeclaredField("retainedFrom");
      cutoff.setAccessible(true);
      cutoff.setLong(store, t + 60000);
      var result = store.history("network", List.of("item1"), t, t + 120000, 10000, t + 120000);
      var detail = result.series().get(0);
      var table =
          store.statistics("network", false, t, t + 120000, 0, 10, t + 120000).rows().get(0);
      assertTrue(result.truncated());
      assertFalse(detail.complete());
      assertEquals("latest-continuous", detail.statisticsScope());
      assertEquals(table.statisticsScope(), detail.statisticsScope());
      assertEquals(table.delta(), detail.delta());
    }
  }

  @Test
  void statisticsRevisionTracksMaintenanceButNotOtherNetworkSamples() throws Exception {
    long t = 40 * History.DAY, latest = t + 29 * History.DAY;
    try (var store = open()) {
      store.append(sample(t, "old", "10"));
      var other =
          new Resource(
              "item2", "group:item:test:y", "item", "test:y", "Other", "个", 1, "{id:test:y}");
      for (int i = 0; i < 2; i++)
        store.append(
            Snapshot.of(
                "network",
                "new",
                latest + i * 10000,
                latest + i * 10000,
                List.of(new Resource.Stored(other, BigInteger.TEN))));
      var before =
          store.statistics("network", false, latest, latest + 10000, 0, 10, latest + 10000);
      store.saveNetwork(new Network("other", 1, 1, "online", Set.of("b")), null, Set.of());
      store.append(
          Snapshot.of(
              "other",
              "other",
              latest + 20000,
              latest + 20000,
              List.of(new Resource.Stored(other, BigInteger.TEN))));
      assertEquals(
          before.version(),
          store
              .statistics("network", false, latest, latest + 10000, 0, 10, latest + 10000)
              .version());
      store.maintain(t + 30 * History.DAY + 10000);
      var after = store.statistics("network", false, latest, latest + 10000, 0, 10, latest + 10000);
      assertEquals(2, before.total());
      assertEquals(1, after.total());
      assertNotEquals(before.version(), after.version());
    }
  }

  @Test
  void observationCountsOnlyLatestNonzeroExactResources() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      assertEquals(new HistoryStore.Observation(0, 0, 0), store.lastObservation("network"));
      store.append(sample(t, "s", "92233720368547758080"));
      assertEquals(new HistoryStore.Observation(t, 1, 0), store.lastObservation("network"));
      store.append(sample(t + 10000, "s", null));
      assertEquals(new HistoryStore.Observation(t + 10000, 0, 0), store.lastObservation("network"));
      store.append(sample(t + 20000, "s", "100"));
      store.append(sample(t + 100000, "next", null));
      assertEquals(
          new HistoryStore.Observation(t + 100000, 0, 0), store.lastObservation("network"));
    }
  }

  @Test
  void intervalStatisticsIgnoreLastTickReversalAndUseLatestSegment() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (int i = 0; i <= 36; i++)
        store.append(sample(t + i * 10000, "s", String.valueOf(i == 36 ? 449 : 100 + i * 10)));
      var page = store.statistics("network", false, t - 100000, t + 360000, 0, 2000, t + 360000);
      assertEquals(1, page.total());
      var value = page.rows().get(0);
      assertEquals("349", value.delta());
      assertEquals("58.166667", value.rate());
      assertEquals("latest-continuous", value.statisticsScope());
      for (long step : new long[] {10000, 60000, 300000}) {
        var history =
            store
                .history("network", List.of("item1"), t - 100000, t + 360000, step, t + 360000)
                .series()
                .get(0);
        assertEquals(value.delta(), history.delta());
        assertEquals(value.rate(), history.rate());
        assertEquals(value.statisticsFrom(), history.statisticsFrom());
        assertTrue(
            new java.math.BigDecimal(
                        history.points().get(history.points().size() - 1).smoothedRate())
                    .signum()
                > 0);
      }
      store.append(sample(t + 500000, "next", "1000"));
      var single =
          store.statistics("network", false, t, t + 500000, 0, 10, t + 500000).rows().get(0);
      assertNull(single.delta());
      assertEquals("insufficient", single.statisticsScope());
      store.append(sample(t + 510000, "next", "1020"));
      var resumed =
          store.statistics("network", false, t, t + 510000, 0, 10, t + 510000).rows().get(0);
      assertEquals("20", resumed.delta());
      assertEquals("120", resumed.rate());
      assertEquals(t + 500000, resumed.statisticsFrom());
      var curve =
          store
              .history("network", List.of("item1"), t, t + 510000, 10000, t + 510000)
              .series()
              .get(0);
      assertEquals(resumed.delta(), curve.delta());
      assertNull(curve.points().get(curve.points().size() - 2).smoothedRate());
    }
  }

  @Test
  void batchAndChartAgreeForSparseRollupsAndExactLargeQuantities() throws Exception {
    long t = 10 * History.DAY;
    BigInteger base = new BigInteger("92233720368547758080");
    try (var store = open()) {
      for (int i = 0; i < 180; i++)
        store.append(
            sample(
                t + i * 10000,
                "s",
                base.add(BigInteger.valueOf(i < 20 ? 0 : i < 120 ? 200 : 350)).toString()));
      for (long now : new long[] {t + 1800000, t + 2 * History.DAY, t + 8 * History.DAY}) {
        var stats =
            store.statistics("network", false, t + 10000, t + 1790000, 0, 10, now).rows().get(0);
        var h =
            store
                .history("network", List.of("item1"), t + 10000, t + 1790000, 0, now)
                .series()
                .get(0);
        assertEquals(h.delta(), stats.delta());
        assertEquals(h.rate(), stats.rate());
        assertEquals(h.statisticsFrom(), stats.statisticsFrom());
        assertEquals(h.statisticsTo(), stats.statisticsTo());
      }
    }
  }

  @Test
  void nativeRollbackCannotPoisonLaterTransactions() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      String worldId = store.worldId();
      store.append(sample(t, "s", "100"));
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite"));
          var statement = connection.createStatement()) {
        statement.execute(
            "CREATE TRIGGER fail_fixture BEFORE INSERT ON raw_changes WHEN NEW.q='999' BEGIN SELECT"
                + " RAISE(ROLLBACK,'synthetic failure'); END");
      }
      assertThrows(SQLException.class, () -> store.append(sample(t + 10000, "s", "999")));
      store.saveNetwork(
          new Network("network", 1, t + 20000, "online", Set.of("a")), null, Set.of());
      store.append(sample(t + 20000, "s", "123"));
      assertEquals(worldId, store.worldId());
      assertEquals("recording", store.status());
      var result =
          store
              .history("network", List.of("item1"), t, t + 20000, 10000, t + 20000)
              .series()
              .get(0);
      assertEquals(
          List.of("100", "123"), result.points().stream().map(History.Point::quantity).toList());
      assertFalse(result.complete());
      assertNull(result.points().get(1).rate());
    }
  }

  @Test
  void completedReadsDoNotPoisonLargeCatalogMaintenance() throws Exception {
    long t = 10 * History.DAY;
    List<Resource.Stored> values = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      var resource =
          new Resource(
              "catalog" + i,
              "group:item:test:c" + i,
              "item",
              "test:c" + i,
              "Catalog",
              "个",
              1,
              "{id:" + i + "}");
      values.add(new Resource.Stored(resource, BigInteger.valueOf(i + 1)));
    }
    try (var store = new HistoryStore(directory, 128 * 1024 * 1024)) {
      store.saveNetwork(new Network("network", 1, 1, "online", Set.of("a")), null, Set.of());
      store.append(Snapshot.of("network", "s", t, 1, values));
      store.history("network", List.of("catalog0"), t, t + 10000, 10000, t + 10000);
      // Synthetic sample time alone does not expire the old callback's wall-clock deadline.
      Thread.sleep(3100);
      for (int i = 1; i <= 18; i++) {
        long at = t + i * 10000;
        store.append(Snapshot.of("network", "s", at, i + 1, values));
        store.history("network", List.of("catalog0", "catalog1999"), t, at, 10000, at);
      }
      assertEquals("recording", store.status());
      assertEquals(t + 180000, store.lastInventory("network", false, "", 0, 10).timestamp());
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite"));
          var statement = connection.createStatement();
          var result =
              statement.executeQuery(
                  "EXPLAIN QUERY PLAN SELECT 1 FROM series WHERE resource='catalog0'")) {
        boolean indexed = false;
        while (result.next()) indexed |= result.getString(4).contains("series_resource");
        assertTrue(indexed);
      }
    }
  }

  @Test
  void unchangedInventoryAndDisappearanceRemainObservable() throws Exception {
    long t = 10 * History.DAY + 1234;
    try (var store = open()) {
      store.append(sample(t, "s", "9007199254740993"));
      store.append(sample(t + 10_000, "s", "9007199254740993"));
      store.append(sample(t + 20_000, "s", null));
      var series =
          store
              .history("network", List.of("item1"), t, t + 20_000, 10_000, t + 20_000)
              .series()
              .get(0);
      assertEquals(
          List.of("9007199254740993", "9007199254740993", "0"),
          series.points().stream().map(History.Point::quantity).toList());
      assertEquals("-9007199254740993", series.delta());
    }
  }

  @Test
  void gapsDoNotBecomeZeroOrRates() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      store.append(sample(t, "s", "100"));
      store.append(sample(t + 10_000, "s", "120"));
      store.append(sample(t + 100_000, "s", "500"));
      var series =
          store
              .history("network", List.of("item1"), t, t + 100_000, 10_000, t + 100_000)
              .series()
              .get(0);
      assertFalse(series.complete());
      assertNull(series.delta());
      assertNull(series.points().get(2).rate());
    }
  }

  @Test
  void rollupsKeepExtremaAndFlatBuckets() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (int i = 0; i < 30; i++)
        store.append(sample(t + i * 10_000, "s", i == 2 ? "999" : i < 5 ? "100" : "200"));
      var series =
          store
              .history("network", List.of("item1"), t, t + 290_000, 60_000, t + 2 * History.DAY)
              .series()
              .get(0);
      assertEquals("999", series.points().get(0).max());
      assertEquals("200", series.points().get(series.points().size() - 1).quantity());
    }
  }

  @Test
  void groupsAreSampledBeforeAggregation() throws Exception {
    long t = 10 * History.DAY;
    var other =
        new Resource(
            "item2", resource.groupId(), "item", "test:x", "Other", "个", 1, "{id:test:x,tag:1}");
    try (var store = open()) {
      for (int i = 0; i < 6; i++)
        store.append(
            Snapshot.of(
                "network",
                "s",
                t + i * 10_000,
                i,
                List.of(
                    new Resource.Stored(resource, BigInteger.valueOf(i % 2 == 0 ? 100 : 0)),
                    new Resource.Stored(other, BigInteger.valueOf(i % 2 == 0 ? 0 : 100)))));
      var group =
          store
              .history(
                  "network",
                  List.of(resource.groupId()),
                  t,
                  t + 50_000,
                  60_000,
                  t + 2 * History.DAY)
              .series()
              .get(0);
      assertEquals("100", group.points().get(0).min());
      assertEquals("100", group.points().get(0).max());
    }
  }

  @Test
  void restartsPreserveWorldButStartNewSegment() throws Exception {
    long t = 10 * History.DAY;
    String world;
    try (var store = open()) {
      world = store.worldId();
      store.append(sample(t, "s", "1"));
    }
    try (var store = open()) {
      assertEquals(world, store.worldId());
      store.append(sample(t + 10_000, "s", "9"));
      var series =
          store
              .history("network", List.of("item1"), t, t + 10_000, 10_000, t + 10_000)
              .series()
              .get(0);
      assertFalse(series.complete());
    }
  }

  @Test
  void corruptDatabaseIsNotReplaced() throws Exception {
    byte[] original = "not a SQLite database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(directory.resolve("history.sqlite"), original);
    assertThrows(SQLException.class, this::open);
    assertArrayEquals(original, Files.readAllBytes(directory.resolve("history.sqlite")));
  }

  @Test
  void refusesNewerSchemaWithoutOverwriting() throws Exception {
    try (var store = open()) {}
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite"));
        var s = c.createStatement()) {
      s.execute("PRAGMA user_version=99");
    }
    assertThrows(SQLException.class, this::open);
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite"));
        var s = c.createStatement();
        var r = s.executeQuery("PRAGMA user_version")) {
      assertEquals(99, r.getInt(1));
    }
  }

  @Test
  void mergedIdentitiesStayRetiredAcrossRestart() throws Exception {
    try (var store = open()) {
      store.saveNetwork(
          new Network("retired", 1, 2, "merged", Set.of("old-anchor")),
          "merged",
          Set.of("network"));
    }
    try (var store = open()) {
      assertEquals(
          "merged",
          store.networks().stream()
              .filter(n -> n.id().equals("retired"))
              .findFirst()
              .orElseThrow()
              .status());
    }
  }

  @Test
  void lastInventoryRemainsReadableAfterRawDataExpires() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      store.append(sample(t, "s", "19"));
      store.append(sample(t + 10000, "s", "23"));
      store.maintain(t + 8 * History.DAY);
      var last = store.lastInventory("network", false, "", 0, 100);
      assertEquals(t + 10000, last.timestamp());
      assertEquals("23", last.rows().get(0).quantity().toString());
    }
  }

  @Test
  void retentionKeepsSeedWithoutKeepingExpiredCoverage() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      for (int i = 0; i < 20; i++) store.append(sample(t + i * 10_000, "s", "17"));
      store.maintain(t + 2 * History.DAY);
      var series =
          store
              .history("network", List.of("item1"), t, t + 190_000, 60_000, t + 2 * History.DAY)
              .series()
              .get(0);
      assertFalse(series.points().isEmpty());
      assertTrue(series.points().stream().allMatch(p -> p.quantity().equals("17")));
    }
  }

  @Test
  void aSingleDisplayBucketStillHasExactDelta() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      store.append(sample(t, "s", "100"));
      store.append(sample(t + 10000, "s", "130"));
      store.append(sample(t + 20000, "s", "120"));
      var series =
          store
              .history("network", List.of("item1"), t, t + 20000, 60000, t + 20000)
              .series()
              .get(0);
      assertEquals(1, series.points().size());
      assertEquals("20", series.delta());
      assertEquals("60", series.rate());
    }
  }

  @Test
  void retriesDoNotDoubleCountAndUnknownKeysDoNotBecomeZero() throws Exception {
    long t = 10 * History.DAY;
    try (var store = open()) {
      store.append(sample(t, "s", "1"));
      store.append(sample(t, "s", "1"));
      store.append(sample(t + 10000, "s", "2"));
      var series =
          store
              .history("network", List.of("item1"), t, t + 10000, 10000, t + 10000)
              .series()
              .get(0);
      assertEquals(2, series.points().size());
      assertTrue(
          store
              .history("network", List.of("unknown"), t, t + 10000, 10000, t + 10000)
              .series()
              .get(0)
              .points()
              .isEmpty());
    }
  }

  @Test
  void rejectsOversizeTransactionBeforeWritingAnySample() throws Exception {
    long t = 10 * History.DAY;
    List<Resource.Stored> values = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      var r =
          new Resource(
              "x" + i,
              "group:item:test:" + i,
              "item",
              "test:r" + i,
              "Test",
              "个",
              1,
              "x".repeat(2048));
      values.add(new Resource.Stored(r, BigInteger.ONE));
    }
    try (var store = new HistoryStore(directory, 16 * 1024 * 1024)) {
      assertThrows(
          java.io.IOException.class, () -> store.append(Snapshot.of("network", "s", t, 1, values)));
      assertEquals("paused:batch", store.status());
      assertTrue(store.resources(0, 100).isEmpty());
    }
  }
}
