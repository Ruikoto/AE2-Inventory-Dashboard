package local.ae2dashboard.model;

import java.math.BigInteger;
import java.util.Objects;

/** Detached metadata; native objects never cross the sampler boundary. */
public record Resource(
    String id,
    String groupId,
    String type,
    String registryId,
    String name,
    String unit,
    int amountPerUnit,
    String identity) {
  public Resource {
    Objects.requireNonNull(id);
    Objects.requireNonNull(groupId);
    Objects.requireNonNull(identity);
    if (id.length() > 256 || identity.length() > 1_048_576 || name.length() > 4096)
      throw new IllegalArgumentException("Resource metadata exceeds limits");
  }

  public static String group(String type, String registryId) {
    return "group:" + type + ":" + registryId;
  }

  public Resource grouped() {
    return new Resource(
        groupId, groupId, type, registryId, registryId, unit, amountPerUnit, "group:" + groupId);
  }

  public long estimatedBytes() {
    return 512L
        + 2L
            * (identity.length()
                + name.length()
                + registryId.length()
                + id.length()
                + groupId.length());
  }

  public record Stored(Resource resource, BigInteger quantity) {
    public Stored {
      Objects.requireNonNull(resource);
      if (quantity.signum() < 0) throw new IllegalArgumentException("Negative inventory");
    }
  }
}
