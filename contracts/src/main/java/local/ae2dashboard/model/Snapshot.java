package local.ae2dashboard.model;

import java.math.BigInteger;
import java.util.*;

public record Snapshot(
    String networkId,
    String segment,
    long timestamp,
    long version,
    Map<String, Resource.Stored> values) {
  public Snapshot {
    values = Map.copyOf(values);
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
      Resource group = value.resource().grouped();
      result.compute(
          group.id(),
          (id, old) ->
              new Resource.Stored(
                  group, value.quantity().add(old == null ? BigInteger.ZERO : old.quantity())));
    }
    return new Snapshot(networkId, segment, timestamp, version, result);
  }
}
