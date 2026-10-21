package local.ae2dashboard.assets;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetPackTest {
  @TempDir Path directory;

  @Test
  void missingAndInvalidPacksAreOptional() throws Exception {
    assertEquals("missing", AssetPack.load(directory.resolve("absent.zip")).status());
    Path invalid = directory.resolve("invalid.zip");
    Files.writeString(invalid, "not a zip");
    assertEquals("invalid", AssetPack.load(invalid).status());
  }

  @Test
  void validPackMapsOnlyDisplayMetadata() throws Exception {
    Path path = pack(false, false);
    AssetPack pack = AssetPack.load(path);
    assertEquals("loaded", pack.status());
    assertEquals("铁锭", pack.entry("AAAAAAAAAAAAAAAAAAAAAA").name());
    assertNull(pack.entry("uncovered"));
  }

  @Test
  void hashMismatchAndTraversalAreRejected() throws Exception {
    var partial = AssetPack.load(pack(true, false));
    assertEquals("partial", partial.status());
    assertEquals("铁锭", partial.entry("AAAAAAAAAAAAAAAAAAAAAA").name());
    assertFalse(partial.hasIcon("AAAAAAAAAAAAAAAAAAAAAA"));
    assertEquals("invalid", AssetPack.load(pack(false, true)).status());
  }

  private Path pack(boolean wrongHash, boolean traversal) throws Exception {
    var image = new BufferedImage(68, 68, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(3, 3, 0xffcccccc);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    byte[] png = bytes.toByteArray();
    String hash = AssetPack.sha(png);
    var manifest =
        new AssetManifest(
            1,
            AssetManifest.CONTRACT,
            "1.20.1",
            "47.4.16",
            "15.4.10",
            "zh_cn",
            "2026-10-21T00:00:00Z",
            List.of(),
            List.of(),
            0,
            List.of(
                new AssetManifest.Entry(
                    "AAAAAAAAAAAAAAAAAAAAAA", "minecraft:iron_ingot", "item", "铁锭", hash, 2, 2)),
            List.of(new AssetManifest.Page(hash, 68, 68)));
    Path path = directory.resolve(UUID.randomUUID() + ".zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(new Gson().toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(hash + ".png"));
      zip.write(wrongHash ? new byte[] {1, 2, 3} : png);
      zip.closeEntry();
      if (traversal) {
        zip.putNextEntry(new ZipEntry("../outside"));
        zip.write(1);
        zip.closeEntry();
      }
    }
    return path;
  }
}
