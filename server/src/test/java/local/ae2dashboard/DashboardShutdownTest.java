package local.ae2dashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardShutdownTest {
  @TempDir Path directory;

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
