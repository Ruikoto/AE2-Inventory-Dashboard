package local.ae2dashboard.http;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import local.ae2dashboard.*;
import local.ae2dashboard.assets.*;
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
      assertEquals(
          401, get(client, base + "/api/v1/networks/" + id + "/statistics", "").statusCode());
      assertTrue(get(client, base + "/api/v1/assets/manifest", cookie).body().contains("missing"));
      var write =
          HttpRequest.newBuilder(URI.create(base + "/api/v1/networks/" + id + "/inventory"))
              .header("Cookie", cookie)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      assertEquals(405, client.send(write, HttpResponse.BodyHandlers.ofString()).statusCode());
      var writeStatistics =
          HttpRequest.newBuilder(URI.create(base + "/api/v1/networks/" + id + "/statistics"))
              .header("Cookie", cookie)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      assertEquals(
          405, client.send(writeStatistics, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(
          400,
          get(client, base + "/api/v1/networks/" + id + "/history?ids=exact&step=13", cookie)
              .statusCode());
    }
  }

  @Test
  void statisticsRejectChangedPagesAndInvalidSmoothing() throws Exception {
    Config config =
        new Config(
            "127.0.0.1", 0, "public", "secret", 10, 64, false, directory.resolve("absent.zip"), "");
    try (var service = new DashboardService(directory, config);
        var server = new DashboardHttp(service, config)) {
      server.start();
      String id = UUID.randomUUID().toString();
      long now = System.currentTimeMillis();
      service.network(
          new Network(id, now - 60000, now, "online", Set.of("overworld 0,0,0")),
          "discovered",
          Set.of());
      Resource resource =
          new Resource("exact", "group:item:test:x", "item", "test:x", "测试", "个", 1, "{id:test:x}");
      service.sample(
          Snapshot.of(
              id, "s", now - 20000, 1, List.of(new Resource.Stored(resource, BigInteger.TEN))));
      service.sample(
          Snapshot.of(
              id,
              "s",
              now - 10000,
              2,
              List.of(new Resource.Stored(resource, BigInteger.valueOf(20)))));
      service.exportKeys(directory).get(10, TimeUnit.SECONDS);
      var client = HttpClient.newHttpClient();
      String base = "http://127.0.0.1:" + server.port() + "/api/v1/networks/" + id;
      String range = "from=" + (now - 60000) + "&to=" + now;
      var page = get(client, base + "/statistics?" + range + "&limit=1", "");
      assertEquals(200, page.statusCode());
      var json = JsonParser.parseString(page.body()).getAsJsonObject();
      assertEquals(
          "10", json.getAsJsonArray("rows").get(0).getAsJsonObject().get("delta").getAsString());
      long version = json.get("version").getAsLong();
      assertEquals(
          400,
          get(client, base + "/history?ids=exact&" + range + "&rateWindowMs=15000", "")
              .statusCode());
      assertEquals(
          400, get(client, base + "/statistics?" + range + "&limit=2001", "").statusCode());
      service.sample(
          Snapshot.of(
              id, "s", now, 3, List.of(new Resource.Stored(resource, BigInteger.valueOf(30)))));
      service.exportKeys(directory).get(10, TimeUnit.SECONDS);
      var stale = get(client, base + "/statistics?" + range + "&version=" + version, "");
      assertEquals(409, stale.statusCode());
      assertEquals(
          "SNAPSHOT_CHANGED",
          JsonParser.parseString(stale.body()).getAsJsonObject().get("error").getAsString());
    }
  }

  @Test
  void validatedAtlasAboveJsonResponseLimitCanBeServed() throws Exception {
    var image = new BufferedImage(2100, 2100, BufferedImage.TYPE_INT_ARGB);
    int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    var random = new Random(17);
    for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
    var encoded = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "PNG", encoded));
    byte[] png = encoded.toByteArray();
    assertTrue(png.length > 16 * 1024 * 1024);
    assertTrue(png.length <= 32 * 1024 * 1024);
    String hash = AssetPack.sha(png);
    var manifest =
        new AssetManifest(
            1,
            AssetManifest.CONTRACT,
            "1.20.1",
            "47.4.16",
            "15.4.10",
            "zh_cn",
            "2026-10-21T00:00:00Z",
            List.of(),
            List.of(),
            0,
            List.of(new AssetManifest.Entry("exact", "test:x", "item", "测试", hash, 0, 0)),
            List.of(new AssetManifest.Page(hash, 2100, 2100)));
    Path pack = directory.resolve("assets.zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(pack))) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(new Gson().toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(hash + ".png"));
      zip.write(png);
      zip.closeEntry();
    }
    Config config = new Config("127.0.0.1", 0, "public", "secret", 10, 64, false, pack, "");
    try (var service = new DashboardService(directory, config);
        var server = new DashboardHttp(service, config)) {
      assertEquals("loaded", service.assets().status());
      assertTrue(service.assets().hasIcon("exact"));
      server.start();
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:" + server.port() + "/api/v1/assets/" + hash + ".png"))
              .GET()
              .build();
      var response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(png, response.body());
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
