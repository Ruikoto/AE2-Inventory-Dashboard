package local.ae2dashboard.http;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Sessions {
  public static final class RateLimited extends IllegalStateException {}

  private final byte[] passwordHash;
  private final boolean publicAccess;
  private final Map<String, Session> sessions = new LinkedHashMap<>();
  private final Map<String, Attempt> attempts = new LinkedHashMap<>();

  private record Session(long created, long touched) {}

  private record Attempt(long started, int count) {}

  public Sessions(String password, boolean publicAccess) {
    this.passwordHash = hash(password);
    this.publicAccess = publicAccess;
  }

  public synchronized boolean authorized(String token, long now) {
    if (publicAccess) return true;
    Session s = sessions.get(token);
    if (s == null) return false;
    if (now - s.created() > 43_200_000 || now - s.touched() > 3_600_000) {
      sessions.remove(token);
      return false;
    }
    sessions.put(token, new Session(s.created(), now));
    return true;
  }

  public synchronized String login(String address, String password, long now) {
    attempts.entrySet().removeIf(e -> now - e.getValue().started() > 60_000);
    Attempt old = attempts.getOrDefault(address, new Attempt(now, 0));
    if (old.count() >= 10) throw new RateLimited();
    if (attempts.size() >= 1024 && !attempts.containsKey(address)) throw new RateLimited();
    attempts.put(address, new Attempt(old.started(), old.count() + 1));
    if (!MessageDigest.isEqual(passwordHash, hash(password))) return null;
    sessions
        .entrySet()
        .removeIf(
            e ->
                now - e.getValue().created() > 43_200_000
                    || now - e.getValue().touched() > 3_600_000);
    if (sessions.size() >= 256) sessions.remove(sessions.keySet().iterator().next());
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    sessions.put(token, new Session(now, now));
    return token;
  }

  public synchronized void logout(String token) {
    sessions.remove(token);
  }

  private static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
