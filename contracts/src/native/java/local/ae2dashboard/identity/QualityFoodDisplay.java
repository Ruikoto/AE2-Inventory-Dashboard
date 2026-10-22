package local.ae2dashboard.identity;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import local.ae2dashboard.model.DisplayHint;
import local.ae2dashboard.model.Resource;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/** 解析当前线程独占的标签副本；不访问世界、注册表或AE共享对象。 */
public final class QualityFoodDisplay {
  private QualityFoodDisplay() {}

  public static DisplayHint hint(Resource resource) {
    if (!resource.type().equals("item")
        || resource.id().startsWith("group:")
        || !resource.identity().contains("quality_food")) return DisplayHint.NONE;
    try {
      var root = TagParser.parseTag(resource.identity());
      // AE2 15.4.10内建物品编码的通道是ae2:i，不读取运行时注册表。
      if (!root.getString("id").equals(resource.registryId())
          || !root.getString("#c").equals("ae2:i"))
        throw new IllegalArgumentException("Display identity does not match resource");
      var tag = root.getCompound("tag");
      var food = tag.getCompound("quality_food");
      if (!food.contains("quality", Tag.TAG_INT)) return DisplayHint.NONE;
      int quality = food.getInt("quality");
      if (quality < 1 || quality > 3) return DisplayHint.NONE;
      tag.remove("quality_food");
      if (tag.isEmpty()) root.remove("tag");
      return new DisplayHint(
          NativeItemIdentity.fromTag("item", resource.registryId(), root).toString(), quality);
    } catch (CommandSyntaxException e) {
      // 解析异常可能包含原始SNBT，跨边界只发布不含原文的原因。
      throw new IllegalArgumentException("Invalid display identity syntax");
    }
  }
}
