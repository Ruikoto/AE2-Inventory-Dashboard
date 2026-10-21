package local.ae2dashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import local.ae2dashboard.model.*;
import local.ae2dashboard.storage.HistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardServiceTest {
  @TempDir Path directory;

  @Test
  void restoringManyNetworksDoesNotReportDroppedSamples() throws Exception {
    long now = System.currentTimeMillis();
    var ids = new ArrayList<String>();
    Resource resource =
        new Resource("exact", "group:item:test:x", "item", "test:x", "测试", "个", 1, "{id:test:x}");
    try (var store = new HistoryStore(directory.resolve("ae2-dashboard"), 64L * 1024 * 1024)) {
      for (int i = 0; i < 48; i++) {
        String id = UUID.randomUUID().toString();
        ids.add(id);
        store.saveNetwork(
            new Network(id, now - 1000, now, "online", Set.of("overworld " + i + ",0,0")),
            null,
            Set.of());
        store.append(
            Snapshot.of(
                id,
                "s",
                now,
                1,
                i % 2 == 0 ? List.of(new Resource.Stored(resource, BigInteger.TEN)) : List.of()));
      }
    }
    Config config =
        new Config(
            "127.0.0.1", 0, "public", "secret", 10, 64, false, directory.resolve("absent.zip"), "");
    try (var service = new DashboardService(directory, config)) {
      // 导出排在恢复任务之后，完成后才能断言后台恢复结果。
      service.exportKeys(directory).get(10, TimeUnit.SECONDS);
      assertEquals(0L, service.status().get("droppedSamples"));
      assertEquals("recording", service.status().get("historyStatus"));
      for (int i = 0; i < ids.size(); i++) {
        assertEquals(Long.valueOf(now), service.observedAt(ids.get(i)));
        assertEquals(Long.valueOf(i % 2 == 0 ? 1 : 0), service.nonZeroResources(ids.get(i)));
      }
    }
  }
}
