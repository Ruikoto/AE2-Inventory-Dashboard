package local.ae2dashboard;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import local.ae2dashboard.model.Crafting.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 网页订单协调：先保存提交意图，再调用游戏线程；所有数据库写入复用服务写线程。 */
public final class CraftingService implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("ae2dashboard");
  private static final int MAX_ACTIVE = 1024;
  private final DashboardService service;
  private final Config config;
  private final ConcurrentHashMap<String, Order> active = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Order> dirty = new ConcurrentHashMap<>();
  private final Map<String, Submission> submissions = new HashMap<>();
  private final AtomicBoolean writing = new AtomicBoolean();
  private volatile Backend backend;
  private volatile boolean initialized, loading, closing, persistenceFailed;
  private volatile long retryAt;

  private record Submission(String planId, String cpuId, CompletableFuture<Order> result) {}

  /** 实现只在游戏线程使用原生对象，Future 仅传递脱离游戏对象的数据。 */
  public interface Backend extends AutoCloseable {
    CompletableFuture<List<Cpu>> cpus(String network);

    CompletableFuture<Plan> createPlan(String network, String resourceId, String quantity);

    CompletableFuture<Plan> plan(String network, String planId);

    CompletableFuture<Void> cancelPlan(String network, String planId);

    CompletableFuture<Void> submit(Order order);

    CompletableFuture<Void> cancel(Order order);

    @Override
    void close();
  }

  public CraftingService(DashboardService service, Config config) {
    this.service = service;
    this.config = config;
    restore();
  }

  public void setBackend(Backend backend) {
    if (this.backend != null) throw new IllegalStateException("Crafting backend already attached");
    this.backend = Objects.requireNonNull(backend);
  }

  public boolean ready() {
    return initialized && !closing;
  }

  public String status() {
    if (persistenceFailed) return "persistence-unavailable";
    if (!initialized) return "initializing";
    return backend == null || closing ? "unavailable" : "ready";
  }

  private synchronized void restore() {
    if (initialized || loading || closing || System.currentTimeMillis() < retryAt) return;
    loading = true;
    service
        .write(
            () -> {
              List<Order> restored = new ArrayList<>();
              for (Order order : service.store().activeOrders()) {
                if (order.nativeId().isEmpty()) {
                  long now = System.currentTimeMillis();
                  service
                      .store()
                      .saveOrder(
                          new Order(
                              order.id(),
                              order.requestId(),
                              order.networkId(),
                              order.planId(),
                              order.item(),
                              order.quantity(),
                              order.requestedCpuId(),
                              order.cpuId(),
                              order.cpuName(),
                              "",
                              "unknown",
                              order.createdAt(),
                              now,
                              now,
                              "SUBMISSION_OUTCOME_UNKNOWN",
                              order.total(),
                              order.progress()));
                } else restored.add(order);
              }
              return restored;
            })
        .whenComplete(
            (orders, failure) -> {
              if (failure == null) {
                for (Order order : orders) active.put(order.id(), order);
                initialized = true;
                persistenceFailed = false;
              } else {
                persistenceFailed = true;
                retryAt = System.currentTimeMillis() + 1000;
                LOG.error(
                    "Cannot restore crafting orders; new submissions remain disabled", failure);
              }
              loading = false;
            });
  }

  private Backend backend(boolean newOrder) {
    if (newOrder && !config.craftingEnabled()) throw new Failure("CRAFTING_DISABLED");
    if (closing || backend == null) throw new Failure("CRAFTING_UNAVAILABLE");
    if (!initialized) throw new Failure("CRAFTING_INITIALIZING");
    if (newOrder && persistenceFailed) throw new Failure("CRAFTING_STORAGE_UNAVAILABLE");
    return backend;
  }

  public CompletableFuture<List<Cpu>> cpus(String network) {
    return backend(false).cpus(network);
  }

  public CompletableFuture<Plan> createPlan(String network, String resourceId, String quantity) {
    if (resourceId == null
        || resourceId.isBlank()
        || resourceId.length() > 256
        || resourceId.startsWith("group:")) throw new Failure("INVALID_RESOURCE");
    if (quantity == null || !quantity.matches("[1-9][0-9]{0,18}"))
      throw new Failure("INVALID_QUANTITY");
    try {
      Long.parseLong(quantity);
    } catch (NumberFormatException e) {
      throw new Failure("INVALID_QUANTITY");
    }
    return backend(true).createPlan(network, resourceId, quantity);
  }

  public CompletableFuture<Plan> plan(String network, String id) {
    return backend(false).plan(network, uuid(id));
  }

  public CompletableFuture<Void> cancelPlan(String network, String id) {
    return backend(false).cancelPlan(network, uuid(id));
  }

  /** 同一个请求在并发、超时重试和服务重启后均只允许提交一次。 */
  public CompletableFuture<Order> submit(
      String network, String planId, String cpuId, String requestId) {
    Backend target = backend(true);
    String plan = uuid(planId), request = uuid(requestId), cpu = Objects.requireNonNull(cpuId);
    if (cpu.length() > 512) throw new Failure("INVALID_CPU");
    String key = network + "/" + request;
    Submission entry;
    synchronized (submissions) {
      entry = submissions.get(key);
      if (entry != null) {
        if (!entry.planId().equals(plan) || !entry.cpuId().equals(cpu))
          throw new Failure("REQUEST_CONFLICT");
        return entry.result();
      }
      if (submissions.size() >= 32) throw new Failure("CRAFTING_BUSY");
      entry = new Submission(plan, cpu, new CompletableFuture<>());
      submissions.put(key, entry);
    }
    Submission pending = entry;
    // 查重必须先于读取预览：首次提交后预览可能已被原生适配器消费。
    service
        .write(() -> service.store().findOrderByRequest(network, request))
        .thenCompose(
            existing -> {
              if (existing != null)
                return CompletableFuture.completedFuture(replay(existing, plan, cpu));
              return target
                  .plan(network, plan)
                  .thenCompose(
                      preview -> {
                        if (!preview.networkId().equals(network))
                          throw new Failure("PLAN_NOT_FOUND");
                        if (!preview.state().equals("ready"))
                          throw new Failure(
                              preview.state().equals("missing")
                                  ? "MISSING_INGREDIENT"
                                  : "PLAN_NOT_READY");
                        long now = System.currentTimeMillis();
                        Order candidate =
                            new Order(
                                UUID.randomUUID().toString(),
                                request,
                                network,
                                plan,
                                preview.item(),
                                preview.quantity(),
                                cpu,
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
                        return service
                            .write(
                                () -> {
                                  if (active.size() >= MAX_ACTIVE)
                                    throw new Failure("CRAFTING_BUSY");
                                  Order saved = service.store().insertOrderIntent(candidate);
                                  if (saved.id().equals(candidate.id()))
                                    active.put(saved.id(), saved);
                                  return saved;
                                })
                            .thenCompose(
                                saved -> {
                                  if (!saved.id().equals(candidate.id()))
                                    return CompletableFuture.completedFuture(
                                        replay(saved, plan, cpu));
                                  CompletableFuture<Void> submitted;
                                  try {
                                    submitted = backend(true).submit(saved);
                                  } catch (RuntimeException e) {
                                    submitted = CompletableFuture.failedFuture(e);
                                  }
                                  return submitted
                                      .handle(
                                          (ignored, failure) -> {
                                            if (failure != null) {
                                              Order current =
                                                  active.getOrDefault(saved.id(), saved);
                                              if (!current.terminal()) {
                                                long ended = System.currentTimeMillis();
                                                update(
                                                    new Order(
                                                        current.id(),
                                                        current.requestId(),
                                                        current.networkId(),
                                                        current.planId(),
                                                        current.item(),
                                                        current.quantity(),
                                                        current.requestedCpuId(),
                                                        current.cpuId(),
                                                        current.cpuName(),
                                                        current.nativeId(),
                                                        current.nativeId().isEmpty()
                                                                && !code(failure)
                                                                    .equals(
                                                                        "SUBMISSION_OUTCOME_UNKNOWN")
                                                            ? "failed"
                                                            : "unknown",
                                                        current.createdAt(),
                                                        ended,
                                                        ended,
                                                        code(failure),
                                                        current.total(),
                                                        current.progress()));
                                              }
                                            }
                                            return saved.id();
                                          })
                                      .thenCompose(ignored -> persistLatest(saved));
                                });
                      });
            })
        .whenComplete(
            (order, failure) -> {
              if (failure != null) {
                Throwable cause = cause(failure);
                if (cause instanceof SQLException || cause instanceof RejectedExecutionException)
                  persistenceFailure(cause);
              }
              synchronized (submissions) {
                submissions.remove(key, pending);
              }
              if (failure == null) pending.result().complete(order);
              else pending.result().completeExceptionally(failure);
            });
    return pending.result();
  }

  private Order replay(Order order, String plan, String cpu) {
    if (!order.planId().equals(plan) || !order.requestedCpuId().equals(cpu))
      throw new Failure("REQUEST_CONFLICT");
    return active.getOrDefault(order.id(), order);
  }

  public Order order(String network, String id) throws Exception {
    Order cached = active.get(uuid(id));
    if (cached != null && cached.networkId().equals(network)) return cached;
    Order stored = service.store().findOrder(network, id);
    if (stored == null) throw new Failure("ORDER_NOT_FOUND");
    return stored;
  }

  public OrderPage byRequest(String network, String requestId) throws Exception {
    String request = uuid(requestId);
    Order stored = service.store().findOrderByRequest(network, request);
    if (stored == null) {
      synchronized (submissions) {
        if (submissions.containsKey(network + "/" + request)) throw new Failure("REQUEST_PENDING");
      }
      // 首次查询与检查之间可能刚完成意图落盘；再次查询后才能确认请求未被接受。
      stored = service.store().findOrderByRequest(network, request);
    }
    return new OrderPage(
        stored == null ? List.of() : List.of(active.getOrDefault(stored.id(), stored)), "");
  }

  public OrderPage orders(String network, boolean history, int limit, String cursor)
      throws Exception {
    if (limit < 1 || limit > 100) throw new Failure("INVALID_PARAMETER");
    OrderPage page = service.store().orders(network, history, limit, cursor);
    return new OrderPage(
        page.orders().stream().map(o -> active.getOrDefault(o.id(), o)).toList(),
        page.nextCursor());
  }

  public CompletableFuture<Order> cancel(String network, String id) throws Exception {
    Backend target = backend(false);
    Order order = order(network, id);
    if (order.terminal()) return CompletableFuture.completedFuture(order);
    return target.cancel(order).thenCompose(ignored -> persistLatest(order));
  }

  /** 原生生命周期事件不等待磁盘；待写数据按订单合并，终态不能被迟到的进度覆盖。 */
  public void update(Order order) {
    active.compute(
        order.id(),
        (id, before) -> {
          if (before == null) throw new IllegalStateException("Unregistered crafting order");
          if (before.terminal() || order.updatedAt() < before.updatedAt()) return before;
          dirty.put(id, order);
          return order;
        });
    flush();
  }

  public Collection<Order> activeOrders() {
    return active.values().stream().filter(o -> !o.terminal()).toList();
  }

  private CompletableFuture<Order> persistLatest(Order original) {
    return service
        .write(
            () -> {
              Order order = active.get(original.id());
              // 终态可能已由批量刷写保存并移出活动集合。
              if (order == null) {
                Order stored = service.store().findOrder(original.networkId(), original.id());
                if (stored == null) throw new IllegalStateException("Unregistered crafting order");
                return stored;
              }
              service.store().saveOrder(order);
              persisted(order);
              return order;
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) persistenceFailure(failure);
            });
  }

  private void persisted(Order order) {
    dirty.remove(order.id(), order);
    if (order.terminal()) active.remove(order.id(), order);
    if (dirty.isEmpty()) persistenceFailed = false;
  }

  /** 仅在共用写线程成功提交一次真实采样事务后调用。 */
  public void storageRecovered() {
    if (initialized && dirty.isEmpty()) persistenceFailed = false;
  }

  /** 由服务 tick 调用；失败后保留最新事件，最多每秒重试一次。 */
  public void flush() {
    if (!initialized) {
      restore();
      return;
    }
    if (dirty.isEmpty()
        || System.currentTimeMillis() < retryAt
        || !writing.compareAndSet(false, true)) return;
    List<Order> batch = dirty.values().stream().limit(closing ? MAX_ACTIVE : 16).toList();
    service
        .write(
            () -> {
              for (Order order : batch) {
                // 调度后可能收到更新事件；只保存当时最新的版本。
                Order latest = active.get(order.id());
                if (latest != null) {
                  service.store().saveOrder(latest);
                  persisted(latest);
                }
              }
              return null;
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure == null) persistenceFailed = false;
              else persistenceFailure(failure);
              writing.set(false);
            });
  }

  private void persistenceFailure(Throwable failure) {
    if (!persistenceFailed)
      LOG.error("Crafting state could not be persisted; latest events retained", failure);
    persistenceFailed = true;
    retryAt = System.currentTimeMillis() + 1000;
  }

  public static String code(Throwable failure) {
    Throwable cause = cause(failure);
    return cause instanceof Failure f ? f.code() : "CRAFTING_FAILED";
  }

  private static Throwable cause(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) cause = cause.getCause();
    return cause;
  }

  private static String uuid(String value) {
    try {
      if (value == null || value.length() != 36) throw new IllegalArgumentException();
      return UUID.fromString(value).toString();
    } catch (IllegalArgumentException e) {
      throw new Failure("INVALID_PARAMETER");
    }
  }

  @Override
  public void close() {
    closing = true;
    if (backend != null) backend.close();
    // 无论先前批次是否仍在写入，都在其后安排最后一次完整排空。
    service
        .write(
            () -> {
              for (Order order : List.copyOf(dirty.values())) {
                Order latest = active.get(order.id());
                if (latest != null) {
                  service.store().saveOrder(latest);
                  persisted(latest);
                }
              }
              return null;
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                LOG.error("Crafting shutdown left unconfirmed order updates", failure);
            });
  }
}
