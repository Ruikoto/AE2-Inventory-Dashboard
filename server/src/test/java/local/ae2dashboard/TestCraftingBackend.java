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

  public TestCraftingBackend(CraftingService service) {
    this.service = service;
  }

  public CompletableFuture<List<Cpu>> cpus(String network) {
    return CompletableFuture.completedFuture(
        List.of(new Cpu("cpu", "测试 CPU", "65536", 1, false, true)));
  }

  public CompletableFuture<Plan> createPlan(String network, String resource, String quantity) {
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
    plans.remove(order.planId());
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
