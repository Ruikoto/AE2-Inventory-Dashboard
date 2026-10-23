package local.ae2dashboard.forge;

import appeng.api.networking.*;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.*;
import java.math.BigInteger;
import java.util.*;
import local.ae2dashboard.*;
import local.ae2dashboard.identity.NativeItemIdentity;
import local.ae2dashboard.model.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.*;

/** Called only at END server tick. An individual native inventory scan is indivisible. */
public final class AeSampler {
  private static final Logger LOG = LoggerFactory.getLogger("ae2dashboard");
  private final DashboardService service;
  private final long interval;
  private final IdentityHashMap<IGrid, Binding> bindings = new IdentityHashMap<>();
  private long lastDiscovery;
  private long version;
  private long bindingRevision;
  private long cachedBytes;
  private final Map<AEKey, Resource> resources = new LinkedHashMap<>(256, 0.75f, true);

  private static final class Binding {
    Network network;
    String segment = UUID.randomUUID().toString();
    long next;
    String status = "booting";
    boolean failed;
    long revision;

    Binding(Network network, long next) {
      this.network = network;
      this.next = next;
    }
  }

  public AeSampler(DashboardService service, int intervalSeconds) {
    this.service = service;
    this.interval = intervalSeconds * 1000L;
  }

  public void tick() {
    long now = System.currentTimeMillis();
    if (now - lastDiscovery >= 1000) {
      discover(now);
      lastDiscovery = now;
    }
    for (var entry : bindings.entrySet()) {
      IGrid grid = entry.getKey();
      Binding b = entry.getValue();
      String state = state(grid);
      if (state.equals("online") && b.failed && now < b.next) state = "error";
      if (!state.equals(b.status)) {
        b.status = state;
        b.revision = ++bindingRevision;
        b.segment = UUID.randomUUID().toString();
        b.network =
            new Network(b.network.id(), b.network.createdAt(), now, state, b.network.anchors());
        service.network(b.network, state, Set.of());
      }
      if (now < b.next || !state.equals("online")) continue;
      long lag = now - b.next;
      b.next = nextSampleAt(b.next, now, interval);
      long started = System.nanoTime();
      long sampledBytes = 0;
      boolean sampled = false;
      try {
        if (service.needsSegment(b.network.id())) b.segment = UUID.randomUUID().toString();
        List<Resource.Stored> exact = new ArrayList<>();
        long snapshotBytes = 0;
        var counter = grid.getStorageService().getCachedInventory();
        var crafting = grid.getCraftingService();
        Set<AEKey> keys = new HashSet<>(crafting.getCraftables(key -> true));
        for (var value : counter) {
          if (value.getLongValue() < 0)
            throw new IllegalStateException("AE returned a negative inventory quantity");
          if (value.getLongValue() > 0) keys.add(value.getKey());
        }
        if (keys.size() > 100000)
          throw new IllegalStateException("Network exceeds 100000 resource sampling limit");
        Set<AEKey> infinite = grid.getService(MonitoringGridService.class).infiniteKeys();
        for (AEKey key : keys) {
          long q = counter.get(key);
          Resource resource = resource(key);
          snapshotBytes += resource.estimatedBytes();
          if (snapshotBytes > 32L * 1024 * 1024)
            throw new IllegalStateException("Snapshot metadata exceeds 32 MiB");
          exact.add(
              new Resource.Stored(
                  resource,
                  BigInteger.valueOf(q),
                  crafting.isCraftable(key) || crafting.canEmitFor(key),
                  infinite.contains(key)));
        }
        Snapshot snapshot = Snapshot.of(b.network.id(), b.segment, now, ++version, exact);
        sampledBytes = snapshot.estimatedBytes();
        service.sample(snapshot);
        b.failed = false;
        sampled = true;
      } catch (RuntimeException e) {
        b.failed = true;
        b.segment = UUID.randomUUID().toString();
        b.status = "error";
        service.network(
            new Network(b.network.id(), b.network.createdAt(), now, "error", b.network.anchors()),
            "error",
            Set.of());
        LOG.error("AE inventory sampling failed; no partial sample was published", e);
      } finally {
        LOG.debug(
            "AE sampling finished: network={} timestamp={} published={} lagMs={} skippedSlots={}"
                + " durationMs={} sampleBytes={} nextAt={}",
            b.network.id(),
            now,
            sampled,
            lag,
            lag / interval,
            (System.nanoTime() - started) / 1_000_000,
            sampledBytes,
            b.next);
      }
    }
  }

  /** 已到期时跳过错过的槽位，保留各网络原有相位；每个 tick 仍只采一次。 */
  static long nextSampleAt(long next, long now, long interval) {
    return next + ((now - next) / interval + 1) * interval;
  }

  private void discover(long now) {
    var active = MonitoringGridService.active();
    Map<String, Set<String>> candidates = new HashMap<>();
    Map<String, IGrid> byKey = new HashMap<>();
    int index = 0;
    for (var monitor : active) {
      if (monitor.grid.isEmpty()) {
        MonitoringGridService.retire(monitor.grid);
        continue;
      }
      Set<String> anchors = monitor.controllers();
      if (!anchors.isEmpty()) {
        String key = Integer.toString(index++);
        candidates.put(key, anchors);
        byKey.put(key, monitor.grid);
      }
    }
    Set<IGrid> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    var matches =
        NetworkIdentity.match(
            candidates,
            service.networks().stream().filter(n -> !n.status().equals("merged")).toList(),
            now);
    for (var match : matches) {
      IGrid grid = byKey.get(match.current());
      seen.add(grid);
      Binding old = bindings.get(grid);
      if (old == null
          || !old.network.id().equals(match.network().id())
          || !old.network.anchors().equals(match.network().anchors())
          || match.changed()) {
        Binding binding =
            new Binding(
                match.network(),
                now
                    + (Math.floorMod(match.network().id().hashCode(), Math.max(1, interval / 50))
                        * 50));
        binding.revision = ++bindingRevision;
        bindings.put(grid, binding);
        service.network(match.network(), old == null ? "discovered" : "topology", match.sources());
      }
    }
    Set<String> assigned = new HashSet<>();
    for (var match : matches) assigned.add(match.network().id());
    for (var match : matches)
      for (String source : match.sources())
        if (!assigned.contains(source)) {
          service.networks().stream()
              .filter(n -> n.id().equals(source) && !n.anchors().isEmpty())
              .findFirst()
              .ifPresent(
                  n ->
                      service.network(
                          new Network(n.id(), n.createdAt(), now, "merged", n.anchors()),
                          "merged",
                          Set.of(match.network().id())));
        }
    for (var it = bindings.entrySet().iterator(); it.hasNext(); ) {
      var entry = it.next();
      if (!seen.contains(entry.getKey())) {
        var n = entry.getValue().network;
        if (!assigned.contains(n.id())
            && service.networks().stream()
                .noneMatch(
                    current -> current.id().equals(n.id()) && current.status().equals("merged")))
          service.network(
              new Network(n.id(), n.createdAt(), now, "unloaded", n.anchors()),
              "unloaded",
              Set.of());
        it.remove();
      }
    }
  }

  /** 仅游戏线程使用；修订号用于阻止过期预览跨网络重组提交。 */
  public record Access(IGrid grid, long revision, IActionHost host, ServerLevel level) {}

  public Access access(String networkId) {
    for (var entry : bindings.entrySet()) {
      if (!entry.getValue().network.id().equals(networkId)) continue;
      IGrid grid = entry.getKey();
      if (!state(grid).equals("online")) throw new Crafting.Failure("NETWORK_UNAVAILABLE");
      var node = grid.getService(MonitoringGridService.class).controllerNode();
      if (node == null || !(node.getOwner() instanceof IActionHost host))
        throw new Crafting.Failure("NETWORK_UNAVAILABLE");
      return new Access(grid, entry.getValue().revision, host, node.getLevel());
    }
    throw new Crafting.Failure("NETWORK_UNAVAILABLE");
  }

  public String networkId(IGrid grid) {
    var binding = bindings.get(grid);
    return binding == null ? null : binding.network.id();
  }

  /** 从本网络已发布的精确身份恢复 key，随后仍由合成服务验证当前能力。 */
  AEKey resolve(String networkId, String id) {
    var current = service.latest(networkId);
    var stored = current == null ? null : current.values().get(id);
    if (stored == null || id.startsWith("group:")) throw new Crafting.Failure("RESOURCE_NOT_FOUND");
    try {
      var key = AEKey.fromTagGeneric(TagParser.parseTag(stored.resource().identity()));
      if (key == null || !NativeItemIdentity.getKey(key).toString().equals(id))
        throw new Crafting.Failure("RESOURCE_NOT_FOUND");
      return key;
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
      throw new IllegalStateException("Published resource identity is invalid", e);
    }
  }

  Resource resource(AEKey key) {
    Resource cached = resources.get(key);
    if (cached != null) return cached;
    String id = NativeItemIdentity.getKey(key).toString();
    String type =
        key instanceof AEItemKey
            ? "item"
            : key instanceof AEFluidKey ? "fluid" : "ae-key:" + key.getType().getId();
    String registry = key.getId().toString();
    CompoundTag serialized = key.toTagGeneric();
    String name = key.getDisplayName().getString();
    if (name.isBlank()
        || name.equals(registry)
        || name.startsWith("item.")
        || name.startsWith("block.")
        || name.startsWith("fluid.")) name = registry;
    String unit =
        type.equals("item")
            ? "个"
            : type.equals("fluid") ? "mB" : Objects.toString(key.getUnitSymbol(), "单位");
    Resource resource =
        new Resource(
            id,
            Resource.group(type, registry),
            type,
            registry,
            name,
            unit,
            key.getAmountPerUnit(),
            serialized.toString());
    if (key instanceof AEItemKey || key instanceof AEFluidKey) {
      resources.put(key, resource);
      cachedBytes += resource.estimatedBytes();
      while (resources.size() > 16384 || cachedBytes > 16 * 1024 * 1024) {
        var it = resources.entrySet().iterator();
        cachedBytes -= it.next().getValue().estimatedBytes();
        it.remove();
      }
    }
    return resource;
  }

  private static String state(IGrid grid) {
    if (grid.isEmpty()) return "unloaded";
    if (grid.getPathingService().isNetworkBooting()) return "booting";
    if (grid.getPathingService().getControllerState() == ControllerState.CONTROLLER_CONFLICT)
      return "conflict";
    if (!grid.getEnergyService().isNetworkPowered()) return "unpowered";
    return "online";
  }
}
