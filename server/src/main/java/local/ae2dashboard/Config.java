package local.ae2dashboard;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

public record Config(
    String bindAddress,
    int port,
    String authMode,
    String password,
    int sampleIntervalSeconds,
    int historyBudgetMiB,
    boolean secureCookie,
    Path assetPack,
    String publicOrigin,
    boolean craftingEnabled) {
  /** 旧调用方沿用默认开放合成；显式配置使用完整构造函数。 */
  public Config(
      String bindAddress,
      int port,
      String authMode,
      String password,
      int sampleIntervalSeconds,
      int historyBudgetMiB,
      boolean secureCookie,
      Path assetPack,
      String publicOrigin) {
    this(
        bindAddress,
        port,
        authMode,
        password,
        sampleIntervalSeconds,
        historyBudgetMiB,
        secureCookie,
        assetPack,
        publicOrigin,
        true);
  }

  public static Config load(Path directory) throws IOException {
    Files.createDirectories(directory);
    Path path = directory.resolve("ae2-dashboard.properties");
    Properties p = new Properties();
    if (Files.exists(path))
      try (var input = Files.newInputStream(path)) {
        p.load(input);
      }
    p.putIfAbsent("bindAddress", "0.0.0.0");
    p.putIfAbsent("port", "18766");
    p.putIfAbsent("authMode", "password");
    p.putIfAbsent("sampleIntervalSeconds", "10");
    p.putIfAbsent("historyBudgetMiB", "2048");
    p.putIfAbsent("secureCookie", "false");
    p.putIfAbsent("assetPack", "ae2-dashboard-assets.zip");
    p.putIfAbsent("publicOrigin", "");
    p.putIfAbsent("craftingEnabled", "true");
    if (p.getProperty("password", "").isBlank()) {
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      p.setProperty("password", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
    String mode = p.getProperty("authMode");
    if (!Set.of("password", "public").contains(mode)) throw new IOException("Invalid authMode");
    int port = integer(p, "port", 1, 65535),
        interval = integer(p, "sampleIntervalSeconds", 2, 3600),
        budget = integer(p, "historyBudgetMiB", 16, 65536);
    boolean secure = Boolean.parseBoolean(p.getProperty("secureCookie"));
    String crafting = p.getProperty("craftingEnabled").trim();
    if (!Set.of("true", "false").contains(crafting))
      throw new IOException("Invalid craftingEnabled");
    String origin = p.getProperty("publicOrigin").trim();
    if (!origin.isEmpty() && !origin.matches("https?://[A-Za-z0-9.\\-\\[\\]:]+"))
      throw new IOException("Invalid publicOrigin");
    Path temporary = Files.createTempFile(directory, "dashboard-config-", ".tmp");
    try {
      try (var out = Files.newOutputStream(temporary)) {
        p.store(out, "AE Dashboard - restart after configuration changes. Do not share this file.");
      }
      try {
        Files.move(
            temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
    Path asset = Path.of(p.getProperty("assetPack"));
    if (!asset.isAbsolute()) asset = directory.resolve(asset);
    return new Config(
        p.getProperty("bindAddress"),
        port,
        mode,
        p.getProperty("password"),
        interval,
        budget,
        secure,
        asset,
        origin,
        Boolean.parseBoolean(crafting));
  }

  private static int integer(Properties p, String key, int min, int max) throws IOException {
    try {
      int n = Integer.parseInt(p.getProperty(key));
      if (n < min || n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new IOException("Invalid " + key);
    }
  }
}
