package local.ae2dashboard.assets;

import java.util.*;
import java.util.function.Function;
import local.ae2dashboard.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 库存和合成共用的显示解析；资产永远不能改变资源身份。 */
public final class Presentation {
  private static final Logger LOG = LoggerFactory.getLogger("ae2dashboard");
  private static final int CACHE_LIMIT = 16384;
  private final AssetPack assets;
  private final Function<Resource, DisplayHint> derive;
  private final Map<String, DisplayHint> hints = new LinkedHashMap<>(16, 0.75f, true);

  public record Icon(String url, int x, int y, int width, int height) {}

  public record Display(String name, int quality, Icon icon, Icon overlay) {}

  public Presentation(AssetPack assets, Function<Resource, DisplayHint> derive) {
    this.assets = assets;
    this.derive = derive;
  }

  public DisplayHint hint(Resource resource) {
    if (resource.id().startsWith("group:") || !resource.type().equals("item"))
      return DisplayHint.NONE;
    synchronized (hints) {
      var cached = hints.get(resource.id());
      if (cached != null) return cached;
    }
    // 旧订单/分页可能尚未带身份，不把缺输入永久缓存为无品质。
    if (resource.identity().isEmpty()) return DisplayHint.NONE;
    DisplayHint result;
    try {
      result = derive.apply(resource);
    } catch (IllegalArgumentException invalid) {
      LOG.warn("Cannot decode display identity for resource {}", resource.id());
      result = DisplayHint.NONE;
    }
    synchronized (hints) {
      hints.put(resource.id(), result);
      if (hints.size() > CACHE_LIMIT) hints.remove(hints.keySet().iterator().next());
    }
    return result;
  }

  public Crafting.Item item(Resource resource) {
    var hint = hint(resource);
    return new Crafting.Item(
        resource.id(),
        resource.name(),
        resource.unit(),
        resource.type(),
        resource.registryId(),
        hint.baseId(),
        hint.quality());
  }

  public Display display(Resource resource) {
    return display(item(resource));
  }

  public Display display(Crafting.Item item) {
    int quality = item.quality() >= 1 && item.quality() <= 3 ? item.quality() : 0;
    var exact = mapping(item.id(), item.registryId(), item.type());
    var base =
        quality == 0 || item.baseDisplayId() == null
            ? null
            : mapping(item.baseDisplayId(), item.registryId(), item.type());
    String name = exact != null ? exact.name() : base != null ? base.name() : item.name();
    Icon icon = icon(exact), overlay = null;
    if (icon == null && base != null) {
      icon = icon(base);
      if (icon != null) {
        String registry =
            "quality_food:"
                + switch (quality) {
                  case 1 -> "iron_overlay";
                  case 2 -> "gold_overlay";
                  case 3 -> "diamond_overlay";
                  default -> throw new IllegalStateException("Invalid display quality");
                };
        overlay = icon(mapping(Resource.group("item", registry), registry, "item"));
      }
    }
    return new Display(name, quality, icon, overlay);
  }

  private AssetManifest.Entry mapping(String id, String registryId, String type) {
    var entry = assets.entry(id);
    return entry != null && entry.registryId().equals(registryId) && entry.type().equals(type)
        ? entry
        : null;
  }

  private Icon icon(AssetManifest.Entry entry) {
    if (entry == null || !assets.hasIcon(entry.id())) return null;
    var page = assets.page(entry.page());
    return new Icon(
        "api/v1/assets/" + entry.page() + ".png",
        entry.x(),
        entry.y(),
        page.width(),
        page.height());
  }
}
