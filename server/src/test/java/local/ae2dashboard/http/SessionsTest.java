package local.ae2dashboard.http;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionsTest {
  @TempDir Path directory;

  private Path file() {
    return directory.resolve("sessions.json");
  }

  private Sessions sessions() throws IOException {
    return new Sessions("secret", false, file());
  }

  @Test
  void fixedThirtyDaysSurvivesIdleAndServerRestart() throws Exception {
    var sessions = sessions();
    assertFalse(sessions.authorized("", 0));
    assertNull(sessions.login("local", "wrong", 0));
    String token = sessions.login("local", "secret", 100);
    assertTrue(sessions.authorized(token, 3_600_100));
    assertTrue(sessions.authorized(token, 43_200_100));
    var restored = sessions();
    long expiry = 100 + Sessions.LIFETIME_SECONDS * 1000;
    assertTrue(restored.authorized(token, expiry - 1));
    assertFalse(restored.authorized(token, expiry));
    String stored = Files.readString(file(), StandardCharsets.UTF_8);
    assertFalse(stored.contains(token));
    assertFalse(stored.contains("secret"));
  }

  @Test
  void logoutAndPasswordChangeRemainRevokedAfterRestart() throws Exception {
    var first = sessions();
    String token = first.login("local", "secret", 0);
    first.logout(token, 1);
    assertFalse(sessions().authorized(token, 2));
    token = first.login("local", "secret", 3);
    var changed = new Sessions("changed", false, file());
    assertFalse(changed.authorized(token, 4));
    String replacement = changed.login("local", "changed", 5);
    var restored = new Sessions("changed", false, file());
    assertFalse(restored.authorized(token, 6));
    assertTrue(restored.authorized(replacement, 6));
  }

  @Test
  void publicModeDoesNotReadOrWriteSessionFile() throws Exception {
    Files.writeString(file(), "broken", StandardCharsets.UTF_8);
    var sessions = new Sessions("secret", true, file());
    assertTrue(sessions.authorized("", 0));
    String token = sessions.login("local", "secret", 0);
    sessions.logout(token, 1);
    assertEquals("broken", Files.readString(file(), StandardCharsets.UTF_8));
  }

  @Test
  void rateLimitRemainsBounded() throws Exception {
    var sessions = sessions();
    for (int i = 0; i < 10; i++) sessions.login("local", "wrong", i);
    assertThrows(Sessions.RateLimited.class, () -> sessions.login("local", "secret", 11));
    assertNotNull(sessions.login("local", "secret", 60_001));
  }

  @Test
  void capacityEvictsOldestOnlyAfterPublication() throws Exception {
    String first = sessions().login("address-0", "secret", 0);
    var full =
        JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
    for (int i = 1; i < 256; i++) {
      var entry = new JsonObject();
      entry.addProperty("tokenHash", String.format("%064x", i));
      entry.addProperty("expiresAt", Sessions.LIFETIME_SECONDS * 1000 + i);
      full.getAsJsonArray("sessions").add(entry);
    }
    Files.writeString(file(), full.toString(), StandardCharsets.UTF_8);
    var sessions = sessions();
    String latest = sessions.login("address-256", "secret", 256);
    assertFalse(sessions.authorized(first, 257));
    assertTrue(sessions().authorized(latest, 257));
    var stored = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
    assertEquals(256, stored.getAsJsonArray("sessions").size());
  }

  @Test
  void damagedUnsupportedOversizedAndDuplicateFilesArePreserved() throws Exception {
    sessions().login("local", "secret", 0);
    String valid = Files.readString(file(), StandardCharsets.UTF_8);
    var duplicate = JsonParser.parseString(valid).getAsJsonObject();
    duplicate
        .getAsJsonArray("sessions")
        .add(duplicate.getAsJsonArray("sessions").get(0).deepCopy());
    var unknown = JsonParser.parseString(valid).getAsJsonObject();
    unknown.addProperty("version", 2);
    var fraction = JsonParser.parseString(valid).getAsJsonObject();
    fraction.getAsJsonArray("sessions").get(0).getAsJsonObject().addProperty("expiresAt", 1.5);
    for (String invalid :
        new String[] {
          "{", unknown.toString(), duplicate.toString(), fraction.toString(), " ".repeat(65537)
        }) {
      Files.writeString(file(), invalid, StandardCharsets.UTF_8);
      assertThrows(IOException.class, this::sessions);
      assertEquals(invalid, Files.readString(file(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void failedLoginOrLogoutPreservesPreviouslyAcknowledgedSessions() throws Exception {
    var sessions = sessions();
    String token = sessions.login("local", "secret", 0);
    Path backup = directory.resolve("saved.json");
    Files.move(file(), backup);
    Files.createDirectory(file());
    Files.writeString(file().resolve("blocker"), "test", StandardCharsets.UTF_8);
    assertThrows(Sessions.StorageFailure.class, () -> sessions.login("local", "secret", 1));
    assertTrue(sessions.authorized(token, 2));
    assertThrows(Sessions.StorageFailure.class, () -> sessions.logout(token, 3));
    assertTrue(sessions.authorized(token, 4));
    Files.delete(file().resolve("blocker"));
    Files.delete(file());
    Files.move(backup, file());
    assertTrue(sessions().authorized(token, 5));
    try (var files = Files.list(directory)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void unknownAndExpiredLogoutNeedsNoPublication() throws Exception {
    var sessions = sessions();
    String token = sessions.login("local", "secret", 0);
    String before = Files.readString(file(), StandardCharsets.UTF_8);
    sessions.logout("unknown", 1);
    sessions.logout(token, Sessions.LIFETIME_SECONDS * 1000);
    assertEquals(before, Files.readString(file(), StandardCharsets.UTF_8));
  }
}
