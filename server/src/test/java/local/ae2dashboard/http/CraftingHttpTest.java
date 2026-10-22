package local.ae2dashboard.http;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import local.ae2dashboard.*;
import local.ae2dashboard.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CraftingHttpTest {
  @TempDir Path directory;

  private final class Fixture implements AutoCloseable {
    final DashboardService service;
    final DashboardHttp server;
    final TestCraftingBackend backend;
    final HttpClient client = HttpClient.newHttpClient();
    final String network = UUID.randomUUID().toString(), base, api;

    Fixture(String auth, boolean enabled) throws Exception {
      Config config =
          new Config(
              "127.0.0.1",
              0,
              auth,
              "secret",
              10,
              64,
              false,
              directory.resolve("absent.zip"),
              "",
              enabled);
      service = new DashboardService(directory, config);
      service.write(() -> null).get(5, TimeUnit.SECONDS);
      backend = new TestCraftingBackend(service.crafting());
      service.crafting().setBackend(backend);
      long now = System.currentTimeMillis();
      service.network(
          new Network(network, now, now, "online", Set.of("overworld 1,2,3")),
          "discovered",
          Set.of());
      server = new DashboardHttp(service, config);
      server.start();
      base = "http://127.0.0.1:" + server.port();
      api = base + "/api/v1/networks/" + network + "/crafting";
    }

    HttpResponse<String> send(
        String method, String path, String body, String cookie, String origin, String contentType)
        throws Exception {
      var request = HttpRequest.newBuilder(URI.create(path));
      if (!cookie.isEmpty()) request.header("Cookie", cookie);
      if (!origin.isEmpty()) request.header("Origin", origin);
      if (!contentType.isEmpty()) request.header("Content-Type", contentType);
      request.method(
          method,
          body.isEmpty()
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body, String cookie) throws Exception {
      return send("POST", path, body, cookie, base, "application/json");
    }

    String login() throws Exception {
      var result = post(base + "/api/v1/session", "{\"password\":\"secret\"}", "");
      assertEquals(200, result.statusCode());
      return result.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    JsonObject order(String cookie) throws Exception {
      var preview = post(api + "/plans", "{\"resourceId\":\"exact\",\"quantity\":\"64\"}", cookie);
      assertEquals(202, preview.statusCode(), preview.body());
      String plan = json(preview).get("id").getAsString();
      var result =
          post(
              api + "/orders",
              "{\"planId\":\"" + plan + "\",\"requestId\":\"" + UUID.randomUUID() + "\"}",
              cookie);
      assertEquals(202, result.statusCode(), result.body());
      return json(result);
    }

    public void close() {
      server.close();
      service.close();
    }
  }

  @Test
  void publicVisitorsShareOrdersAndCancellationAndHistoryPagination() throws Exception {
    try (var f = new Fixture("public", true)) {
      var status = json(f.send("GET", f.base + "/api/v1/status", "", "", "", ""));
      assertTrue(status.get("craftingEnabled").getAsBoolean());
      assertEquals("ready", status.get("craftingStatus").getAsString());
      JsonObject first = f.order("");
      var otherClient = HttpClient.newHttpClient();
      var response =
          otherClient.send(
              HttpRequest.newBuilder(URI.create(f.api + "/orders")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(
          first.get("id"),
          json(response).getAsJsonArray("orders").get(0).getAsJsonObject().get("id"));
      var cancellation =
          otherClient.send(
              HttpRequest.newBuilder(
                      URI.create(f.api + "/orders/" + first.get("id").getAsString() + "/cancel"))
                  .header("Origin", f.base)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("cancelled", json(cancellation).get("state").getAsString());
      JsonObject second = f.order("");
      assertEquals(
          200,
          f.post(f.api + "/orders/" + second.get("id").getAsString() + "/cancel", "{}", "")
              .statusCode());
      var page = json(f.send("GET", f.api + "/orders?history=true&limit=1", "", "", "", ""));
      assertEquals(1, page.getAsJsonArray("orders").size());
      String cursor = page.get("nextCursor").getAsString();
      assertFalse(cursor.isEmpty());
      var next =
          json(
              f.send(
                  "GET",
                  f.api
                      + "/orders?history=true&limit=1&cursor="
                      + URLEncoder.encode(cursor, StandardCharsets.UTF_8),
                  "",
                  "",
                  "",
                  ""));
      assertEquals(1, next.getAsJsonArray("orders").size());
      assertNotEquals(
          page.getAsJsonArray("orders").get(0).getAsJsonObject().get("id"),
          next.getAsJsonArray("orders").get(0).getAsJsonObject().get("id"));
    }
  }

  @Test
  void passwordSessionsHaveSharedNetworkOrders() throws Exception {
    try (var f = new Fixture("password", true)) {
      assertEquals(401, f.post(f.api + "/plans", "{}", "").statusCode());
      String owner = f.login(), visitor = f.login();
      JsonObject order = f.order(owner);
      String id = order.get("id").getAsString();
      var shared = f.send("GET", f.api + "/orders/" + id, "", visitor, "", "");
      assertEquals(200, shared.statusCode());
      assertEquals(
          "cancelled",
          json(f.post(f.api + "/orders/" + id + "/cancel", "{}", visitor))
              .get("state")
              .getAsString());
    }
  }

  @Test
  void writesRequireOriginJsonAndBoundedValidInput() throws Exception {
    try (var f = new Fixture("public", true)) {
      String body = "{\"resourceId\":\"exact\",\"quantity\":\"1\"}";
      assertEquals(
          403,
          f.send("POST", f.api + "/plans", body, "", "https://evil.invalid", "application/json")
              .statusCode());
      assertEquals(
          403, f.send("POST", f.api + "/plans", body, "", "", "application/json").statusCode());
      assertEquals(
          415, f.send("POST", f.api + "/plans", body, "", f.base, "text/plain").statusCode());
      var preview = json(f.post(f.api + "/plans", body, ""));
      String previewPath = f.api + "/plans/" + preview.get("id").getAsString();
      assertEquals(415, f.send("DELETE", previewPath, "", "", f.base, "").statusCode());
      assertEquals(
          200, f.send("DELETE", previewPath, "", "", f.base, "application/json").statusCode());
      assertEquals(413, f.post(f.api + "/plans", " ".repeat(4097), "").statusCode());
      for (String bad :
          List.of(
              "[]",
              "{}",
              "{\"resourceId\":\"exact\",\"quantity\":1}",
              "{\"resourceId\":\"group:item:test:x\",\"quantity\":\"1\"}"))
        assertEquals(400, f.post(f.api + "/plans", bad, "").statusCode());
      assertEquals(
          405,
          f.post(f.base + "/api/v1/networks/" + f.network + "/inventory", "{}", "").statusCode());
      assertEquals(
          405,
          f.send("GET", f.api + "/orders/" + UUID.randomUUID() + "/cancel", "", "", "", "")
              .statusCode());
      assertEquals(400, f.send("GET", f.api + "/orders?limit=101", "", "", "", "").statusCode());
    }
  }

  @Test
  void disabledConfigurationStillExposesHistory() throws Exception {
    try (var f = new Fixture("public", false)) {
      assertEquals(
          403,
          f.post(f.api + "/plans", "{\"resourceId\":\"exact\",\"quantity\":\"1\"}", "")
              .statusCode());
      assertEquals(
          403,
          f.post(
                  f.api + "/orders",
                  "{\"planId\":\""
                      + UUID.randomUUID()
                      + "\",\"requestId\":\""
                      + UUID.randomUUID()
                      + "\"}",
                  "")
              .statusCode());
      assertEquals(200, f.send("GET", f.api + "/orders?history=true", "", "", "", "").statusCode());
      assertEquals(0, f.backend.submits.get());
    }
  }

  @Test
  void configurationDefaultsAndExplicitDisableAreStable() throws Exception {
    Config original = Config.load(directory);
    assertTrue(original.craftingEnabled());
    Path path = directory.resolve("ae2-dashboard.properties");
    String properties = Files.readString(path, StandardCharsets.ISO_8859_1);
    Files.writeString(
        path,
        properties.replace("craftingEnabled=true", "craftingEnabled=false"),
        StandardCharsets.ISO_8859_1);
    Config disabled = Config.load(directory);
    assertFalse(disabled.craftingEnabled());
    assertEquals(original.password(), disabled.password());
    Files.writeString(
        path,
        properties.replace("craftingEnabled=true", "craftingEnabled=yes"),
        StandardCharsets.ISO_8859_1);
    assertThrows(java.io.IOException.class, () -> Config.load(directory));
  }

  private static JsonObject json(HttpResponse<String> response) {
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }
}
