package local.ae2dashboard.http;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 固定期限的浏览器凭证；只有落盘成功的登录和退出才对外生效。 */
public final class Sessions {
  public static final long LIFETIME_SECONDS = 30L * 24 * 60 * 60;
  private static final long LIFETIME_MILLIS = LIFETIME_SECONDS * 1000;
  private static final int MAX_SESSIONS = 256, MAX_FILE_BYTES = 65536;

  public static final class RateLimited extends IllegalStateException {}

  public static final class StorageFailure extends IOException {
    StorageFailure(IOException cause) {
      super("Cannot persist browser sessions", cause);
    }
  }

  private final byte[] passwordHash;
  private final boolean publicAccess;
  private final Path file;
  private Map<String, Long> sessions = new LinkedHashMap<>();
  private final Map<String, Attempt> attempts = new LinkedHashMap<>();
  private final SecureRandom random = new SecureRandom();

  private record Attempt(long started, int count) {}

  public Sessions(String password, boolean publicAccess, Path file) throws IOException {
    this.passwordHash = hash(password);
    this.publicAccess = publicAccess;
    this.file = file;
    if (publicAccess || !Files.exists(file)) return;
    byte[] bytes;
    try (var in = Files.newInputStream(file)) {
      bytes = in.readNBytes(MAX_FILE_BYTES + 1);
    }
    if (bytes.length > MAX_FILE_BYTES) throw new IOException("Session file exceeds limit");
    String fingerprint;
    try {
      var root =
          JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      if (number(root.get("version")) != 1) throw new IllegalArgumentException();
      fingerprint = digest(root.get("passwordFingerprint"));
      var entries = root.getAsJsonArray("sessions");
      if (entries == null || entries.size() > MAX_SESSIONS) throw new IllegalArgumentException();
      for (var element : entries) {
        var entry = element.getAsJsonObject();
        String tokenHash = digest(entry.get("tokenHash"));
        long expiry = number(entry.get("expiresAt"));
        if (expiry < 0 || sessions.put(tokenHash, expiry) != null)
          throw new IllegalArgumentException();
      }
    } catch (RuntimeException e) {
      throw new IOException("Invalid browser session file; original file preserved", e);
    }
    if (!fingerprint.equals(HexFormat.of().formatHex(passwordHash))) publish(new LinkedHashMap<>());
  }

  public synchronized boolean authorized(String token, long now) {
    if (publicAccess) return true;
    if (!token.matches("[A-Za-z0-9_-]{43}")) return false;
    Long expiry = sessions.get(tokenHash(token));
    return expiry != null && now < expiry;
  }

  public synchronized String login(String address, String password, long now)
      throws StorageFailure {
    attempts.entrySet().removeIf(e -> now - e.getValue().started() > 60_000);
    Attempt old = attempts.getOrDefault(address, new Attempt(now, 0));
    if (old.count() >= 10) throw new RateLimited();
    if (attempts.size() >= 1024 && !attempts.containsKey(address)) throw new RateLimited();
    attempts.put(address, new Attempt(old.started(), old.count() + 1));
    if (!MessageDigest.isEqual(passwordHash, hash(password))) return null;
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    if (publicAccess) return token;
    var candidate = new LinkedHashMap<>(sessions);
    candidate.entrySet().removeIf(e -> now >= e.getValue());
    if (candidate.size() >= MAX_SESSIONS) candidate.remove(candidate.keySet().iterator().next());
    candidate.put(tokenHash(token), Math.addExact(now, LIFETIME_MILLIS));
    publish(candidate);
    return token;
  }

  public synchronized void logout(String token, long now) throws StorageFailure {
    if (publicAccess) return;
    String key = tokenHash(token);
    Long expiry = sessions.get(key);
    if (expiry == null || now >= expiry) return;
    var candidate = new LinkedHashMap<>(sessions);
    candidate.remove(key);
    publish(candidate);
  }

  private void publish(Map<String, Long> candidate) throws StorageFailure {
    var root = new JsonObject();
    root.addProperty("version", 1);
    root.addProperty("passwordFingerprint", HexFormat.of().formatHex(passwordHash));
    var entries = new JsonArray();
    candidate.forEach(
        (key, expiry) -> {
          var entry = new JsonObject();
          entry.addProperty("tokenHash", key);
          entry.addProperty("expiresAt", expiry);
          entries.add(entry);
        });
    root.add("sessions", entries);
    Path temporary = null;
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "sessions-", ".tmp");
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        var bytes = ByteBuffer.wrap(root.toString().getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      sessions = candidate;
    } catch (IOException e) {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
      }
      throw new StorageFailure(e);
    }
  }

  private static long number(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException();
    return value.getAsBigDecimal().longValueExact();
  }

  private static String digest(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException();
    String result = value.getAsString();
    if (!result.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
    return result;
  }

  private static String tokenHash(String token) {
    return HexFormat.of().formatHex(hash(token));
  }

  private static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
