package local.ae2dashboard.forge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import local.ae2dashboard.model.Crafting.Failure;
import org.junit.jupiter.api.Test;

class AeCraftingQueueTest {
  @Test
  void unprocessedNativeCommandsAreBoundedAndShutdownCompletesWaitingRequests() {
    // 不驱动游戏 tick，因此命令不会访问这些原生依赖。
    var backend = new AeCrafting(null, null, null);
    var waiting = new ArrayList<CompletableFuture<?>>();
    try {
      for (int i = 0; i < 32; i++) {
        var future = backend.cpus("network");
        assertFalse(future.isDone());
        waiting.add(future);
      }
      var full = assertThrows(CompletionException.class, () -> backend.cpus("network").join());
      assertEquals("CRAFTING_QUEUE_BUSY", ((Failure) full.getCause()).code());
    } finally {
      backend.close();
    }
    for (var future : waiting) {
      var failure = assertThrows(CompletionException.class, future::join);
      assertEquals("CRAFTING_UNAVAILABLE", ((Failure) failure.getCause()).code());
    }
    assertEquals(
        "CRAFTING_UNAVAILABLE",
        ((Failure)
                assertThrows(CompletionException.class, () -> backend.cpus("network").join())
                    .getCause())
            .code());
  }
}
