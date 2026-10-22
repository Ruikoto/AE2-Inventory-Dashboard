package local.ae2dashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import local.ae2dashboard.model.Crafting.*;
import local.ae2dashboard.model.Network;
import local.ae2dashboard.model.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CraftingServiceTest {
  @TempDir Path directory;
  private final String network = UUID.randomUUID().toString();

  private Config config(boolean enabled) {
    return new Config(
        "127.0.0.1",
        0,
        "public",
        "secret",
        10,
        64,
        false,
        directory.resolve("absent.zip"),
        "",
        enabled);
  }

  private TestCraftingBackend backend(DashboardService service) throws Exception {
    service.write(() -> null).get(5, TimeUnit.SECONDS);
    assertTrue(service.crafting().ready());
    var backend = new TestCraftingBackend(service.crafting());
    service.crafting().setBackend(backend);
    return backend;
  }

  private Order submit(DashboardService service) throws Exception {
    var plan = service.crafting().createPlan(network, "exact", "64").get();
    return service
        .crafting()
        .submit(network, plan.id(), "", UUID.randomUUID().toString())
        .get(5, TimeUnit.SECONDS);
  }

  @Test
  void concurrentAndPersistedRetriesSubmitOnlyOnce() throws Exception {
    try (var service = new DashboardService(directory, config(true))) {
      var backend = backend(service);
      backend.submitGate = new CompletableFuture<>();
      var crafting = service.crafting();
      var plan = crafting.createPlan(network, "exact", "64").get();
      String request = UUID.randomUUID().toString();
      List<CompletableFuture<Order>> attempts = new ArrayList<>();
      for (int i = 0; i < 12; i++) attempts.add(crafting.submit(network, plan.id(), "", request));
      assertThrows(
          Failure.class, () -> crafting.submit(network, plan.id(), "another-cpu", request));
      backend.submitGate.complete(null);
      Order order = attempts.get(0).get(5, TimeUnit.SECONDS);
      for (var attempt : attempts) assertEquals(order.id(), attempt.get().id());
      assertEquals(1, backend.submits.get());
      assertEquals("running", order.state());
      // 原生后端已消费预览，但持久化请求标识仍能恢复响应。
      assertFalse(backend.plans.containsKey(plan.id()));
      assertEquals(order.id(), crafting.submit(network, plan.id(), "", request).get().id());
      assertEquals(order.id(), crafting.byRequest(network, request).orders().get(0).id());
      assertEquals(1, backend.submits.get());
      assertEquals("cancelled", crafting.cancel(network, order.id()).get().state());
      assertTrue(crafting.activeOrders().isEmpty());
      assertEquals(1, crafting.orders(network, true, 50, "").orders().size());
    }
  }

  @Test
  void pendingIntentCannotBeMistakenForUnacceptedRequest() throws Exception {
    try (var service = new DashboardService(directory, config(true))) {
      var backend = backend(service);
      var crafting = service.crafting();
      var plan = crafting.createPlan(network, "exact", "64").get();
      String request = UUID.randomUUID().toString();
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      var blocked =
          service.write(
              () -> {
                entered.countDown();
                release.await();
                return null;
              });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      CompletableFuture<Order> submission;
      try {
        submission = crafting.submit(network, plan.id(), "", request);
        assertEquals(
            "REQUEST_PENDING",
            assertThrows(Failure.class, () -> crafting.byRequest(network, request)).code());
        assertEquals(0, backend.submits.get());
      } finally {
        release.countDown();
      }
      blocked.get(5, TimeUnit.SECONDS);
      Order order = submission.get(5, TimeUnit.SECONDS);
      assertEquals(order.id(), crafting.byRequest(network, request).orders().get(0).id());
      assertEquals(1, backend.submits.get());
    }
  }

  @Test
  void failedIntentNeverCallsNativeAndSuccessfulSampleAllowsExplicitRetry() throws Exception {
    try (var service = new DashboardService(directory, config(true))) {
      var backend = backend(service);
      var crafting = service.crafting();
      var plan = crafting.createPlan(network, "exact", "64").get();
      String request = UUID.randomUUID().toString();
      service
          .write(
              () -> {
                executeSql(
                    "CREATE TRIGGER fail_intent BEFORE INSERT ON crafting_orders BEGIN SELECT"
                        + " RAISE(ABORT, 'test intent failure'); END");
                return null;
              })
          .get(5, TimeUnit.SECONDS);
      assertThrows(
          ExecutionException.class,
          () -> crafting.submit(network, plan.id(), "", request).get(5, TimeUnit.SECONDS));
      assertEquals(0, backend.submits.get());
      assertEquals("persistence-unavailable", crafting.status());
      assertTrue(crafting.byRequest(network, request).orders().isEmpty());
      assertEquals(
          "CRAFTING_STORAGE_UNAVAILABLE",
          assertThrows(Failure.class, () -> crafting.createPlan(network, "exact", "1")).code());

      service
          .write(
              () -> {
                executeSql("DROP TRIGGER fail_intent");
                return null;
              })
          .get(5, TimeUnit.SECONDS);
      long now = System.currentTimeMillis();
      service.network(new Network(network, now, now, "online", Set.of()), null, Set.of());
      service.sample(Snapshot.of(network, "test", now, 1, List.of()));
      service.write(() -> null).get(5, TimeUnit.SECONDS);
      assertEquals("ready", crafting.status());
      assertEquals(0, backend.submits.get());
      assertTrue(crafting.byRequest(network, request).orders().isEmpty());
      assertEquals(
          "running",
          crafting.submit(network, plan.id(), "", request).get(5, TimeUnit.SECONDS).state());
      assertEquals(1, backend.submits.get());
    }
  }

  private void executeSql(String sql) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("ae2-dashboard/history.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Test
  void failedNativeSubmissionRemainsInHistoryAndDoesNotRetry() throws Exception {
    try (var service = new DashboardService(directory, config(true))) {
      var backend = backend(service);
      backend.failure = "CPU_BUSY";
      Order order = submit(service);
      assertEquals("failed", order.state());
      assertEquals("CPU_BUSY", order.error());
      assertEquals(
          order.id(),
          service.crafting().submit(network, order.planId(), "", order.requestId()).get().id());
      assertEquals(1, backend.submits.get());
      assertEquals(1, service.crafting().orders(network, true, 50, "").orders().size());
      assertFalse(backend.plans.containsKey(order.planId()));
    }
  }

  @Test
  void restartKeepsNativeAssociationAndDoesNotResubmitUnknownIntent() throws Exception {
    String knownId, uncertainId;
    try (var service = new DashboardService(directory, config(true))) {
      backend(service);
      Order known = submit(service);
      knownId = known.id();
      long now = System.currentTimeMillis();
      Order uncertain =
          new Order(
              UUID.randomUUID().toString(),
              UUID.randomUUID().toString(),
              network,
              UUID.randomUUID().toString(),
              known.item(),
              "1",
              "",
              "",
              "",
              "",
              "submitting",
              now,
              now,
              0,
              "",
              "0",
              "0");
      uncertainId = uncertain.id();
      service.write(() -> service.store().insertOrderIntent(uncertain)).get();
    }
    try (var restored = new DashboardService(directory, config(false))) {
      var backend = backend(restored);
      assertEquals(0, backend.submits.get());
      assertEquals("unknown", restored.crafting().order(network, uncertainId).state());
      assertEquals(
          "SUBMISSION_OUTCOME_UNKNOWN", restored.crafting().order(network, uncertainId).error());
      assertEquals(knownId, restored.crafting().activeOrders().iterator().next().id());
      assertEquals("cancelled", restored.crafting().cancel(network, knownId).get().state());
      Failure disabled =
          assertThrows(Failure.class, () -> restored.crafting().createPlan(network, "exact", "1"));
      assertEquals("CRAFTING_DISABLED", disabled.code());
      assertEquals(2, restored.crafting().orders(network, true, 50, "").orders().size());
    }
  }

  @Test
  void exceptionAfterNativeAssociationKeepsUnknownOutcome() throws Exception {
    try (var service = new DashboardService(directory, config(true))) {
      var backend = backend(service);
      backend.submitGate =
          CompletableFuture.failedFuture(new Failure("SUBMISSION_OUTCOME_UNKNOWN"));
      Order order = submit(service);
      assertEquals("unknown", order.state());
      assertFalse(order.nativeId().isEmpty());
      assertEquals(1, backend.submits.get());
      assertEquals(
          order.id(),
          service.crafting().submit(network, order.planId(), "", order.requestId()).get().id());
      assertEquals(1, backend.submits.get());
    }
  }

  @Test
  void fullWriterQueueRetainsTerminalEventForRetry() throws Exception {
    try (var service = new DashboardService(directory, config(true))) {
      backend(service);
      Order running = submit(service);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      CompletableFuture<Void> blocked =
          service.write(
              () -> {
                entered.countDown();
                release.await();
                return null;
              });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      List<CompletableFuture<Void>> queued = new ArrayList<>();
      try {
        for (int i = 0; i < 32; i++) queued.add(service.write(() -> null));
        service.crafting().update(TestCraftingBackend.ended(running, "completed"));
        assertEquals("persistence-unavailable", service.crafting().status());
        assertEquals("completed", service.crafting().order(network, running.id()).state());
      } finally {
        release.countDown();
      }
      blocked.get(5, TimeUnit.SECONDS);
      for (var task : queued) task.get(5, TimeUnit.SECONDS);
      Thread.sleep(1050);
      service.crafting().flush();
      service.write(() -> null).get(5, TimeUnit.SECONDS);
      assertEquals("completed", service.store().findOrder(network, running.id()).state());
      assertEquals("ready", service.crafting().status());
    }
  }

  @Test
  void validatesExactIdentityAndLongQuantity() throws Exception {
    try (var service = new DashboardService(directory, config(true))) {
      backend(service);
      for (String quantity : List.of("0", "-1", "1.5", "9223372036854775808", "1e3", " 1"))
        assertEquals(
            "INVALID_QUANTITY",
            assertThrows(
                    Failure.class, () -> service.crafting().createPlan(network, "exact", quantity))
                .code());
      assertEquals(
          "INVALID_RESOURCE",
          assertThrows(
                  Failure.class,
                  () -> service.crafting().createPlan(network, "group:item:test:x", "1"))
              .code());
      assertEquals(
          "9223372036854775807",
          service.crafting().createPlan(network, "exact", "9223372036854775807").get().quantity());
    }
  }

  @Test
  void cpuStatusRemainsReadOnlyWhenNewOrdersAreDisabled() throws Exception {
    try (var service = new DashboardService(directory, config(false))) {
      backend(service);
      var crafting = service.crafting();
      CpuStatus status = crafting.cpuStatus(network, "cpu", "", 0, 120, null).get();
      assertEquals("idle", status.state());
      assertTrue(status.detailsAvailable());
      assertTrue(crafting.activeOrders().isEmpty());
      assertTrue(crafting.orders(network, true, 50, "").orders().isEmpty());
      assertEquals(
          "STATUS_CHANGED",
          ((Failure)
                  assertThrows(
                          ExecutionException.class,
                          () -> crafting.cpuStatus(network, "cpu", "", 0, 120, 2L).get())
                      .getCause())
              .code());
      assertEquals(
          "TASK_CHANGED",
          ((Failure)
                  assertThrows(
                          ExecutionException.class,
                          () ->
                              crafting
                                  .cpuStatus(
                                      network, "cpu", UUID.randomUUID().toString(), 0, 120, null)
                                  .get())
                      .getCause())
              .code());
      for (int[] page :
          List.of(
              new int[] {-1, 120}, new int[] {0, 0}, new int[] {0, 121}, new int[] {100001, 120})) {
        assertEquals(
            "INVALID_PARAMETER",
            assertThrows(
                    Failure.class,
                    () -> crafting.cpuStatus(network, "cpu", "", page[0], page[1], null))
                .code());
      }
      assertThrows(Failure.class, () -> crafting.cpuStatus(network, "", "", 0, 120, null));
      assertThrows(
          Failure.class, () -> crafting.cpuStatus(network, "cpu", "not-a-uuid", 0, 120, null));
    }
  }

  @Test
  void pendingSubmissionCapacityIsDistinctFromNativeQueue() throws Exception {
    try (var service = new DashboardService(directory, config(true))) {
      var backend = backend(service);
      backend.submitGate = new CompletableFuture<>();
      var crafting = service.crafting();
      var plans = new ArrayList<Plan>();
      for (int i = 0; i < 32; i++) plans.add(crafting.createPlan(network, "exact", "64").get());
      List<CompletableFuture<Order>> pending = new ArrayList<>();
      try {
        for (int i = 0; i < 32; i++) {
          pending.add(
              crafting.submit(network, plans.get(i).id(), "", UUID.randomUUID().toString()));
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (backend.submits.get() <= i && System.nanoTime() < deadline) Thread.sleep(1);
          assertEquals(i + 1, backend.submits.get());
          service.write(() -> null).get(5, TimeUnit.SECONDS);
        }
        assertEquals(
            "SUBMISSION_LIMIT",
            assertThrows(
                    Failure.class,
                    () ->
                        crafting.submit(
                            network, plans.get(0).id(), "", UUID.randomUUID().toString()))
                .code());
        assertEquals(32, backend.submits.get());
      } finally {
        backend.submitGate.complete(null);
      }
      for (var attempt : pending) assertEquals("running", attempt.get(5, TimeUnit.SECONDS).state());
    }
  }
}
