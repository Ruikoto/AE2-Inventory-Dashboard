package local.ae2dashboard;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import local.ae2dashboard.assets.AssetPack;
import local.ae2dashboard.assets.Presentation;
import local.ae2dashboard.model.*;
import local.ae2dashboard.storage.HistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native-free service boundary, also used by the local integration harness. */
public final class DashboardService implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("ae2dashboard");
  private static final long MAX_PENDING_SAMPLE_BYTES = 64L * 1024 * 1024;
  private final HistoryStore store;
  private final AssetPack assets;
  private final Presentation presentation;
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
          // ThreadPoolExecutor 停止空闲线程时会设置中断；最终 checkpoint 不再属于可取消任务。
          boolean interrupted = Thread.interrupted();
          try {
            store.close();
            LOG.debug("History writer stopped and checkpoint completed");
          } catch (Exception e) {
            LOG.error("History shutdown failed", e);
          } finally {
            if (interrupted) Thread.currentThread().interrupt();
          }
        }
      };
  private final AtomicLong pendingSampleBytes = new AtomicLong();
  private final AtomicLong nextDropWarning = new AtomicLong();
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
    this(world, config, resource -> DisplayHint.NONE);
  }

  public DashboardService(
      Path world, Config config, java.util.function.Function<Resource, DisplayHint> displayHints)
      throws Exception {
    store =
        new HistoryStore(
            world.resolve("ae2-dashboard"),
            config.historyBudgetMiB() * 1024L * 1024,
            config.sampleIntervalSeconds() * 1000L);
    for (Network n : store.networks()) networks.put(n.id(), n);
    assets = AssetPack.load(config.assetPack());
    presentation = new Presentation(assets, displayHints);
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
    LOG.debug(
        "History writer started: queueCapacity=32 sampleBudgetBytes={} restoredNetworks={}",
        MAX_PENDING_SAMPLE_BYTES,
        networks.size());
  }

  public HistoryStore store() {
    return store;
  }

  public AssetPack assets() {
    return assets;
  }

  public Presentation presentation() {
    return presentation;
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
    boolean needed = broken.remove(id);
    if (needed) LOG.debug("History sampling starts a new segment after a gap: network={}", id);
    return needed;
  }

  public void network(Network network, String event, Set<String> related) {
    networks.put(network.id(), network);
    if (!"online".equals(network.status())) broken.add(network.id());
    LOG.debug(
        "Network observation changed: network={} state={} event={} relatedNetworks={}",
        network.id(),
        network.status(),
        event,
        related.size());
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
    if (closing) {
      drop(snapshot.networkId(), "closing");
      return;
    }
    long bytes = snapshot.estimatedBytes();
    long pending = pendingSampleBytes.get();
    while (true) {
      if (bytes > MAX_PENDING_SAMPLE_BYTES - pending) {
        drop(snapshot.networkId(), "sample-budget");
        return;
      }
      if (pendingSampleBytes.compareAndSet(pending, pending + bytes)) break;
      pending = pendingSampleBytes.get();
    }
    var task = new PendingSample(snapshot, observed, System.nanoTime());
    try {
      writer.execute(task);
    } catch (RejectedExecutionException e) {
      task.discard("writer-queue");
    }
  }

  private final class PendingSample implements Runnable {
    private final Snapshot snapshot;
    private final Network observed;
    private final long queuedAt;

    private PendingSample(Snapshot snapshot, Network observed, long queuedAt) {
      this.snapshot = snapshot;
      this.observed = observed;
      this.queuedAt = queuedAt;
    }

    @Override
    public void run() {
      long started = System.nanoTime();
      boolean confirmed = false;
      try {
        if (observed != null) store.saveNetwork(observed, null, Set.of());
        store.append(snapshot);
        crafting.storageRecovered();
        if (!writeStatus.equals(store.status()))
          LOG.debug(
              "History write status changed after sample: network={} from={} to={}",
              snapshot.networkId(),
              writeStatus,
              store.status());
        writeStatus = store.status();
        confirmed = true;
      } catch (Exception e) {
        broken.add(snapshot.networkId());
        writeStatus = store.status().startsWith("paused") ? store.status() : "paused:write";
        LOG.error(
            "History sample could not be confirmed; recording will retry: network={} timestamp={}",
            snapshot.networkId(),
            snapshot.timestamp(),
            e);
      } finally {
        long pending = pendingSampleBytes.addAndGet(-snapshot.estimatedBytes());
        LOG.debug(
            "History sample finished: network={} timestamp={} confirmed={} queueMs={} writeMs={}"
                + " sampleBytes={} pendingBytes={} queueDepth={}",
            snapshot.networkId(),
            snapshot.timestamp(),
            confirmed,
            TimeUnit.NANOSECONDS.toMillis(started - queuedAt),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            snapshot.estimatedBytes(),
            pending,
            writer.getQueue().size());
      }
    }

    private void discard(String reason) {
      // execute 拒绝或 shutdownNow 排出时任务不会再运行，由此路径释放一次预算。
      pendingSampleBytes.addAndGet(-snapshot.estimatedBytes());
      drop(snapshot.networkId(), reason);
    }
  }

  private void drop(String id, String reason) {
    long total = dropped.incrementAndGet();
    broken.add(id);
    writeStatus = "paused:queue";
    LOG.debug(
        "History sample dropped: network={} reason={} pendingBytes={} queueDepth={}"
            + " droppedTotal={}",
        id,
        reason,
        pendingSampleBytes.get(),
        writer.getQueue().size(),
        total);
    long now = System.currentTimeMillis();
    long nextWarning = nextDropWarning.get();
    if (now >= nextWarning && nextDropWarning.compareAndSet(nextWarning, now + 60_000))
      LOG.warn(
          "History sample dropped; network starts a new segment: network={} reason={}"
              + " pendingBytes={} queueDepth={} droppedTotal={} (warning limited to once per"
              + " minute)",
          id,
          reason,
          pendingSampleBytes.get(),
          writer.getQueue().size(),
          total);
  }

  private void submit(Runnable task, String id) {
    if (closing) return;
    try {
      writer.execute(task);
    } catch (RejectedExecutionException e) {
      drop(id, "metadata-queue");
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
    LOG.debug(
        "History writer shutdown requested: pendingBytes={} queueDepth={}",
        pendingSampleBytes.get(),
        writer.getQueue().size());
    writer.shutdown();
    List<Runnable> discarded = List.of();
    try {
      if (!writer.awaitTermination(5, TimeUnit.SECONDS)) discarded = writer.shutdownNow();
    } catch (InterruptedException e) {
      discarded = writer.shutdownNow();
      Thread.currentThread().interrupt();
    }
    // 超时或中断时，排队中的订单写入也必须触发失败回调。
    for (Runnable task : discarded) {
      if (task instanceof PendingWrite<?> pending)
        pending
            .result()
            .completeExceptionally(new RejectedExecutionException("Shutdown before write"));
      else if (task instanceof PendingSample sample) sample.discard("shutdown");
    }
    if (!discarded.isEmpty())
      LOG.debug("History shutdown discarded queued tasks: count={}", discarded.size());
  }
}
