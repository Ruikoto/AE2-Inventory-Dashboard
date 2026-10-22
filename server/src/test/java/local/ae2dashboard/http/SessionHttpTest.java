package local.ae2dashboard.http;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import local.ae2dashboard.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionHttpTest {
  @TempDir Path directory;
  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void persistentCookieSurvivesHttpRestartAndLogoutRemainsRevoked() throws Exception {
    var config = config(true);
    Path file = directory.resolve("sessions.json");
    try (var service = new DashboardService(directory, config)) {
      String cookie;
      try (var http = new DashboardHttp(service, config, file)) {
        http.start();
        var login = send(http, "POST", "session", "", true);
        assertEquals(200, login.statusCode(), login.body());
        String header = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(header.contains("Max-Age=2592000"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains("Secure"));
        cookie = header.split(";", 2)[0];
      }
      try (var http = new DashboardHttp(service, config, file)) {
        http.start();
        assertAuthenticated(send(http, "GET", "status", cookie, true), true);
        var logout = send(http, "DELETE", "session", cookie, true);
        assertEquals(200, logout.statusCode());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
      }
      try (var http = new DashboardHttp(service, config, file)) {
        http.start();
        assertAuthenticated(send(http, "GET", "status", cookie, true), false);
      }
    }
  }

  @Test
  void failedPersistenceDoesNotIssueOrClearCookie() throws Exception {
    var config = config(false);
    Path file = directory.resolve("sessions.json"), backup = directory.resolve("saved.json");
    try (var service = new DashboardService(directory, config);
        var http = new DashboardHttp(service, config, file)) {
      http.start();
      var login = send(http, "POST", "session", "", false);
      String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      assertFalse(login.headers().firstValue("Set-Cookie").orElseThrow().contains("Secure"));
      Files.move(file, backup);
      Files.createDirectory(file);
      Files.writeString(file.resolve("blocker"), "test", StandardCharsets.UTF_8);
      for (String method : new String[] {"POST", "DELETE"}) {
        var failed = send(http, method, "session", cookie, false);
        assertEquals(503, failed.statusCode(), failed.body());
        assertTrue(failed.body().contains("SESSION_STORAGE_UNAVAILABLE"));
        assertTrue(failed.headers().firstValue("Set-Cookie").isEmpty());
        assertAuthenticated(send(http, "GET", "status", cookie, false), true);
      }
      Files.delete(file.resolve("blocker"));
      Files.delete(file);
      Files.move(backup, file);
      assertEquals(200, send(http, "DELETE", "session", cookie, false).statusCode());
      assertAuthenticated(send(http, "GET", "status", cookie, false), false);
    }
  }

  private Config config(boolean secure) {
    return new Config(
        "127.0.0.1", 0, "password", "secret", 10, 64, secure, directory.resolve("missing.zip"), "");
  }

  private HttpResponse<String> send(
      DashboardHttp http, String method, String path, String cookie, boolean secure)
      throws Exception {
    String host = "127.0.0.1:" + http.port();
    var request =
        HttpRequest.newBuilder(URI.create("http://" + host + "/api/v1/" + path))
            .header("Origin", (secure ? "https://" : "http://") + host)
            .header("Content-Type", "application/json");
    if (!cookie.isEmpty()) request.header("Cookie", cookie);
    request.method(
        method,
        method.equals("POST")
            ? HttpRequest.BodyPublishers.ofString("{\"password\":\"secret\"}")
            : HttpRequest.BodyPublishers.noBody());
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertAuthenticated(HttpResponse<String> response, boolean expected) {
    assertEquals(200, response.statusCode());
    assertEquals(
        expected,
        JsonParser.parseString(response.body())
            .getAsJsonObject()
            .get("authenticated")
            .getAsBoolean());
  }
}
