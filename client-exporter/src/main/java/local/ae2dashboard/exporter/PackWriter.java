package local.ae2dashboard.exporter;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import local.ae2dashboard.assets.AssetManifest;
import local.ae2dashboard.io.BoundedOutputStream;

/** One bounded writer owns atlas pages and publishes only a complete archive. */
final class PackWriter {
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          r -> {
            var t = new Thread(r, "ae2-assets-writer");
            t.setDaemon(true);
            return t;
          });
  private final Path directory;
  private final String language;
  private final List<String> packs, mods;
  private final List<AssetManifest.Entry> entries = new ArrayList<>();
  private final List<AssetManifest.Page> pages = new ArrayList<>();
  private final List<AssetManifest.Entry> pending = new ArrayList<>();
  private final CompletableFuture<Path> result = new CompletableFuture<>();
  private volatile boolean cancelled;
  private ZipOutputStream zip;
  private Path temporary;
  private BufferedImage atlas;
  private int cells;
  private long bytes;

  PackWriter(Path directory, String language, List<String> packs, List<String> mods) {
    this.directory = directory;
    this.language = language;
    this.packs = packs;
    this.mods = mods;
  }

  boolean ready() {
    return worker.getQueue().remainingCapacity() > 16 && !result.isCompletedExceptionally();
  }

  void check() {
    if (result.isCompletedExceptionally()) result.join();
  }

  void add(String id, String registry, String type, String name, BufferedImage pixels) {
    worker.execute(
        () -> {
          if (cancelled) return;
          try {
            if (zip == null) {
              Files.createDirectories(directory);
              temporary = Files.createTempFile(directory, "assets-", ".tmp");
              zip =
                  new ZipOutputStream(
                      new BoundedOutputStream(
                          Files.newOutputStream(temporary), 512L * 1024 * 1024));
            }
            if (pixels == null) {
              entries.add(new AssetManifest.Entry(id, registry, type, name, "", 0, 0));
              return;
            }
            if (atlas == null) atlas = new BufferedImage(2040, 2040, BufferedImage.TYPE_INT_ARGB);
            int x = cells % 30 * 68 + 2, y = cells / 30 * 68 + 2;
            int[] rgb = pixels.getRGB(0, 0, 64, 64, null, 0, 64);
            atlas.setRGB(x, y, 64, 64, rgb, 0, 64);
            for (int p = 0; p < 64; p++)
              for (int border = 1; border <= 2; border++) {
                atlas.setRGB(x + p, y - border, rgb[p]);
                atlas.setRGB(x + p, y + 63 + border, rgb[63 * 64 + p]);
                atlas.setRGB(x - border, y + p, rgb[p * 64]);
                atlas.setRGB(x + 63 + border, y + p, rgb[p * 64 + 63]);
              }
            pending.add(new AssetManifest.Entry(id, registry, type, name, "", x, y));
            cells++;
            if (cells == 900) flush();
          } catch (Exception e) {
            fail(e);
          }
        });
  }

  private void flush() throws Exception {
    if (cells == 0) return;
    var output = new ByteArrayOutputStream();
    ImageIO.write(atlas, "PNG", output);
    byte[] png = output.toByteArray();
    bytes += png.length;
    if (bytes > 480L * 1024 * 1024) throw new IOException("Asset pack too large");
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
    if (pages.stream().noneMatch(p -> p.hash().equals(hash))) {
      zip.putNextEntry(new ZipEntry(hash + ".png"));
      zip.write(png);
      zip.closeEntry();
      pages.add(new AssetManifest.Page(hash, 2040, 2040));
    }
    for (var e : pending)
      entries.add(
          new AssetManifest.Entry(e.id(), e.registryId(), e.type(), e.name(), hash, e.x(), e.y()));
    pending.clear();
    cells = 0;
    atlas = null;
  }

  CompletableFuture<Path> finish(int skipped) {
    worker.execute(
        () -> {
          try {
            if (cancelled) throw new IOException("Cancelled");
            if (zip == null) throw new IOException("Nothing exported");
            flush();
            var manifest =
                new AssetManifest(
                    1,
                    AssetManifest.CONTRACT,
                    "1.20.1",
                    versionOf("forge"),
                    versionOf("ae2"),
                    language,
                    Instant.now().toString(),
                    mods,
                    packs,
                    skipped,
                    entries,
                    pages);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            // The archive owns this stream; flushing the encoder must not close the ZIP entry.
            var manifestWriter =
                new BufferedWriter(
                    new OutputStreamWriter(
                        new BoundedOutputStream(zip, 32L * 1024 * 1024), StandardCharsets.UTF_8));
            new Gson().toJson(manifest, manifestWriter);
            manifestWriter.flush();
            zip.closeEntry();
            zip.close();
            zip = null;
            synchronized (result) {
              if (cancelled) throw new IOException("Cancelled");
              Path target = directory.resolve("assets-" + UUID.randomUUID() + ".zip");
              Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
              temporary = null;
              result.complete(target);
            }
          } catch (Exception e) {
            fail(e);
          } finally {
            worker.shutdown();
          }
        });
    return result;
  }

  private String versionOf(String id) {
    return mods.stream()
        .filter(s -> s.startsWith(id + "@"))
        .map(s -> s.substring(id.length() + 1))
        .findFirst()
        .orElse("unknown");
  }

  boolean cancel() {
    synchronized (result) {
      if (result.isDone() && !cancelled) return false;
      cancelled = true;
    }
    worker.getQueue().clear();
    try {
      worker.execute(
          () -> {
            fail(new IOException("Cancelled"));
            worker.shutdown();
          });
    } catch (RejectedExecutionException ignored) {
    }
    return true;
  }

  private void fail(Exception e) {
    cancelled = true;
    result.completeExceptionally(e);
    try {
      if (zip != null) zip.close();
      if (temporary != null) Files.deleteIfExists(temporary);
    } catch (IOException suppressed) {
      e.addSuppressed(suppressed);
    }
    zip = null;
    temporary = null;
    worker.shutdown();
  }
}
