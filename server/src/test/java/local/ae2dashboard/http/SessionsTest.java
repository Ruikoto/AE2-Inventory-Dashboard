package local.ae2dashboard.http;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SessionsTest {
  @Test
  void passwordsIdleAndAbsoluteExpiry() {
    var sessions = new Sessions("secret", false);
    assertFalse(sessions.authorized("", 0));
    assertNull(sessions.login("local", "wrong", 0));
    String token = sessions.login("local", "secret", 0);
    assertTrue(sessions.authorized(token, 10));
    assertFalse(sessions.authorized(token, 3_600_011));
    token = sessions.login("local", "secret", 100);
    assertFalse(sessions.authorized(token, 43_200_101));
  }

  @Test
  void publicAndLogout() {
    assertTrue(new Sessions("secret", true).authorized("", 0));
    var sessions = new Sessions("secret", false);
    String token = sessions.login("local", "secret", 0);
    sessions.logout(token);
    assertFalse(sessions.authorized(token, 1));
  }

  @Test
  void rateLimitIsBounded() {
    var sessions = new Sessions("secret", false);
    for (int i = 0; i < 10; i++) sessions.login("local", "wrong", i);
    assertThrows(IllegalStateException.class, () -> sessions.login("local", "secret", 11));
    assertNotNull(sessions.login("local", "secret", 60_001));
  }
}
