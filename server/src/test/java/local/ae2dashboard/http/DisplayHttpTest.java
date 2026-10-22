package local.ae2dashboard.http;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import local.ae2dashboard.*;
import local.ae2dashboard.model.*;
import local.ae2dashboard.storage.HistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisplayHttpTest {
  @TempDir Path directory;

  @Test
  void restartedOfflineInventoryLegacyOrdersAndPlanUseOneDisplayResolver() throws Exception {
    long now = System.currentTimeMillis();
    String network = UUID.randomUUID().toString(), orderId = UUID.randomUUID().toString();
    var silver = DisplayFixtures.resource(1);
    var ordinary =
        new Resource(
            "plain",
            "group:item:test:plain",
            "item",
            "test:plain",
            "Plain",
            "个",
            1,
            "{id:\"test:plain\"}");
    var oldItem =
        new Crafting.Item(
            silver.id(), silver.name(), silver.unit(), silver.type(), silver.registryId());
    try (var store = new HistoryStore(directory.resolve("ae2-dashboard"), 64 * 1024 * 1024)) {
      store.saveNetwork(new Network(network, now, now, "offline", Set.of("test")), null, Set.of());
      store.append(
          Snapshot.of(
              network,
              "segment",
              now,
              1,
              List.of(
                  new Resource.Stored(silver, BigInteger.valueOf(15)),
                  new Resource.Stored(ordinary, BigInteger.ONE))));
      store.insertOrderIntent(
          new Crafting.Order(
              orderId,
              UUID.randomUUID().toString(),
              network,
              UUID.randomUUID().toString(),
              oldItem,
              "15",
              "",
              "cpu",
              "CPU",
              UUID.randomUUID().toString(),
              "completed",
              now,
              now,
              now,
              "",
              "10",
              "10"));
      var page = store.resources(0, 100);
      assertEquals(
          silver.identity(),
          page.stream()
              .filter(r -> r.id().equals(silver.id()))
              .findFirst()
              .orElseThrow()
              .identity());
      assertEquals(
          "",
          page.stream()
              .filter(r -> r.id().equals(ordinary.id()))
              .findFirst()
              .orElseThrow()
              .identity());
      assertEquals(
          silver.identity(),
          store.networkResources(network, false, 0, 100).stream()
              .filter(r -> r.id().equals(silver.id()))
              .findFirst()
              .orElseThrow()
              .identity());
    }
    var config =
        new Config(
            "127.0.0.1", 0, "public", "secret", 10, 64, false, DisplayFixtures.pack(directory), "");
    try (var service = new DashboardService(directory, config, DisplayFixtures::hint);
        var http = new DashboardHttp(service, config, directory.resolve("sessions.json"))) {
      service.write(() -> null).get(5, TimeUnit.SECONDS);
      var backend = new TestCraftingBackend(service.crafting());
      service.crafting().setBackend(backend);
      String planId = UUID.randomUUID().toString();
      var item = service.presentation().item(silver);
      backend.plans.put(
          planId,
          new Crafting.Plan(
              planId,
              network,
              item,
              "15",
              "ready",
              "128",
              List.of(new Crafting.Material(item, "3", "12", "0")),
              "",
              now));
      http.start();
      String base = "http://127.0.0.1:" + http.port() + "/api/v1/";
      var inventory = get(base + "networks/" + network + "/inventory?merged=false");
      assertTrue(inventory.get("stale").getAsBoolean());
      JsonObject row = null;
      for (var entry : inventory.getAsJsonArray("rows"))
        if (entry.getAsJsonObject().get("id").getAsString().equals(silver.id()))
          row = entry.getAsJsonObject();
      assertNotNull(row);
      assertSilver(row);
      assertEquals("15", row.get("quantity").getAsString());
      var orders = get(base + "networks/" + network + "/crafting/orders?history=true");
      assertSilver(
          orders.getAsJsonArray("orders").get(0).getAsJsonObject().getAsJsonObject("item"));
      assertSilver(
          get(base + "networks/" + network + "/crafting/orders/" + orderId)
              .getAsJsonObject("item"));
      var plan = get(base + "networks/" + network + "/crafting/plans/" + planId);
      assertSilver(plan.getAsJsonObject("item"));
      assertSilver(
          plan.getAsJsonArray("materials").get(0).getAsJsonObject().getAsJsonObject("item"));
      assertFalse(inventory.toString().contains("quality_food"));
      assertFalse(orders.toString().contains("baseDisplayId"));
      assertFalse(plan.toString().contains("baseDisplayId"));
      assertFalse(plan.toString().contains("identity"));
    }
  }

  private void assertSilver(JsonObject item) {
    assertEquals("小麦种子", item.get("name").getAsString());
    assertEquals(1, item.get("quality").getAsInt());
    assertTrue(item.has("icon"));
    assertTrue(item.has("overlay"));
    assertFalse(item.has("identity"));
  }

  private JsonObject get(String url) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }
}
