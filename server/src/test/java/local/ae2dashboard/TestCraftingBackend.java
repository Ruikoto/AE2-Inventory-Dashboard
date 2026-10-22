package local.ae2dashboard;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.ae2dashboard.model.Crafting.*;

/** HTTP 与持久化测试共用的脱离游戏对象合成后端。 */
public final class TestCraftingBackend implements CraftingService.Backend {
  private final CraftingService service;
  public final Map<String, Plan> plans = new ConcurrentHashMap<>();
  public final AtomicInteger submits = new AtomicInteger(), cancels = new AtomicInteger();
  public CompletableFuture<Void> submitGate = CompletableFuture.completedFuture(null);
  public String failure = "";
  public String planFailure = "";

  public TestCraftingBackend(CraftingService service) {
    this.service = service;
  }

  public CompletableFuture<List<Cpu>> cpus(String network) {
    return CompletableFuture.completedFuture(
        List.of(
            new Cpu(
                "cpu", "测试 CPU", "65536", 1, false, true, true, "", null, "0", "0", "0", "0", "",
                false)));
  }

  public CompletableFuture<CpuStatus> cpuStatus(
      String network, String cpuId, String taskId, int offset, int limit, Long version) {
    if (version != null && version != 1)
      return CompletableFuture.failedFuture(new Failure("STATUS_CHANGED"));
    if (!taskId.isEmpty()) return CompletableFuture.failedFuture(new Failure("TASK_CHANGED"));
    return CompletableFuture.completedFuture(
        new CpuStatus(
            cpuId,
            "",
            1,
            System.currentTimeMillis(),
            "idle",
            false,
            "0",
            "0",
            "0",
            offset,
            0,
            List.of(),
            true,
            ""));
  }

  public CompletableFuture<Plan> createPlan(String network, String resource, String quantity) {
    if (!planFailure.isEmpty()) return CompletableFuture.failedFuture(new Failure(planFailure));
    Plan plan =
        new Plan(
            UUID.randomUUID().toString(),
            network,
            new Item(resource, "测试物品", "个", "item", "test:item"),
            quantity,
            "ready",
            "512",
            List.of(),
            "",
            System.currentTimeMillis());
    plans.put(plan.id(), plan);
    return CompletableFuture.completedFuture(plan);
  }

  public CompletableFuture<Plan> plan(String network, String id) {
    Plan plan = plans.get(id);
    return plan == null || !plan.networkId().equals(network)
        ? CompletableFuture.failedFuture(new Failure("PLAN_NOT_FOUND"))
        : CompletableFuture.completedFuture(plan);
  }

  public CompletableFuture<Void> cancelPlan(String network, String id) {
    plans.remove(id);
    return CompletableFuture.completedFuture(null);
  }

  public CompletableFuture<Void> submit(Order order) {
    submits.incrementAndGet();
    plans.remove(order.planId());
    if (!failure.isEmpty()) return CompletableFuture.failedFuture(new Failure(failure));
    service.update(
        new Order(
            order.id(),
            order.requestId(),
            order.networkId(),
            order.planId(),
            order.item(),
            order.quantity(),
            order.requestedCpuId(),
            "cpu",
            "测试 CPU",
            UUID.randomUUID().toString(),
            "running",
            order.createdAt(),
            System.currentTimeMillis(),
            0,
            "",
            "128",
            "0"));
    return submitGate;
  }

  public CompletableFuture<Void> cancel(Order order) {
    cancels.incrementAndGet();
    service.update(ended(order, "cancelled"));
    return CompletableFuture.completedFuture(null);
  }

  public static Order ended(Order order, String state) {
    long now = Math.max(order.updatedAt(), System.currentTimeMillis());
    return new Order(
        order.id(),
        order.requestId(),
        order.networkId(),
        order.planId(),
        order.item(),
        order.quantity(),
        order.requestedCpuId(),
        order.cpuId(),
        order.cpuName(),
        order.nativeId(),
        state,
        order.createdAt(),
        now,
        now,
        "",
        order.total(),
        order.progress());
  }

  public void close() {}
}
