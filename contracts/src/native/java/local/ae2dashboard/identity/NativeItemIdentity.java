package local.ae2dashboard.identity;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

/** Exact native AE key identity, including persistent addon data supported by native codecs. */
@SuppressWarnings("UnstableApiUsage")
public final class NativeItemIdentity {

  private NativeItemIdentity() {}

  public static @NotNull StableKey getKey(@NotNull AEKey key) {
    CompoundTag tag = key.toTagGeneric();
    // The native codec allocates its tag first. Bounds cover only the subsequent traversal.
    return fromTag(kind(key), key.getId().toString(), tag);
  }

  /** 共用原生编码散列；传入标签只读且由调用方拥有。 */
  static StableKey fromTag(String kind, String registryId, CompoundTag tag) {
    return StableKey.create(
        sink -> {
          StableKey.writeText(sink, kind);
          StableKey.writeText(sink, registryId);
          sink.putInt(0);
          sink.putByte((byte) 1);
          CanonicalNbt.write(tag, sink);
        });
  }

  public static @NotNull AEKey copy(@NotNull AEKey key) {
    // Built-in AE keys retain immutable identity under AE2's read-only tag/stack contract.
    if (key instanceof AEItemKey || key instanceof AEFluidKey) return key;
    return decodeCopy(key, key.toTagGeneric());
  }

  private static @NotNull AEKey decodeCopy(@NotNull AEKey key, @NotNull CompoundTag tag) {
    AEKey copy = key.getType().loadKeyFromTag(tag);
    if (copy == null) {
      throw new UnsupportedOperationException("Native identity could not be decoded");
    }
    return copy;
  }

  private static @NotNull String kind(@NotNull AEKey key) {
    if (key instanceof AEItemKey) return "item";
    if (key instanceof AEFluidKey) return "fluid";
    return "ae-key:" + key.getType().getId();
  }
}
