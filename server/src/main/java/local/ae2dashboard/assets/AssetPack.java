package local.ae2dashboard.assets;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Optional display metadata. A failed pack never disables monitoring. */
public final class AssetPack {
  private final AssetManifest manifest;
  private final Map<String, AssetManifest.Entry> entries;
  private final Map<String, byte[]> pages;
  private final Map<String, AssetManifest.Page> metadata;
  private final long missingIcons;
  private final String status;

  private AssetPack(
      AssetManifest manifest,
      Map<String, AssetManifest.Entry> entries,
      Map<String, byte[]> pages,
      String status) {
    this.manifest = manifest;
    this.entries = Map.copyOf(entries);
    this.pages = Map.copyOf(pages);
    Map<String, AssetManifest.Page> sizes = new HashMap<>();
    if (manifest != null)
      for (var page : manifest.pages())
        if (sizes.put(page.hash(), page) != null)
          throw new IllegalArgumentException("Duplicate page metadata");
    this.metadata = Map.copyOf(sizes);
    missingIcons = entries.values().stream().filter(e -> !hasIcon(e.id())).count();
    this.status = status.equals("loaded") && missingIcons > 0 ? "partial" : status;
  }

  public static AssetPack load(Path path) {
    if (!Files.isRegularFile(path)) return new AssetPack(null, Map.of(), Map.of(), "missing");
    try {
      if (Files.size(path) > 512L * 1024 * 1024) throw new IOException("Pack too large");
      try (var zip = new ZipFile(path.toFile())) {
        long total = 0;
        Set<String> names = new HashSet<>();
        for (var e : Collections.list(zip.entries())) {
          if (!names.add(e.getName())
              || e.isDirectory()
              || e.getName().contains("..")
              || e.getName().contains("\\")
              || e.getName().startsWith("/")
              || e.getSize() < 0) throw new IOException("Invalid entry");
          total += e.getSize();
          if (total > 512L * 1024 * 1024 || names.size() > 1024)
            throw new IOException("Expanded pack too large");
        }
        ZipEntry descriptor = zip.getEntry("manifest.json");
        if (descriptor == null || descriptor.getSize() > 32L * 1024 * 1024)
          throw new IOException("Invalid manifest");
        AssetManifest manifest;
        try (var in = zip.getInputStream(descriptor);
            var reader = new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
          manifest = new Gson().fromJson(reader, AssetManifest.class);
        }
        if (manifest == null
            || manifest.formatVersion() != 1
            || !AssetManifest.CONTRACT.equals(manifest.identityContract())
            || !"1.20.1".equals(manifest.minecraftVersion())
            || !"15.4.10".equals(manifest.ae2Version())
            || !"zh_cn".equals(manifest.language())
            || manifest.generatedAt() == null
            || manifest.entries() == null
            || manifest.entries().size() > 100000
            || manifest.pages() == null
            || manifest.pages().size() > 512) throw new IOException("Incompatible manifest");
        Map<String, byte[]> pages = new HashMap<>();
        for (var page : manifest.pages()) {
          if (!page.hash().matches("[a-f0-9]{64}")
              || page.width() < 64
              || page.height() < 64
              || page.width() > 4096
              || page.height() > 4096) throw new IOException("Invalid page");
          ZipEntry entry = zip.getEntry(page.hash() + ".png");
          if (entry == null || entry.getSize() > 32L * 1024 * 1024) continue;
          try {
            byte[] bytes;
            try (var in = zip.getInputStream(entry)) {
              bytes = in.readNBytes(32 * 1024 * 1024 + 1);
            }
            if (bytes.length > 32 * 1024 * 1024 || !sha(bytes).equals(page.hash()))
              throw new IOException("Page hash mismatch");
            try (var image = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
              var readers = ImageIO.getImageReaders(image);
              if (!readers.hasNext()) throw new IOException("Invalid PNG");
              var reader = readers.next();
              try {
                reader.setInput(image);
                if (!reader.getFormatName().equalsIgnoreCase("png")
                    || reader.getWidth(0) != page.width()
                    || reader.getHeight(0) != page.height())
                  throw new IOException("PNG dimensions mismatch");
              } finally {
                reader.dispose();
              }
            }
            pages.put(page.hash(), bytes);
          } catch (IOException | RuntimeException invalidImage) {
            /* Keep valid names when one image cannot be used. */
          }
        }
        Map<String, AssetManifest.Entry> entries = new HashMap<>();
        for (var e : manifest.entries()) {
          if (e.id() == null
              || e.registryId() == null
              || e.type() == null
              || e.page() == null
              || e.name() == null
              || e.name().isBlank()
              || e.name().length() > 4096
              || e.x() < 0
              || e.y() < 0
              || entries.put(e.id(), e) != null) throw new IOException("Invalid mapping");
        }
        return new AssetPack(manifest, entries, pages, "loaded");
      }
    } catch (Exception e) {
      return new AssetPack(null, Map.of(), Map.of(), "invalid");
    }
  }

  public static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public String status() {
    return status;
  }

  public Map<String, Object> summary() {
    return Map.of(
        "status",
        status,
        "entries",
        entries.size(),
        "missingIcons",
        missingIcons,
        "language",
        manifest == null ? "" : manifest.language(),
        "generatedAt",
        manifest == null ? "" : manifest.generatedAt());
  }

  public AssetManifest.Entry entry(String id) {
    return entries.get(id);
  }

  public AssetManifest.Page page(String hash) {
    return metadata.get(hash);
  }

  public boolean hasIcon(String id) {
    var entry = entries.get(id);
    if (entry == null) return false;
    var size = metadata.get(entry.page());
    return size != null
        && pages.containsKey(entry.page())
        && entry.x() >= 0
        && entry.y() >= 0
        && entry.x() <= size.width() - 64
        && entry.y() <= size.height() - 64;
  }

  public byte[] bytes(String hash) {
    return pages.get(hash);
  }
}
