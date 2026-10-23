package local.ae2dashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import local.ae2dashboard.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardSamplingTest {
  @TempDir Path directory;

  private DashboardService service() throws Exception {
    return new DashboardService(
        directory,
        new Config(
            "127.0.0.1",
            0,
            "public",
            "secret",
            10,
            64,
            false,
            directory.resolve("absent.zip"),
            ""));
  }

  private static Snapshot sample(String network, long timestamp, String identity) {
    Resource resource =
        new Resource("exact", "group:item:test:x", "item", "test:x", "测试", "个", 1, identity);
    return Snapshot.of(
        network, "s", timestamp, timestamp, List.of(new Resource.Stored(resource, BigInteger.TEN)));
  }

  private static long pendingBytes(DashboardService service) throws Exception {
    var field = DashboardService.class.getDeclaredField("pendingSampleBytes");
    field.setAccessible(true);
    return ((AtomicLong) field.get(service)).get();
  }

  private static CompletableFuture<Void> block(
      DashboardService service, CountDownLatch entered, CountDownLatch release) throws Exception {
    CompletableFuture<Void> result =
        service.write(
            () -> {
              entered.countDown();
              release.await();
              return null;
            });
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    return result;
  }

  private static void drain(DashboardService service) throws Exception {
    service.write(() -> null).get(10, TimeUnit.SECONDS);
  }

  @Test
  void fiveSimultaneousSmallNetworksPersistAfterWriterDelay() throws Exception {
    long now = System.currentTimeMillis();
    try (var service = service()) {
      var samples = new ArrayList<Snapshot>();
      for (int i = 0; i < 5; i++) {
        String network = "network-" + i;
        service.network(new Network(network, now, now, "online", Set.of()), "discovered", Set.of());
        samples.add(sample(network, now, "{id:test:x}"));
      }
      var release = new CountDownLatch(1);
      var blocked = block(service, new CountDownLatch(1), release);
      try {
        samples.forEach(service::sample);
        assertEquals(
            samples.stream().mapToLong(Snapshot::estimatedBytes).sum(), pendingBytes(service));
        assertEquals(0L, service.status().get("droppedSamples"));
      } finally {
        release.countDown();
      }
      blocked.get(5, TimeUnit.SECONDS);
      drain(service);
      assertEquals(0, pendingBytes(service));
      for (Snapshot snapshot : samples) {
        assertSame(snapshot, service.latest(snapshot.networkId()));
        assertEquals(now, service.store().lastObservation(snapshot.networkId()).timestamp());
        assertFalse(service.needsSegment(snapshot.networkId()));
      }
    }
  }

  @Test
  void byteBudgetDropsOnlyOverflowNetworkAndFailuresReleaseReservations() throws Exception {
    // 共享合成字符串避免测试本身占用同等堆；预算仍按每份完整快照计算。
    var identities = new HashMap<Integer, String>();
    var values = new HashMap<String, Resource.Stored>();
    for (int i = 0; i < 16; i++) {
      var metadata = new Resource("r" + i, "g", "item", "test:x", "测试", "个", 1, "");
      int identityLength = (int) ((2L * 1024 * 1024 - metadata.estimatedBytes()) / 2);
      String identity = identities.computeIfAbsent(identityLength, length -> "x".repeat(length));
      Resource resource = new Resource("r" + i, "g", "item", "test:x", "测试", "个", 1, identity);
      values.put(resource.id(), new Resource.Stored(resource, BigInteger.ONE));
    }
    long now = System.currentTimeMillis();
    var first = new Snapshot("first", "s", now, 1, values);
    var second = new Snapshot("second", "s", now, 1, values);
    var overflow = new Snapshot("overflow", "s", now, 1, values);
    assertEquals(64L * 1024 * 1024, first.estimatedBytes() + second.estimatedBytes());
    try (var service = service()) {
      var release = new CountDownLatch(1);
      block(service, new CountDownLatch(1), release);
      try {
        service.sample(first);
        service.sample(second);
        service.sample(overflow);
        assertEquals(first.estimatedBytes() + second.estimatedBytes(), pendingBytes(service));
        assertEquals(1L, service.status().get("droppedSamples"));
        assertFalse(service.needsSegment("first"));
        assertFalse(service.needsSegment("second"));
        assertTrue(service.needsSegment("overflow"));
        assertSame(overflow, service.latest("overflow"));
      } finally {
        release.countDown();
      }
      // 合成大样本超过本测试数据库的单批写入预算，异常路径也必须释放全部预约。
      drain(service);
      assertEquals(0, pendingBytes(service));
      assertTrue(service.needsSegment("first"));
      assertTrue(service.needsSegment("second"));
      assertEquals("paused:batch", service.status().get("historyStatus"));
      service.sample(sample("recovery", now + 10_000, "{id:test:x}"));
      drain(service);
      assertEquals(0, pendingBytes(service));
      assertEquals(now + 10_000, service.store().lastObservation("recovery").timestamp());
    }
  }

  @Test
  void fullTaskQueueReleasesRejectedSampleReservation() throws Exception {
    try (var service = service()) {
      var release = new CountDownLatch(1);
      block(service, new CountDownLatch(1), release);
      var writes = new ArrayList<CompletableFuture<?>>();
      try {
        for (int i = 0; i < 32; i++) writes.add(service.write(() -> null));
        service.sample(sample("rejected", System.currentTimeMillis(), "{id:test:x}"));
        assertEquals(0, pendingBytes(service));
        assertEquals(1L, service.status().get("droppedSamples"));
        assertTrue(service.needsSegment("rejected"));
        assertFalse(service.needsSegment("other"));
      } finally {
        release.countDown();
      }
      CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
      service.sample(sample("accepted", System.currentTimeMillis(), "{id:test:x}"));
      drain(service);
      assertEquals(0, pendingBytes(service));
      assertTrue(service.store().lastObservation("accepted").timestamp() > 0);
    }
  }

  @Test
  void interruptedShutdownReleasesDiscardedSamplesAndPreservesCallerInterrupt() throws Exception {
    var service = service();
    var release = new CountDownLatch(1);
    var blocked = block(service, new CountDownLatch(1), release);
    try {
      service.sample(sample("discarded", System.currentTimeMillis(), "{id:test:x}"));
      assertTrue(pendingBytes(service) > 0);
      Thread.currentThread().interrupt();
      service.close();
      assertTrue(Thread.interrupted());
      assertThrows(ExecutionException.class, () -> blocked.get(5, TimeUnit.SECONDS));
      assertEquals(0, pendingBytes(service));
      assertEquals(1L, service.status().get("droppedSamples"));
      assertTrue(service.needsSegment("discarded"));
      service.sample(sample("after-close", System.currentTimeMillis(), "{id:test:x}"));
      assertEquals(0, pendingBytes(service));
      assertEquals(2L, service.status().get("droppedSamples"));
    } finally {
      Thread.interrupted();
      release.countDown();
      service.close();
    }
  }
}
