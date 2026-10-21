package local.ae2dashboard.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import local.ae2dashboard.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetStressTest {
  @TempDir Path directory;

  @Test
  void churnReclaimsHistoryAndNeverPublishesPartialTransactions() throws Exception {
    long budget = 16L * 1024 * 1024, start = 10 * History.DAY;
    int saved = 0, rejected = 0;
    List<Resource> keys = new ArrayList<>();
    for (int i = 0; i < 120; i++)
      keys.add(
          new Resource(
              "key" + i,
              "group:item:test:r" + i,
              "item",
              "test:r" + i,
              "Resource " + i,
              "个",
              1,
              "{id:\"test:r" + i + "\"}"));
    try (var store = new HistoryStore(directory, budget)) {
      store.saveNetwork(new Network("n", start, start, "online", Set.of("a")), null, Set.of());
      for (int frame = 0; frame < 700; frame++) {
        final int count = frame;
        var values =
            keys.stream()
                .map(r -> new Resource.Stored(r, BigInteger.valueOf(10000 + count)))
                .toList();
        try {
          store.append(Snapshot.of("n", "s", start + frame * 10000L, frame, values));
          saved++;
        } catch (java.io.IOException | java.sql.SQLException e) {
          assertTrue(store.status().startsWith("paused"));
          rejected++;
        }
        long bytes;
        try (var files = Files.list(directory)) {
          bytes =
              files
                  .mapToLong(
                      p -> {
                        try {
                          return Files.size(p);
                        } catch (Exception e) {
                          throw new RuntimeException(e);
                        }
                      })
                  .sum();
        }
        assertTrue(bytes <= budget, "History exceeded directory budget: " + bytes);
      }
      assertTrue(saved > 100);
      var result = store.history("n", List.of("key0"), start, start + 6990000, 0, start + 6990000);
      assertTrue(
          result.truncated() || rejected > 0, "Workload should exercise capacity management");
      assertTrue(
          result.series().get(0).points().stream()
              .allMatch(
                  p -> new BigInteger(p.quantity()).compareTo(BigInteger.valueOf(10000)) >= 0));
    }
  }
}
