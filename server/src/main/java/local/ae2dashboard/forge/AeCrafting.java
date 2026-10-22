package local.ae2dashboard.forge;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.mojang.authlib.GameProfile;
import java.util.*;
import java.util.concurrent.*;
import local.ae2dashboard.CraftingService;
import local.ae2dashboard.DashboardService;
import local.ae2dashboard.model.Crafting.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 游戏线程拥有原生计划、CPU 和 link；HTTP 只能提交有界命令及读取脱离对象的数据。 */
public final class AeCrafting implements CraftingService.Backend {
  private static final Logger LOG = LoggerFactory.getLogger("ae2dashboard");
  private static final GameProfile PROFILE =
      new GameProfile(UUID.fromString("e77aabf4-d670-451f-9514-a01ee639b861"), "[AE2Dashboard]");
  private static AeCrafting active;
  private final DashboardService service;
  private final AeSampler sampler;
  private final MinecraftServer server;
  private final ArrayBlockingQueue<Command<?>> commands = new ArrayBlockingQueue<>(32);
  private final Map<String, Entry> plans = new HashMap<>();
  private final LinkedHashMap<String, TerminalPlan> terminalPlans = new LinkedHashMap<>();
  private final Map<String, Long> capacityWarnings = new HashMap<>();
  private final LinkedHashMap<StatusKey, StatusSnapshot> statuses =
      new LinkedHashMap<>(16, 0.75f, true);
  private final Map<String, Order> tracked = new HashMap<>();
  private Submission submission;
  private long lastTracking;
  private long statusVersion, statusBytes;
  private volatile boolean closed;

  private record Command<T>(Callable<T> action, CompletableFuture<T> result) {
    void run() {
      if (result.isCancelled()) return;
      try {
        result.complete(action.call());
      } catch (Exception e) {
        if (!(e instanceof Failure)) LOG.error("Dashboard crafting command failed", e);
        result.completeExceptionally(e);
      }
    }
  }

  private static final class Entry {
    final AeSampler.Access access;
    final IActionSource source;
    final Future<ICraftingPlan> calculation;
    ICraftingPlan plan;
    Plan view;
    long expiresAt;
    long leaseUntil;

    Entry(
        AeSampler.Access access,
        IActionSource source,
        Future<ICraftingPlan> calculation,
        Plan view) {
      this.access = access;
      this.source = source;
      this.calculation = calculation;
      this.view = view;
      leaseUntil = view.createdAt() + 30_000;
    }
  }

  private record TerminalPlan(Plan view, long expiresAt) {}

  private record StatusKey(String network, IGrid grid, long revision, String cpu, String task) {}

  private static final class StatusSnapshot {
    final CpuStatus view;
    final long bytes;
    long touched;

    StatusSnapshot(CpuStatus view, long bytes, long now) {
      this.view = view;
      this.bytes = bytes;
      touched = now;
    }
  }

  private static final class Submission {
    final Order order;
    final IGrid grid;
    final ICraftingPlan plan;
    boolean captured;

    Submission(Order order, IGrid grid, ICraftingPlan plan) {
      this.order = order;
      this.grid = grid;
      this.plan = plan;
    }
  }

  public AeCrafting(DashboardService service, AeSampler sampler, MinecraftServer server) {
    this.service = service;
    this.sampler = sampler;
    this.server = server;
    active = this;
  }

  private synchronized <T> CompletableFuture<T> enqueue(Callable<T> action) {
    var result = new CompletableFuture<T>();
    if (closed) result.completeExceptionally(new Failure("CRAFTING_UNAVAILABLE"));
    else if (!commands.offer(new Command<>(action, result)))
      result.completeExceptionally(capacity("CRAFTING_QUEUE_BUSY"));
    return result;
  }

  private synchronized Failure capacity(String code) {
    long now = System.currentTimeMillis();
    if (now - capacityWarnings.getOrDefault(code, 0L) >= 30_000) {
      capacityWarnings.put(code, now);
      LOG.warn("Dashboard crafting capacity reached: {}, queued={}", code, commands.size());
    }
    return new Failure(code);
  }

  public void tick() {
    if (closed) return;
    for (int i = 0; i < 4; i++) {
      var command = commands.poll();
      if (command == null) break;
      command.run();
    }
    long now = System.currentTimeMillis();
    prunePlans(now);
    pruneStatuses(now);
    if (service.crafting().ready() && now - lastTracking >= 1000) {
      refreshOrders();
      lastTracking = now;
    }
    service.crafting().flush();
  }

  @Override
  public CompletableFuture<List<Cpu>> cpus(String network) {
    return enqueue(
        () -> {
          var access = sampler.access(network);
          var source =
              IActionSource.ofPlayer(FakePlayerFactory.get(access.level(), PROFILE), access.host());
          var orders = new HashMap<String, Order>();
          for (Order order : service.crafting().activeOrders()) {
            if (!order.nativeId().isEmpty()) orders.put(order.nativeId(), order);
          }
          return access.grid().getCraftingService().getCpus().stream()
              .filter(CraftingCPUCluster.class::isInstance)
              .map(CraftingCPUCluster.class::cast)
              .map(
                  cpu -> {
                    String task = taskId(cpu);
                    Order order = orders.get(task);
                    var job = cpu.getJobStatus();
                    boolean selectable = cpu.isActive() && !cpu.isDestroyed() && !cpu.isBusy();
                    return new Cpu(
                        cpuId(cpu),
                        (cpu.getName() == null ? "" : cpu.getName().getString()),
                        Long.toString(cpu.getAvailableStorage()),
                        cpu.getCoProcessors(),
                        cpu.isBusy(),
                        selectable,
                        selectable && cpu.canBeAutoSelectedFor(source),
                        task,
                        job == null ? null : item(job.crafting().what()),
                        job == null ? "0" : Long.toString(job.crafting().amount()),
                        job == null ? "0" : Long.toString(job.totalItems()),
                        job == null ? "0" : Long.toString(job.progress()),
                        job == null ? "0" : Long.toString(job.elapsedTimeNanos()),
                        order == null ? "" : order.id(),
                        order != null && order.networkId().equals(network) && !order.terminal());
                  })
              .sorted(Comparator.comparing(Cpu::name).thenComparing(Cpu::id))
              .toList();
        });
  }

  @Override
  public CompletableFuture<CpuStatus> cpuStatus(
      String network, String id, String task, int offset, int limit, Long version) {
    return enqueue(
        () -> {
          var access = sampler.access(network);
          var cpu =
              access.grid().getCraftingService().getCpus().stream()
                  .filter(CraftingCPUCluster.class::isInstance)
                  .map(CraftingCPUCluster.class::cast)
                  .filter(value -> !value.isDestroyed() && cpuId(value).equals(id))
                  .findFirst()
                  .orElseThrow(() -> new Failure("CPU_OFFLINE"));
          if (!taskId(cpu).equals(task)) throw new Failure("TASK_CHANGED");
          long now = System.currentTimeMillis();
          pruneStatuses(now);
          var key = new StatusKey(network, access.grid(), access.revision(), id, task);
          var cached = statuses.get(key);
          if (cached == null || now - cached.view.sampledAt() >= 2000) {
            cached = snapshot(cpu, task, now);
            var previous = statuses.put(key, cached);
            if (previous != null) statusBytes -= previous.bytes;
            statusBytes += cached.bytes;
            while (statuses.size() > 16 || statusBytes > 32L * 1024 * 1024) {
              var oldest = statuses.entrySet().iterator();
              statusBytes -= oldest.next().getValue().bytes;
              oldest.remove();
            }
          }
          cached.touched = now;
          CpuStatus view = cached.view;
          if (version != null && version != view.version()) throw new Failure("STATUS_CHANGED");
          int from = Math.min(offset, view.entries().size());
          int to = Math.min(from + limit, view.entries().size());
          return new CpuStatus(
              view.cpuId(),
              view.taskId(),
              view.version(),
              view.sampledAt(),
              view.state(),
              view.cantStoreItems(),
              view.totalWork(),
              view.completedWork(),
              view.elapsedNanos(),
              offset,
              view.total(),
              view.entries().subList(from, to),
              view.detailsAvailable(),
              view.error());
        });
  }

  private StatusSnapshot snapshot(CraftingCPUCluster cpu, String task, long now) {
    var logic = cpu.craftingLogic;
    var all = new KeyCounter();
    logic.getAllItems(all);
    var entries = new ArrayList<CpuMaterial>();
    long bytes = 512;
    boolean available = all.size() <= 100000;
    if (available) {
      for (var value : all) {
        AEKey key = value.getKey();
        long stored = logic.getStored(key), waiting = logic.getWaitingFor(key);
        // getAllItems 的总量为存储、执行中与待执行之和，避免逐项扫描所有配方。
        long pending = value.getLongValue() - stored - waiting;
        if (stored == 0 && waiting == 0 && pending == 0) continue;
        Item item = item(key);
        bytes += itemBytes(item);
        if (bytes > 8L * 1024 * 1024) {
          available = false;
          break;
        }
        entries.add(
            new CpuMaterial(
                item, Long.toString(stored), Long.toString(waiting), Long.toString(pending)));
      }
    }
    if (!available) {
      entries.clear();
      bytes = 512;
    } else
      entries.sort(
          Comparator.comparing((CpuMaterial value) -> value.item().name())
              .thenComparing(value -> value.item().id()));
    var job = cpu.getJobStatus();
    var view =
        new CpuStatus(
            cpuId(cpu),
            task,
            ++statusVersion,
            now,
            !cpu.isActive() ? "unavailable" : job == null ? "idle" : "running",
            logic.isCantStoreItems(),
            job == null ? "0" : Long.toString(job.totalItems()),
            job == null ? "0" : Long.toString(job.progress()),
            job == null ? "0" : Long.toString(job.elapsedTimeNanos()),
            0,
            available ? entries.size() : all.size(),
            entries,
            available,
            available ? "" : "STATUS_TOO_LARGE");
    return new StatusSnapshot(view, bytes, now);
  }

  private void pruneStatuses(long now) {
    for (var iterator = statuses.values().iterator(); iterator.hasNext(); ) {
      var value = iterator.next();
      if (now - value.touched >= 10000) {
        statusBytes -= value.bytes;
        iterator.remove();
      }
    }
  }

  private static String taskId(CraftingCPUCluster cpu) {
    var link = cpu.craftingLogic.getLastLink();
    return link == null ? "" : link.getCraftingID().toString();
  }

  private static long itemBytes(Item item) {
    return 256L
        + 2L
            * (item.id().length()
                + item.name().length()
                + item.registryId().length()
                + (item.baseDisplayId() == null ? 0 : item.baseDisplayId().length()));
  }

  @Override
  public CompletableFuture<Plan> createPlan(String network, String resourceId, String quantity) {
    return enqueue(
        () -> {
          prunePlans(System.currentTimeMillis());
          if (plans.values().stream().filter(e -> e.view.networkId().equals(network)).count() >= 3)
            throw capacity("NETWORK_PREVIEW_LIMIT");
          if (plans.size() >= 32) throw capacity("GLOBAL_PREVIEW_LIMIT");
          long amount;
          try {
            if (!quantity.matches("[1-9][0-9]*")) throw new NumberFormatException();
            amount = Long.parseLong(quantity);
          } catch (NumberFormatException e) {
            throw new Failure("INVALID_QUANTITY");
          }
          var access = sampler.access(network);
          AEKey key = sampler.resolve(network, resourceId);
          var crafting = access.grid().getCraftingService();
          if (!crafting.isCraftable(key) && !crafting.canEmitFor(key))
            throw new Failure("NOT_CRAFTABLE");
          var source =
              IActionSource.ofPlayer(FakePlayerFactory.get(access.level(), PROFILE), access.host());
          var requester =
              new ICraftingSimulationRequester() {
                @Override
                public IActionSource getActionSource() {
                  return source;
                }

                @Override
                public appeng.api.networking.IGridNode getGridNode() {
                  return access.host().getActionableNode();
                }
              };
          var calculation =
              crafting.beginCraftingCalculation(
                  access.level(), requester, key, amount, CalculationStrategy.REPORT_MISSING_ITEMS);
          var view =
              new Plan(
                  UUID.randomUUID().toString(),
                  network,
                  item(key),
                  quantity,
                  "calculating",
                  "0",
                  List.of(),
                  "",
                  System.currentTimeMillis());
          plans.put(view.id(), new Entry(access, source, calculation, view));
          return view;
        });
  }

  @Override
  public CompletableFuture<Plan> plan(String network, String planId) {
    return enqueue(
        () -> {
          long now = System.currentTimeMillis();
          prunePlans(now);
          var entry = plans.get(planId);
          if (entry != null && entry.view.networkId().equals(network)) {
            entry.leaseUntil = now + 30_000;
            return entry.view;
          }
          var terminal = terminalPlans.get(planId);
          if (terminal != null && terminal.view().networkId().equals(network))
            return terminal.view();
          throw new Failure("PLAN_NOT_FOUND");
        });
  }

  @Override
  public CompletableFuture<Void> cancelPlan(String network, String planId) {
    return enqueue(
        () -> {
          var entry = plans.get(planId);
          if (entry != null && !entry.view.networkId().equals(network))
            throw new Failure("PLAN_NOT_FOUND");
          if (entry != null) {
            retire(entry, "cancelled", "", System.currentTimeMillis());
          }
          var terminal = terminalPlans.get(planId);
          if (terminal != null && !terminal.view().networkId().equals(network))
            throw new Failure("PLAN_NOT_FOUND");
          return null;
        });
  }

  private Entry entry(String network, String planId) {
    var entry = plans.get(planId);
    if (entry == null || !entry.view.networkId().equals(network))
      throw new Failure("PLAN_NOT_FOUND");
    return entry;
  }

  private void prunePlans(long now) {
    terminalPlans.values().removeIf(value -> now >= value.expiresAt());
    // refresh 会把终态移入独立缓存，遍历副本避免修改当前迭代器。
    for (Entry entry : List.copyOf(plans.values())) refresh(entry, now);
  }

  private void retire(Entry entry, String state, String error, long now) {
    entry.calculation.cancel(true);
    entry.plan = null;
    var before = entry.view;
    entry.view =
        new Plan(
            before.id(),
            before.networkId(),
            before.item(),
            before.quantity(),
            state,
            "0",
            List.of(),
            error,
            before.createdAt());
    plans.remove(before.id());
    terminalPlans.put(before.id(), new TerminalPlan(entry.view, now + 60_000));
    while (terminalPlans.size() > 32)
      terminalPlans.remove(terminalPlans.keySet().iterator().next());
  }

  private void refresh(Entry entry, long now) {
    if (!Set.of("calculating", "ready", "missing").contains(entry.view.state())) return;
    if (now >= entry.leaseUntil || (entry.expiresAt > 0 && now >= entry.expiresAt)) {
      retire(entry, "expired", "PLAN_EXPIRED", now);
      return;
    }
    try {
      var current = sampler.access(entry.view.networkId());
      if (current.grid() != entry.access.grid() || current.revision() != entry.access.revision())
        throw new Failure("NETWORK_CHANGED");
      if (!entry.view.state().equals("calculating")) return;
      if (now - entry.view.createdAt() >= 60_000) throw new Failure("CALCULATION_TIMEOUT");
      if (!entry.calculation.isDone()) return;
      entry.plan = entry.calculation.get();
      var summary = CraftingPlanSummary.fromJob(entry.access.grid(), entry.source, entry.plan);
      if (summary.getEntries().size() > 100000) throw new Failure("PLAN_TOO_LARGE");
      var materials = new ArrayList<Material>();
      long bytes = 0;
      for (var value : summary.getEntries()) {
        Item item = item(value.getWhat());
        bytes += itemBytes(item);
        if (bytes > 8L * 1024 * 1024) throw new Failure("PLAN_TOO_LARGE");
        materials.add(
            new Material(
                item,
                Long.toString(value.getStoredAmount()),
                Long.toString(value.getCraftAmount()),
                Long.toString(value.getMissingAmount())));
      }
      entry.view =
          new Plan(
              entry.view.id(),
              entry.view.networkId(),
              entry.view.item(),
              entry.view.quantity(),
              summary.isSimulation() ? "missing" : "ready",
              Long.toString(summary.getUsedBytes()),
              materials,
              "",
              entry.view.createdAt());
      entry.expiresAt = now + 15 * 60_000;
    } catch (Exception e) {
      String error = e instanceof Failure failure ? failure.code() : "CALCULATION_FAILED";
      if (!(e instanceof Failure)) LOG.error("Dashboard crafting calculation failed", e);
      retire(entry, "failed", error, now);
    }
  }

  @Override
  public CompletableFuture<Void> submit(Order order) {
    return enqueue(
        () -> {
          Entry entry = entry(order.networkId(), order.planId());
          try {
            refresh(entry, System.currentTimeMillis());
            if (!entry.view.state().equals("ready"))
              throw new Failure(
                  entry.view.state().equals("missing")
                      ? "MISSING_INGREDIENT"
                      : entry.view.error().isEmpty() ? "PLAN_NOT_READY" : entry.view.error());
            CraftingCPUCluster selected = null;
            if (!order.requestedCpuId().isEmpty()) {
              selected =
                  entry.access.grid().getCraftingService().getCpus().stream()
                      .filter(CraftingCPUCluster.class::isInstance)
                      .map(CraftingCPUCluster.class::cast)
                      .filter(cpu -> cpuId(cpu).equals(order.requestedCpuId()))
                      .findFirst()
                      .orElseThrow(() -> new Failure("CPU_OFFLINE"));
              if (!selected.isActive() || selected.isDestroyed()) throw new Failure("CPU_OFFLINE");
              if (selected.isBusy()) throw new Failure("CPU_BUSY");
              if (selected.getAvailableStorage() < entry.plan.bytes())
                throw new Failure("CPU_TOO_SMALL");
            }
            submission = new Submission(order, entry.access.grid(), entry.plan);
            ICraftingSubmitResult result;
            try {
              result =
                  entry
                      .access
                      .grid()
                      .getCraftingService()
                      .submitJob(entry.plan, null, selected, true, entry.source);
            } catch (RuntimeException e) {
              LOG.error("Native crafting submission outcome is unknown; it will not be retried", e);
              throw new Failure("SUBMISSION_OUTCOME_UNKNOWN");
            }
            if (!result.successful()) throw new Failure(result.errorCode().name());
            // 独立任务的 result.link() 为空；只能使用成功提交钩子捕获的 UUID。
            if (!submission.captured) {
              publish(order, "unknown", "TRACKING_UNAVAILABLE", null, "");
              LOG.error("Dashboard submission succeeded without a lifecycle association");
            }
          } finally {
            // 已持久化的订单意图消费此计划，CPU 拒绝和未知结果同样不能占用预览名额。
            plans.remove(order.planId());
            terminalPlans.remove(order.planId());
            entry.calculation.cancel(true);
            entry.plan = null;
            submission = null;
          }
          return null;
        });
  }

  @Override
  public CompletableFuture<Void> cancel(Order order) {
    return enqueue(
        () -> {
          sampler.access(order.networkId());
          CraftingCPUCluster cpu = findByNativeId(order.nativeId());
          if (cpu == null) throw new Failure("ORDER_UNAVAILABLE");
          if (!order.networkId().equals(sampler.networkId(cpu.getGrid())))
            throw new Failure("NETWORK_CHANGED");
          // 先确认确切任务，不能取消复用了原 CPU 的其他合成。
          tracked.put(order.nativeId(), order);
          cpu.cancelJob();
          return null;
        });
  }

  /** 由成功提交的 Mixin 调用；其他终端没有 submission 上下文。 */
  public static void submitted(IGrid grid, ICraftingPlan plan, CraftingCPUCluster cpu) {
    var manager = active;
    if (manager == null || manager.submission == null) return;
    var context = manager.submission;
    if (context.grid != grid || context.plan != plan) return;
    var link = cpu.craftingLogic.getLastLink();
    if (link == null) return;
    context.captured = true;
    manager.publish(context.order, "running", "", cpu, link.getCraftingID().toString());
  }

  /** 正常退出 finishJob 后才发布终态，不用 link 消失或 isDone 猜测结果。 */
  public static void finished(UUID nativeId, boolean success) {
    var manager = active;
    if (manager == null || nativeId == null) return;
    var order = manager.tracked.get(nativeId.toString());
    if (order != null
        && manager.service.crafting().activeOrders().stream()
            .anyMatch(
                current ->
                    current.id().equals(order.id()) && current.nativeId().equals(order.nativeId())))
      manager.publish(order, success ? "completed" : "cancelled", "", null, order.nativeId());
  }

  private void refreshOrders() {
    var activeOrders = service.crafting().activeOrders();
    var activeIds = new HashSet<String>();
    for (Order order : activeOrders) {
      activeIds.add(order.id());
      if (!order.nativeId().isEmpty()) tracked.putIfAbsent(order.nativeId(), order);
    }
    tracked.values().removeIf(order -> !activeIds.contains(order.id()));
    var loaded = loadedCpus();
    for (Order order : List.copyOf(tracked.values())) {
      var cpu = loaded.get(order.nativeId());
      if (cpu != null) {
        String state = cpu.isActive() ? "running" : "recovering";
        var status = cpu.getJobStatus();
        if (!order.state().equals(state)
            || !order.cpuId().equals(cpuId(cpu))
            || (status != null
                && (!order.progress().equals(Long.toString(status.progress()))
                    || !order.total().equals(Long.toString(status.totalItems())))))
          publish(order, state, "", cpu, order.nativeId());
      } else if (cpuDefinitelyReplaced(order.cpuId())) {
        publish(order, "unknown", "NATIVE_JOB_NOT_FOUND", null, order.nativeId());
      } else if (!order.state().equals("recovering")) {
        publish(order, "recovering", "", null, order.nativeId());
      }
    }
  }

  private CraftingCPUCluster findByNativeId(String nativeId) {
    if (nativeId.isEmpty()) return null;
    return loadedCpus().get(nativeId);
  }

  private Map<String, CraftingCPUCluster> loadedCpus() {
    var result = new HashMap<String, CraftingCPUCluster>();
    for (var monitor : MonitoringGridService.active()) {
      if (monitor.grid.isEmpty()) continue;
      for (var candidate : monitor.grid.getCraftingService().getCpus()) {
        if (!(candidate instanceof CraftingCPUCluster cpu) || cpu.isDestroyed()) continue;
        var link = cpu.craftingLogic.getLastLink();
        if (link != null) result.put(link.getCraftingID().toString(), cpu);
      }
    }
    return result;
  }

  private boolean cpuDefinitelyReplaced(String id) {
    if (id.isEmpty()) return false;
    String[] parts = id.split("\\|", -1);
    if (parts.length != 3)
      throw new IllegalStateException("Invalid persisted crafting CPU address");
    var level =
        server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(parts[0])));
    if (level == null) return false;
    var min = BlockPos.of(Long.parseLong(parts[1]));
    var max = BlockPos.of(Long.parseLong(parts[2]));
    if (!level.hasChunkAt(min) || !level.hasChunkAt(max)) return false;
    var block = level.getBlockEntity(min);
    if (!(block instanceof CraftingBlockEntity craftingBlock)) return true;
    var cpu = craftingBlock.getCluster();
    // 尚未形成或未上电时不能区分恢复中与丢失；等网络再次可用后判断。
    return cpu != null && cpu.isActive();
  }

  private void publish(
      Order order, String state, String error, CraftingCPUCluster cpu, String nativeId) {
    long now = System.currentTimeMillis();
    var job = cpu == null ? null : cpu.getJobStatus();
    String total = job == null ? order.total() : Long.toString(job.totalItems());
    String progress = job == null ? order.progress() : Long.toString(job.progress());
    if (state.equals("completed")) progress = total;
    boolean terminal = Set.of("completed", "cancelled", "failed", "unknown").contains(state);
    var next =
        new Order(
            order.id(),
            order.requestId(),
            order.networkId(),
            order.planId(),
            order.item(),
            order.quantity(),
            order.requestedCpuId(),
            cpu == null ? order.cpuId() : cpuId(cpu),
            cpu == null
                ? order.cpuName()
                : (cpu.getName() == null ? "" : cpu.getName().getString()),
            nativeId,
            state,
            order.createdAt(),
            now,
            terminal ? now : 0,
            error,
            total,
            progress);
    if (!nativeId.isEmpty()) {
      if (terminal) tracked.remove(nativeId);
      else tracked.put(nativeId, next);
    }
    service.crafting().update(next);
  }

  private Item item(AEKey key) {
    return service.presentation().item(sampler.resource(key));
  }

  private static String cpuId(CraftingCPUCluster cpu) {
    return cpu.getLevel().dimension().location()
        + "|"
        + cpu.getBoundsMin().asLong()
        + "|"
        + cpu.getBoundsMax().asLong();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (active == this) active = null;
    for (var entry : plans.values()) entry.calculation.cancel(true);
    plans.clear();
    terminalPlans.clear();
    statuses.clear();
    statusBytes = 0;
    tracked.clear();
    Command<?> command;
    while ((command = commands.poll()) != null)
      command.result().completeExceptionally(new Failure("CRAFTING_UNAVAILABLE"));
  }
}
