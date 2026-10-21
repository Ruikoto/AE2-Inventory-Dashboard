package local.ae2dashboard.forge;

import appeng.api.networking.*;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.stacks.*;
import java.math.BigInteger;
import java.util.*;
import local.ae2dashboard.*;
import local.ae2dashboard.identity.NativeItemIdentity;
import local.ae2dashboard.model.*;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.*;

/** Called only at END server tick. An individual native inventory scan is indivisible. */
public final class AeSampler {
  private static final Logger LOG = LoggerFactory.getLogger("ae2dashboard");
  private final DashboardService service;
  private final long interval;
  private final IdentityHashMap<IGrid, Binding> bindings = new IdentityHashMap<>();
  private long lastDiscovery;
  private long version;
  private long cachedBytes;
  private final Map<AEKey, Resource> resources = new LinkedHashMap<>(256, 0.75f, true);

  private static final class Binding {
    Network network;
    String segment = UUID.randomUUID().toString();
    long next;
    String status = "booting";
    boolean failed;

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
        b.segment = UUID.randomUUID().toString();
        b.network =
            new Network(b.network.id(), b.network.createdAt(), now, state, b.network.anchors());
        service.network(b.network, state, Set.of());
      }
      if (now < b.next || !state.equals("online")) continue;
      b.next = now + interval;
      try {
        if (service.needsSegment(b.network.id())) b.segment = UUID.randomUUID().toString();
        List<Resource.Stored> exact = new ArrayList<>();
        long snapshotBytes = 0;
        var counter = grid.getStorageService().getCachedInventory();
        if (counter.size() > 100000)
          throw new IllegalStateException("Network exceeds 100000 resource sampling limit");
        for (var value : counter) {
          long q = value.getLongValue();
          if (q < 0) throw new IllegalStateException("AE returned a negative inventory quantity");
          if (q == 0) continue;
          AEKey key = value.getKey();
          Resource cached = resources.get(key);
          if (cached != null) {
            snapshotBytes += cached.estimatedBytes();
            if (snapshotBytes > 32L * 1024 * 1024)
              throw new IllegalStateException("Snapshot metadata exceeds 32 MiB");
            exact.add(new Resource.Stored(cached, BigInteger.valueOf(q)));
            continue;
          }
          String id = NativeItemIdentity.getKey(key).toString();
          String type =
              key instanceof AEItemKey
                  ? "item"
                  : key instanceof AEFluidKey ? "fluid" : "ae-key:" + key.getType().getId();
          String registry = key.getId().toString();
          CompoundTag serialized = key.toTagGeneric();
          var component = key.getDisplayName();
          String name = component.getString();
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
          snapshotBytes += resource.estimatedBytes();
          if (snapshotBytes > 32L * 1024 * 1024)
            throw new IllegalStateException("Snapshot metadata exceeds 32 MiB");
          exact.add(new Resource.Stored(resource, BigInteger.valueOf(q)));
          if (key instanceof AEItemKey || key instanceof AEFluidKey) {
            resources.put(key, resource);
            cachedBytes += resource.estimatedBytes();
            while (resources.size() > 16384 || cachedBytes > 16 * 1024 * 1024) {
              var it = resources.entrySet().iterator();
              var removed = it.next();
              cachedBytes -= removed.getValue().estimatedBytes();
              it.remove();
            }
          }
        }
        service.sample(Snapshot.of(b.network.id(), b.segment, now, ++version, exact));
        b.failed = false;
      } catch (RuntimeException e) {
        b.failed = true;
        b.segment = UUID.randomUUID().toString();
        b.status = "error";
        service.network(
            new Network(b.network.id(), b.network.createdAt(), now, "error", b.network.anchors()),
            "error",
            Set.of());
        LOG.error("AE inventory sampling failed; no partial sample was published", e);
      }
    }
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

  private static String state(IGrid grid) {
    if (grid.isEmpty()) return "unloaded";
    if (grid.getPathingService().isNetworkBooting()) return "booting";
    if (grid.getPathingService().getControllerState() == ControllerState.CONTROLLER_CONFLICT)
      return "conflict";
    if (!grid.getEnergyService().isNetworkPowered()) return "unpowered";
    return "online";
  }
}
