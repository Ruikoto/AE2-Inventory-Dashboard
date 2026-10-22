package local.ae2dashboard.assets;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import local.ae2dashboard.DisplayFixtures;
import local.ae2dashboard.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresentationTest {
  @TempDir Path directory;

  @Test
  void exactImageWinsAndMissingExactImageUsesBasePlusOverlay() throws Exception {
    var resolver =
        new Presentation(AssetPack.load(DisplayFixtures.pack(directory)), DisplayFixtures::hint);
    var silver = resolver.display(DisplayFixtures.resource(1));
    assertEquals("小麦种子", silver.name());
    assertEquals(1, silver.quality());
    assertEquals(0, silver.icon().x());
    assertEquals(128, silver.overlay().x());
    var gold = resolver.display(DisplayFixtures.resource(2));
    assertEquals("精确种子", gold.name());
    assertEquals(64, gold.icon().x());
    assertNull(gold.overlay());
    var diamond = resolver.display(DisplayFixtures.resource(3));
    assertEquals("钻石种子", diamond.name());
    assertEquals(0, diamond.icon().x());
    assertNotNull(diamond.overlay());
    var grouped = resolver.display(DisplayFixtures.resource(1).grouped());
    assertEquals(0, grouped.quality());
    assertNull(grouped.overlay());
  }

  @Test
  void noGenericGroupFallbackAndAbsentIdentityDoesNotPoisonCache() throws Exception {
    var resolver =
        new Presentation(AssetPack.load(DisplayFixtures.pack(directory)), DisplayFixtures::hint);
    var original = DisplayFixtures.resource(1);
    var missing =
        new Resource(
            original.id(),
            original.groupId(),
            original.type(),
            original.registryId(),
            original.name(),
            original.unit(),
            original.amountPerUnit(),
            "");
    assertNull(resolver.display(missing).icon());
    assertEquals("小麦种子", resolver.display(original).name());
    assertEquals(1, resolver.display(missing).quality());
    var custom =
        new Crafting.Item(
            "custom", "Custom seed", "个", "item", DisplayFixtures.REGISTRY, "not-exported", 1);
    assertEquals("Custom seed", resolver.display(custom).name());
    assertNull(resolver.display(custom).icon());
  }

  @Test
  void optionalPackAndInvalidMetadataDoNotBreakOriginalDisplay() {
    var calls = new AtomicInteger();
    var resolver =
        new Presentation(
            AssetPack.load(directory.resolve("missing.zip")),
            resource -> {
              calls.incrementAndGet();
              throw new IllegalArgumentException("Invalid metadata");
            });
    var first = resolver.display(DisplayFixtures.resource(1));
    assertEquals("Wheat Seeds", first.name());
    assertNull(first.icon());
    resolver.display(DisplayFixtures.resource(1));
    assertEquals(1, calls.get());
  }

  @Test
  void cacheIsBoundedAndDoesNotRetainRawIdentities() {
    var calls = new AtomicInteger();
    var resolver =
        new Presentation(
            AssetPack.load(directory.resolve("missing.zip")),
            resource -> {
              calls.incrementAndGet();
              return DisplayHint.NONE;
            });
    for (int i = 0; i <= 16384; i++)
      resolver.hint(
          new Resource("r" + i, "group:item:test:x", "item", "test:x", "X", "个", 1, "{}"));
    resolver.hint(new Resource("r0", "group:item:test:x", "item", "test:x", "X", "个", 1, "{}"));
    assertEquals(16386, calls.get());
  }
}
