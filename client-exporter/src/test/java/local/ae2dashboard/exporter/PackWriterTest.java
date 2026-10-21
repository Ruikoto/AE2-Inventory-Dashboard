package local.ae2dashboard.exporter;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import local.ae2dashboard.assets.AssetManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackWriterTest {
  @TempDir Path directory;

  @Test
  void publishesCompletePackAndNoTemporaryFile() throws Exception {
    var writer = new PackWriter(directory, "zh_cn", List.of("vanilla"), List.of("ae2@15.4.10"));
    var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xff123456);
    writer.add("AAAAAAAAAAAAAAAAAAAAAA", "minecraft:iron_ingot", "item", "铁锭", image);
    Path path = writer.finish(0).get(10, TimeUnit.SECONDS);
    assertFalse(writer.cancel());
    try (var zip = new ZipFile(path.toFile());
        var reader =
            new java.io.InputStreamReader(
                zip.getInputStream(zip.getEntry("manifest.json")),
                java.nio.charset.StandardCharsets.UTF_8)) {
      var manifest = new Gson().fromJson(reader, AssetManifest.class);
      assertEquals(1, manifest.entries().size());
      assertEquals("铁锭", manifest.entries().get(0).name());
      assertNotNull(zip.getEntry(manifest.pages().get(0).hash() + ".png"));
    }
    try (var files = Files.list(directory)) {
      assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp")));
    }
  }

  @Test
  void blankNamesFallBackWithoutChangingIdentityOrIcons() throws Exception {
    var writer = new PackWriter(directory, "zh_cn", List.of("vanilla"), List.of("ae2@15.4.10"));
    var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xff123456);
    String registry = "tetra:modular_sword";
    String group = "group:item:" + registry;
    writer.add("AAAAAAAAAAAAAAAAAAAAAA", registry, "item", "", image);
    writer.add(group, registry, "item", " \u2003 ", image);
    writer.add("BBBBBBBBBBBBBBBBBBBBBB", "tetra:modular_bow", "item", null, null);
    writer.add("CCCCCCCCCCCCCCCCCCCCCC", "minecraft:iron_ingot", "item", "铁锭", null);
    Path path = writer.finish(0).get(10, TimeUnit.SECONDS);
    try (var zip = new ZipFile(path.toFile());
        var reader =
            new java.io.InputStreamReader(
                zip.getInputStream(zip.getEntry("manifest.json")),
                java.nio.charset.StandardCharsets.UTF_8)) {
      var manifest = new Gson().fromJson(reader, AssetManifest.class);
      var entries =
          manifest.entries().stream()
              .collect(java.util.stream.Collectors.toMap(AssetManifest.Entry::id, e -> e));
      assertEquals(
          Set.of(
              "AAAAAAAAAAAAAAAAAAAAAA", group, "BBBBBBBBBBBBBBBBBBBBBB", "CCCCCCCCCCCCCCCCCCCCCC"),
          entries.keySet());
      assertEquals(registry, entries.get("AAAAAAAAAAAAAAAAAAAAAA").name());
      assertEquals(registry, entries.get(group).name());
      assertEquals("tetra:modular_bow", entries.get("BBBBBBBBBBBBBBBBBBBBBB").name());
      assertEquals("铁锭", entries.get("CCCCCCCCCCCCCCCCCCCCCC").name());
      assertTrue(manifest.entries().stream().noneMatch(e -> e.name().isBlank()));
      assertFalse(entries.get(group).page().isEmpty());
      assertEquals(entries.get(group).page(), entries.get("AAAAAAAAAAAAAAAAAAAAAA").page());
      assertNotNull(zip.getEntry(entries.get(group).page() + ".png"));
    }
  }

  @Test
  void failedIconStillExportsItsName() throws Exception {
    var writer =
        new PackWriter(
            directory, "zh_cn", List.of("vanilla"), List.of("ae2@15.4.10", "forge@47.4.16"));
    writer.add("AAAAAAAAAAAAAAAAAAAAAA", "minecraft:iron_ingot", "item", "铁锭", null);
    Path path = writer.finish(1).get(10, TimeUnit.SECONDS);
    try (var zip = new ZipFile(path.toFile());
        var reader =
            new java.io.InputStreamReader(
                zip.getInputStream(zip.getEntry("manifest.json")),
                java.nio.charset.StandardCharsets.UTF_8)) {
      var manifest = new Gson().fromJson(reader, AssetManifest.class);
      assertEquals("铁锭", manifest.entries().get(0).name());
      assertTrue(manifest.pages().isEmpty());
      assertEquals("", manifest.entries().get(0).page());
    }
  }
}
