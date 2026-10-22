package local.ae2dashboard;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import local.ae2dashboard.assets.AssetPack;
import local.ae2dashboard.model.*;
import local.ae2dashboard.storage.HistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native-free service boundary, also used by the local integration harness. */
public final class DashboardService implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("ae2dashboard");
  private final HistoryStore store;
  private final AssetPack assets;
  private final CraftingService crafting;
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          r -> {
            var t = new Thread(r, "ae2-dashboard-writer");
            t.setDaemon(true);
            return t;
          }) {
        @Override
        protected void terminated() {
          try {
            store.close();
          } catch (Exception e) {
            LOG.error("History shutdown failed", e);
          }
        }
      };
  private final Semaphore pendingSamples = new Semaphore(2);
  private final ConcurrentHashMap<String, Network> networks = new ConcurrentHashMap<>();

  public record Current(Snapshot snapshot, Snapshot before) {}

  private final ConcurrentHashMap<String, Current> current = new ConcurrentHashMap<>();

  private record Observation(long timestamp, long nonZeroResources, long craftableResources) {}

  private final ConcurrentHashMap<String, Observation> observations = new ConcurrentHashMap<>();
  private final Set<String> broken = ConcurrentHashMap.newKeySet();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong snapshotBytes = new AtomicLong(), cacheEvictions = new AtomicLong();
  private volatile String writeStatus = "recording";
  private volatile boolean closing;
  private volatile String httpStatus = "starting";

  public DashboardService(Path world, Config config) throws Exception {
    store =
        new HistoryStore(
            world.resolve("ae2-dashboard"),
            config.historyBudgetMiB() * 1024L * 1024,
            config.sampleIntervalSeconds() * 1000L);
    for (Network n : store.networks()) networks.put(n.id(), n);
    assets = AssetPack.load(config.assetPack());
    crafting = new CraftingService(this, config);
    // 后台恢复显示信息；只排一个任务，避免把历史网络数误算为丢样。
    writer.execute(
        () -> {
          for (Network network : networks.values()) {
            if (closing) break;
            try {
              var observation = store.lastObservation(network.id());
              if (observation.timestamp() > 0)
                observations.putIfAbsent(
                    network.id(),
                    new Observation(
                        observation.timestamp(),
                        observation.nonZeroResources(),
                        observation.craftableResources()));
            } catch (Exception e) {
              LOG.warn("Cannot restore network display metadata", e);
            }
          }
        });
  }

  public HistoryStore store() {
    return store;
  }

  public AssetPack assets() {
    return assets;
  }

  public CraftingService crafting() {
    return crafting;
  }

  /** 在现有单写入线程执行订单持久化；排队失败必须由调用者处理。 */
  public <T> CompletableFuture<T> write(Callable<T> task) {
    var result = new CompletableFuture<T>();
    if (closing) return CompletableFuture.failedFuture(new RejectedExecutionException("Closing"));
    try {
      writer.execute(new PendingWrite<>(task, result));
    } catch (RejectedExecutionException e) {
      result.completeExceptionally(e);
    }
    return result;
  }

  private record PendingWrite<T>(Callable<T> task, CompletableFuture<T> result)
      implements Runnable {
    @Override
    public void run() {
      try {
        result.complete(task.call());
      } catch (Exception e) {
        result.completeExceptionally(e);
      }
    }
  }

  public Collection<Network> networks() {
    return List.copyOf(networks.values());
  }

  public Snapshot latest(String id) {
    var pair = current.get(id);
    return pair == null ? null : pair.snapshot();
  }

  public Snapshot before(String id) {
    var pair = current.get(id);
    return pair == null ? null : pair.before();
  }

  public Current current(String id) {
    return current.get(id);
  }

  public Long nonZeroResources(String id) {
    var observed = observations.get(id);
    return observed == null ? null : observed.nonZeroResources();
  }

  public Long observedAt(String id) {
    var observed = observations.get(id);
    return observed == null ? null : observed.timestamp();
  }

  public Long craftableResources(String id) {
    var observed = observations.get(id);
    return observed == null ? null : observed.craftableResources();
  }

  public String worldId() {
    return store.worldId();
  }

  public void httpStatus(String status) {
    httpStatus = status;
  }

  public Map<String, Object> status() {
    return Map.of(
        "worldId",
        worldId(),
        "historyStatus",
        writeStatus,
        "historyBytes",
        store.bytes(),
        "droppedSamples",
        dropped.get(),
        "assets",
        assets.summary(),
        "httpStatus",
        httpStatus,
        "snapshotCacheBytes",
        snapshotBytes.get(),
        "snapshotCacheEvictions",
        cacheEvictions.get());
  }

  public boolean needsSegment(String id) {
    return broken.remove(id);
  }

  public void network(Network network, String event, Set<String> related) {
    networks.put(network.id(), network);
    if (!"online".equals(network.status())) broken.add(network.id());
    submit(
        () -> {
          try {
            store.saveNetwork(network, event, related);
          } catch (Exception e) {
            writeStatus = "paused:network";
            LOG.error("Cannot persist network metadata", e);
          }
        },
        network.id());
  }

  public void sample(Snapshot snapshot) {
    observations.put(
        snapshot.networkId(),
        new Observation(
            snapshot.timestamp(),
            snapshot.values().values().stream()
                .filter(v -> !v.resource().id().startsWith("group:") && v.quantity().signum() > 0)
                .count(),
            snapshot.values().values().stream()
                .filter(v -> !v.resource().id().startsWith("group:") && v.craftable())
                .count()));
    current.compute(
        snapshot.networkId(),
        (id, old) -> {
          snapshotBytes.addAndGet(
              snapshot.estimatedBytes()
                  - (old == null || old.before() == null ? 0 : old.before().estimatedBytes()));
          return new Current(snapshot, old == null ? null : old.snapshot());
        });
    while (current.size() > 128 || snapshotBytes.get() > 512L * 1024 * 1024) {
      var oldest =
          current.entrySet().stream()
              .min(Comparator.comparingLong(e -> e.getValue().snapshot().timestamp()))
              .orElse(null);
      if (oldest == null) break;
      if (current.remove(oldest.getKey(), oldest.getValue())) {
        var removed = oldest.getValue();
        snapshotBytes.addAndGet(
            -removed.snapshot().estimatedBytes()
                - (removed.before() == null ? 0 : removed.before().estimatedBytes()));
        cacheEvictions.incrementAndGet();
      }
    }
    Network n = networks.get(snapshot.networkId());
    Network observed =
        n == null
            ? null
            : new Network(n.id(), n.createdAt(), snapshot.timestamp(), "online", n.anchors());
    if (n != null)
      networks.put(
          n.id(), new Network(n.id(), n.createdAt(), snapshot.timestamp(), "online", n.anchors()));
    if (closing || !pendingSamples.tryAcquire()) {
      drop(snapshot.networkId());
      return;
    }
    try {
      writer.execute(
          () -> {
            try {
              if (observed != null) store.saveNetwork(observed, null, Set.of());
              store.append(snapshot);
              crafting.storageRecovered();
              writeStatus = store.status();
            } catch (Exception e) {
              broken.add(snapshot.networkId());
              writeStatus = store.status().startsWith("paused") ? store.status() : "paused:write";
              LOG.error("History sample could not be confirmed; recording will retry", e);
            } finally {
              pendingSamples.release();
            }
          });
    } catch (RejectedExecutionException e) {
      pendingSamples.release();
      drop(snapshot.networkId());
    }
  }

  private void drop(String id) {
    dropped.incrementAndGet();
    broken.add(id);
    writeStatus = "paused:queue";
  }

  private void submit(Runnable task, String id) {
    if (closing) return;
    try {
      writer.execute(task);
    } catch (RejectedExecutionException e) {
      drop(id);
    }
  }

  public CompletableFuture<Path> exportKeys(Path world) {
    var future = new CompletableFuture<Path>();
    try {
      writer.execute(
          () -> {
            try {
              Path target = world.resolve("ae2-dashboard/resource-keys.json");
              store.exportKeys(target);
              future.complete(target);
            } catch (Exception e) {
              future.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  @Override
  public void close() {
    crafting.close();
    closing = true;
    writer.shutdown();
    List<Runnable> discarded = List.of();
    try {
      if (!writer.awaitTermination(5, TimeUnit.SECONDS)) discarded = writer.shutdownNow();
    } catch (InterruptedException e) {
      discarded = writer.shutdownNow();
      Thread.currentThread().interrupt();
    }
    // 超时或中断时，排队中的订单写入也必须触发失败回调。
    for (Runnable task : discarded)
      if (task instanceof PendingWrite<?> pending)
        pending
            .result()
            .completeExceptionally(new RejectedExecutionException("Shutdown before write"));
  }
}
