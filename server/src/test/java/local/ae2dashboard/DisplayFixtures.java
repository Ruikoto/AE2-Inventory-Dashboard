package local.ae2dashboard;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import local.ae2dashboard.assets.*;
import local.ae2dashboard.model.*;

/** 不含真实资源资产的品质显示夹具，供核心HTTP和解析器测试共用。 */
public final class DisplayFixtures {
  private DisplayFixtures() {}

  public static final String REGISTRY = "minecraft:wheat_seeds";

  public static Resource resource(int quality) {
    return new Resource(
        "quality-" + quality,
        Resource.group("item", REGISTRY),
        "item",
        REGISTRY,
        "Wheat Seeds",
        "个",
        1,
        "{id:\"minecraft:wheat_seeds\",\"#c\":\"ae2:i\",tag:{quality_food:{quality:"
            + quality
            + "}}}");
  }

  public static DisplayHint hint(Resource resource) {
    if (resource.id().startsWith("quality-") && resource.identity().contains("quality_food"))
      return new DisplayHint("base", Integer.parseInt(resource.id().substring(8)));
    return DisplayHint.NONE;
  }

  public static Path pack(Path directory) throws Exception {
    var image = new BufferedImage(256, 64, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xff00ff00);
    var encoded = new ByteArrayOutputStream();
    ImageIO.write(image, "png", encoded);
    byte[] png = encoded.toByteArray();
    String hash = AssetPack.sha(png);
    var entries = new ArrayList<AssetManifest.Entry>();
    entries.add(new AssetManifest.Entry("base", REGISTRY, "item", "小麦种子", hash, 0, 0));
    entries.add(
        new AssetManifest.Entry(
            Resource.group("item", REGISTRY), REGISTRY, "item", "小麦种子", hash, 0, 0));
    entries.add(new AssetManifest.Entry("quality-2", REGISTRY, "item", "精确种子", hash, 64, 0));
    entries.add(new AssetManifest.Entry("quality-3", REGISTRY, "item", "钻石种子", "", 0, 0));
    for (String overlay : List.of("iron_overlay", "gold_overlay", "diamond_overlay")) {
      String id = "quality_food:" + overlay;
      entries.add(
          new AssetManifest.Entry(Resource.group("item", id), id, "item", overlay, hash, 128, 0));
    }
    var manifest =
        new AssetManifest(
            1,
            AssetManifest.CONTRACT,
            "1.20.1",
            "47.4.16",
            "15.4.10",
            "zh_cn",
            "2026-10-22T00:00:00Z",
            List.of(),
            List.of(),
            0,
            entries,
            List.of(new AssetManifest.Page(hash, 256, 64)));
    Path path = directory.resolve("display-assets.zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(hash + ".png"));
      zip.write(png);
      zip.closeEntry();
    }
    return path;
  }
}
