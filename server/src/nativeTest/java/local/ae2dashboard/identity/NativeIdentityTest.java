package local.ae2dashboard.identity;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class NativeIdentityTest {
  private String identity(Tag tag) {
    return StableKey.create(sink -> CanonicalNbt.write(tag, sink)).toString();
  }

  @Test
  void compoundOrderingAndNumericTypes() {
    CompoundTag a = new CompoundTag();
    a.putString("name", "测试");
    a.putInt("value", 17);
    CompoundTag b = new CompoundTag();
    b.putInt("value", 17);
    b.putString("name", "测试");
    assertEquals(identity(a), identity(b));
    b.putLong("value", 17);
    assertNotEquals(identity(a), identity(b));
  }

  @Test
  void listOrderingAndDepthLimits() {
    ListTag a = new ListTag();
    a.add(IntTag.valueOf(1));
    a.add(IntTag.valueOf(2));
    ListTag b = new ListTag();
    b.add(IntTag.valueOf(2));
    b.add(IntTag.valueOf(1));
    assertNotEquals(identity(a), identity(b));
    CompoundTag root = new CompoundTag(), current = root;
    for (int i = 0; i < 70; i++) {
      CompoundTag next = new CompoundTag();
      current.put("x", next);
      current = next;
    }
    assertThrows(IllegalArgumentException.class, () -> identity(root));
  }
}
