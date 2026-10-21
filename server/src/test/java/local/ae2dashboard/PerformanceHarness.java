package local.ae2dashboard;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import local.ae2dashboard.model.*;
import local.ae2dashboard.storage.HistoryStore;

/** Reproducible synthetic I/O workload; it does not measure native AE calls or game ticks. */
public final class PerformanceHarness {
  public static void main(String[] args) throws Exception {
    String mode = args.length == 0 ? "large" : args[0];
    Path directory = Files.createTempDirectory(Path.of(".local"), "performance-" + mode + "-");
    long start = System.nanoTime(), now = System.currentTimeMillis();
    try (var store =
        new HistoryStore(directory, (mode.equals("large") ? 2048L : 64L) * 1024 * 1024)) {
      if (mode.equals("large")) {
        List<Resource.Stored> stable = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
          Resource r = resource(i);
          stable.add(new Resource.Stored(r, BigInteger.valueOf(1000000L + i)));
        }
        long construct = System.nanoTime();
        var snapshot = Snapshot.of("network0", "s", now, 1, stable);
        System.out.println("snapshot_10000_ms=" + (System.nanoTime() - construct) / 1e6);
        for (int n = 0; n < 10; n++) {
          store.saveNetwork(
              new Network("network" + n, now, now, "online", Set.of("anchor" + n)), null, Set.of());
          store.append(Snapshot.of("network" + n, "s", now, 1, stable));
        }
        long stableStart = System.nanoTime();
        for (int n = 0; n < 10; n++)
          store.append(Snapshot.of("network" + n, "s", now + 10000, 2, stable));
        System.out.println(
            "10x10000_unchanged_write_ms=" + (System.nanoTime() - stableStart) / 1e6);
        List<Resource.Stored> changed =
            stable.stream()
                .map(
                    v ->
                        new Resource.Stored(v.resource(), v.quantity().add(BigInteger.valueOf(79))))
                .toList();
        long changedStart = System.nanoTime();
        for (int n = 0; n < 10; n++)
          store.append(Snapshot.of("network" + n, "s", now + 20000, 3, changed));
        System.out.println("10x10000_changed_write_ms=" + (System.nanoTime() - changedStart) / 1e6);
        long statisticsStart = System.nanoTime();
        int statisticsRows = 0;
        long version = -1;
        for (int offset = 0; offset < 10000; offset += 2000) {
          var page =
              store.statistics("network0", false, now, now + 20000, offset, 2000, now + 20000);
          if (version != -1 && version != page.version())
            throw new AssertionError("Mixed statistics pages");
          version = page.version();
          for (var row : page.rows())
            if (!"79".equals(row.delta()) || !"237".equals(row.rate()))
              throw new AssertionError("Incorrect interval statistics");
          statisticsRows += page.rows().size();
        }
        if (statisticsRows != 10000) throw new AssertionError("Incomplete statistics catalog");
        System.out.println(
            "10000_statistics_rows_ms=" + (System.nanoTime() - statisticsStart) / 1e6);

      } else if (mode.equals("dense")) {
        long beginning = now / 10_000 * 10_000 - 11_990_000;
        store.saveNetwork(
            new Network("network0", beginning, beginning, "online", Set.of("a")), null, Set.of());
        for (int i = 0; i < 1200; i++) {
          List<Resource.Stored> values = new ArrayList<>();
          for (int j = 0; j < 6; j++)
            values.add(
                new Resource.Stored(
                    resource(j), BigInteger.valueOf(9007199254740993L + i * 11L + j)));
          store.append(Snapshot.of("network0", "s", beginning + i * 10_000L, i, values));
        }
        long queryStart = System.nanoTime();
        var result =
            store.history(
                "network0",
                List.of("key0", "key1", "key2", "key3", "key4", "key5"),
                beginning,
                beginning + 11_990_000,
                10_000,
                now);
        if (result.series().size() != 6
            || result.series().stream().anyMatch(s -> s.points().size() != 1200))
          throw new AssertionError("Expected six complete 1200 point series");
        System.out.println("6x1200_query_ms=" + (System.nanoTime() - queryStart) / 1e6);
      } else if (mode.equals("long")) {
        long beginning = now - 30 * History.DAY + 10000;
        store.saveNetwork(
            new Network("network0", beginning, beginning, "online", Set.of("a")), null, Set.of());
        Resource r = resource(0);
        int samples = (int) (30 * History.DAY / 10000);
        for (int i = 0; i < samples; i++) {
          long t = beginning + i * 10000L;
          store.append(
              Snapshot.of(
                  "network0",
                  "s",
                  t,
                  i,
                  List.of(new Resource.Stored(r, BigInteger.valueOf(10000 + (i / 6) % 1000)))));
          if (i > 0 && i % 50000 == 0) System.out.println("samples=" + i);
        }
        store.maintain(now + 60000);
        long queryStart = System.nanoTime();
        var result =
            store.history(
                "network0", List.of("key0", resource(0).groupId()), beginning, now, 0, now);
        System.out.println(
            "30_day_points="
                + result.series().get(0).points().size()
                + "; step="
                + result.step()
                + "; query_ms="
                + (System.nanoTime() - queryStart) / 1e6);
      } else throw new IllegalArgumentException("Unknown workload: " + mode);
      var pool = Executors.newFixedThreadPool(20);
      long queryStart = System.nanoTime();
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < 20; i++)
        futures.add(
            pool.submit(
                () -> {
                  try {
                    store.history(
                        "network0",
                        List.of("key0", "key1", "key2", "key3", "key4", "key5"),
                        now - 3600000,
                        now + 20000,
                        0,
                        now + 20000);
                    return true;
                  } catch (Exception e) {
                    return false;
                  }
                }));
      int successful = 0;
      for (var future : futures) if (future.get()) successful++;
      pool.shutdown();
      System.out.println(
          "20_direct_query_requests_completed="
              + successful
              + "; ms="
              + (System.nanoTime() - queryStart) / 1e6
              + " (busy responses are bounded)");
      System.out.println("database_bytes=" + store.bytes());
    }
    long actual;
    try (var files = Files.list(directory)) {
      actual =
          files
              .mapToLong(
                  p -> {
                    try {
                      return Files.size(p);
                    } catch (Exception e) {
                      return 0;
                    }
                  })
              .sum();
    }
    System.out.println(
        "mode="
            + mode
            + "; elapsed_s="
            + (System.nanoTime() - start) / 1e9
            + "; final_bytes="
            + actual
            + "; directory="
            + directory);
  }

  private static Resource resource(int i) {
    String id = "test:r" + i;
    return new Resource(
        "key" + i, Resource.group("item", id), "item", id, id, "个", 1, "{id:\"" + id + "\"}");
  }
}
