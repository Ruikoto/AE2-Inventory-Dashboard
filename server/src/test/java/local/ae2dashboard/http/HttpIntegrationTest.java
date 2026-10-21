package local.ae2dashboard.http;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import local.ae2dashboard.*;
import local.ae2dashboard.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HttpIntegrationTest {
  @TempDir Path directory;

  @Test
  void noPackAuthenticationReadOnlyAndExactInventory() throws Exception {
    Config config =
        new Config(
            "127.0.0.1",
            0,
            "password",
            "secret",
            10,
            64,
            false,
            directory.resolve("absent.zip"),
            "");
    try (var service = new DashboardService(directory, config);
        var server = new DashboardHttp(service, config)) {
      server.start();
      String base = "http://127.0.0.1:" + server.port();
      var client = HttpClient.newHttpClient();
      assertEquals(401, get(client, base + "/api/v1/networks", "").statusCode());
      for (String malformed : List.of("[]", "{}", "{\"password\":{}}")) {
        var invalid =
            HttpRequest.newBuilder(URI.create(base + "/api/v1/session"))
                .header("Origin", base)
                .POST(HttpRequest.BodyPublishers.ofString(malformed))
                .build();
        assertEquals(400, client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
      }
      var badOrigin =
          HttpRequest.newBuilder(URI.create(base + "/api/v1/session"))
              .header("Origin", "https://evil.invalid")
              .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"secret\"}"))
              .build();
      assertEquals(403, client.send(badOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
      var login =
          HttpRequest.newBuilder(URI.create(base + "/api/v1/session"))
              .header("Origin", base)
              .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"secret\"}"))
              .build();
      var logged = client.send(login, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, logged.statusCode());
      String cookie = logged.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
      assertFalse(logged.body().contains("secret"));
      String id = UUID.randomUUID().toString();
      long now = System.currentTimeMillis();
      service.network(
          new Network(id, now, now, "online", Set.of("overworld 1,2,3")), "discovered", Set.of());
      Resource r =
          new Resource(
              "exact",
              "group:item:minecraft:iron_ingot",
              "item",
              "minecraft:iron_ingot",
              "minecraft:iron_ingot",
              "个",
              1,
              "{id:\"minecraft:iron_ingot\"}");
      service.sample(
          Snapshot.of(
              id,
              "s",
              now,
              1,
              List.of(new Resource.Stored(r, new BigInteger("9007199254740993")))));
      var inventory = get(client, base + "/api/v1/networks/" + id + "/inventory", cookie);
      assertEquals(200, inventory.statusCode());
      assertTrue(inventory.body().contains("\"quantity\":\"9007199254740993\""));
      assertFalse(inventory.body().contains("identity"));
      assertTrue(get(client, base + "/api/v1/assets/manifest", cookie).body().contains("missing"));
      var write =
          HttpRequest.newBuilder(URI.create(base + "/api/v1/networks/" + id + "/inventory"))
              .header("Cookie", cookie)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      assertEquals(405, client.send(write, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(
          400,
          get(client, base + "/api/v1/networks/" + id + "/history?ids=exact&step=13", cookie)
              .statusCode());
    }
  }

  @Test
  void configurationGeneratesPasswordButDoesNotOverwriteIt() throws Exception {
    Config first = Config.load(directory);
    Config second = Config.load(directory);
    assertEquals(43, first.password().length());
    assertEquals(first.password(), second.password());
    assertEquals("password", second.authMode());
  }

  private static HttpResponse<String> get(HttpClient client, String url, String cookie)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(url));
    if (!cookie.isEmpty()) builder.header("Cookie", cookie);
    return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
