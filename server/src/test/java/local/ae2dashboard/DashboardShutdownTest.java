package local.ae2dashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardShutdownTest {
  @TempDir Path directory;

  @Test
  void normalShutdownCheckpointsEvenWhenExecutorInterruptsItsIdleWorker() throws Exception {
    var config =
        new Config(
            "127.0.0.1", 0, "public", "secret", 10, 64, false, directory.resolve("absent.zip"), "");
    var service = new DashboardService(directory, config);
    try {
      service.write(() -> service.store().worldId()).get(5, TimeUnit.SECONDS);
      Path database = directory.resolve("ae2-dashboard/history.sqlite");
      Path wal = directory.resolve("ae2-dashboard/history.sqlite-wal");
      // 保留空闲读取连接，避免最后一个连接关闭时的隐式 checkpoint 掩盖回归。
      try (var reader = DriverManager.getConnection("jdbc:sqlite:" + database);
          var statement = reader.createStatement()) {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM meta")) {
          assertTrue(result.next());
        }
        assertTrue(Files.size(wal) > 0);
        service.close();
        assertEquals(0, Files.size(wal));
      }
    } finally {
      service.close();
    }
  }

  @Test
  void shutdownTimeoutCompletesDiscardedWritesExceptionally() throws Exception {
    var config =
        new Config(
            "127.0.0.1", 0, "public", "secret", 10, 64, false, directory.resolve("absent.zip"), "");
    var service = new DashboardService(directory, config);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var blocked =
        service.write(
            () -> {
              entered.countDown();
              release.await();
              return null;
            });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var queued = service.write(() -> "must not run");
      service.close();
      var failure = assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS));
      assertInstanceOf(RejectedExecutionException.class, failure.getCause());
      assertThrows(ExecutionException.class, () -> blocked.get(1, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      service.close();
    }
  }
}
