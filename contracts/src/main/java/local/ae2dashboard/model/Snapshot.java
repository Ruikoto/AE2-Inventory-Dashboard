package local.ae2dashboard.model;

import java.math.BigInteger;
import java.util.*;

public record Snapshot(
    String networkId,
    String segment,
    long timestamp,
    long version,
    Map<String, Resource.Stored> values,
    long estimatedBytes) {
  public Snapshot(
      String networkId,
      String segment,
      long timestamp,
      long version,
      Map<String, Resource.Stored> values) {
    this(networkId, segment, timestamp, version, values, 0);
  }

  public Snapshot {
    values = Map.copyOf(values);
    estimatedBytes = values.values().stream().mapToLong(v -> v.resource().estimatedBytes()).sum();
    if (estimatedBytes > 32L * 1024 * 1024)
      throw new IllegalArgumentException("Detached snapshot exceeds 32 MiB");
  }

  public static Snapshot of(
      String networkId,
      String segment,
      long timestamp,
      long version,
      Collection<Resource.Stored> exact) {
    Map<String, Resource.Stored> result = new HashMap<>();
    for (var value : exact) {
      var prior = result.put(value.resource().id(), value);
      if (prior != null && !prior.resource().identity().equals(value.resource().identity()))
        throw new IllegalArgumentException("Identity collision");
      if (prior != null)
        throw new IllegalArgumentException("Duplicate resource in complete snapshot");
      Resource group = value.resource().grouped();
      result.compute(
          group.id(),
          (id, old) ->
              new Resource.Stored(
                  group,
                  value.quantity().add(old == null ? BigInteger.ZERO : old.quantity()),
                  value.craftable() || old != null && old.craftable(),
                  value.infinite() || old != null && old.infinite()));
    }
    return new Snapshot(networkId, segment, timestamp, version, result);
  }
}
