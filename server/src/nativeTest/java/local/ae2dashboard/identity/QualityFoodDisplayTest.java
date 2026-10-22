package local.ae2dashboard.identity;

import static org.junit.jupiter.api.Assertions.*;

import local.ae2dashboard.model.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class QualityFoodDisplayTest {
  private Resource resource(CompoundTag tag) {
    String registry = tag.getString("id");
    return new Resource(
        NativeItemIdentity.fromTag("item", registry, tag).toString(),
        Resource.group("item", registry),
        "item",
        registry,
        "Wheat Seeds",
        "个",
        1,
        tag.toString());
  }

  @Test
  void allQualityLevelsAndDifferentEffectsKeepExactIdentityButShareDisplayBase() throws Exception {
    var base = TagParser.parseTag("{id:\"minecraft:wheat_seeds\",\"#c\":\"ae2:i\"}");
    String baseId = resource(base).id();
    assertEquals(DisplayHint.NONE, QualityFoodDisplay.hint(resource(base)));
    for (int quality = 1; quality <= 3; quality++) {
      var first = base.copy();
      first.put(
          "tag", TagParser.parseTag("{quality_food:{quality:" + quality + ",effects:[{a:1}]}}"));
      var second = base.copy();
      second.put(
          "tag", TagParser.parseTag("{quality_food:{quality:" + quality + ",effects:[{a:2}]}}"));
      assertNotEquals(resource(first).id(), resource(second).id());
      String original = first.toString();
      assertEquals(new DisplayHint(baseId, quality), QualityFoodDisplay.hint(resource(first)));
      assertEquals(new DisplayHint(baseId, quality), QualityFoodDisplay.hint(resource(second)));
      assertEquals(original, first.toString());
    }
  }

  @Test
  void customNamesModelsOtherTagsAndCapabilitiesRemainPartOfDisplayIdentity() throws Exception {
    var base =
        TagParser.parseTag(
            "{id:\"minecraft:wheat_seeds\",\"#c\":\"ae2:i\","
                + "tag:{display:{Name:'{\"text\":\"自定义种子\"}'},CustomModelData:12,other:{level:7}},"
                + "caps:{sample:{amount:4}}}");
    var quality = base.copy();
    quality.getCompound("tag").put("quality_food", TagParser.parseTag("{quality:2,effects:[1,2]}"));
    assertEquals(
        new DisplayHint(resource(base).id(), 2), QualityFoodDisplay.hint(resource(quality)));
    var plain = TagParser.parseTag("{id:\"minecraft:wheat_seeds\",\"#c\":\"ae2:i\"}");
    assertNotEquals(resource(plain).id(), QualityFoodDisplay.hint(resource(quality)).baseId());
  }

  @Test
  void unknownOrWronglyTypedQualityAndGroupDoNotInventFallback() throws Exception {
    for (String value : new String[] {"0", "4", "2b", "\"2\""}) {
      var tag =
          TagParser.parseTag(
              "{id:\"minecraft:wheat_seeds\",\"#c\":\"ae2:i\",tag:{quality_food:{quality:"
                  + value
                  + "}}}");
      assertEquals(DisplayHint.NONE, QualityFoodDisplay.hint(resource(tag)));
      assertEquals(DisplayHint.NONE, QualityFoodDisplay.hint(resource(tag).grouped()));
    }
  }

  @Test
  void invalidIdentityCannotMasqueradeAsAnotherResourceOrLeakItsNbt() {
    var bad =
        new Resource(
            "invalid",
            "group:item:minecraft:wheat_seeds",
            "item",
            "minecraft:wheat_seeds",
            "seed",
            "个",
            1,
            "{quality_food:private_broken");
    var error = assertThrows(IllegalArgumentException.class, () -> QualityFoodDisplay.hint(bad));
    assertFalse(error.toString().contains("private_broken"));
    var wrong =
        new Resource(
            "invalid",
            bad.groupId(),
            "item",
            bad.registryId(),
            "seed",
            "个",
            1,
            "{id:\"minecraft:stone\",\"#c\":\"ae2:i\",tag:{quality_food:{quality:1}}}");
    assertThrows(IllegalArgumentException.class, () -> QualityFoodDisplay.hint(wrong));
  }
}
