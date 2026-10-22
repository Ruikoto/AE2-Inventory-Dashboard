package local.ae2dashboard.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.ae2dashboard.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CraftingStoreTest {
  @TempDir Path directory;
  private final String network = UUID.randomUUID().toString();
  private final long now = System.currentTimeMillis();

  private Resource resource(String id) {
    return new Resource(
        id, "group:item:test:material", "item", "test:material", "测试材料", "个", 1, id);
  }

  private void populate(HistoryStore store) throws Exception {
    store.saveNetwork(
        new Network(network, now, now, "online", Set.of("overworld 0,0,0")), null, Set.of());
    store.append(
        Snapshot.of(
            network,
            "s",
            now,
            1,
            List.of(
                new Resource.Stored(resource("a"), BigInteger.ZERO, true, false),
                new Resource.Stored(
                    resource("b"), new BigInteger("9007199254740993"), false, true))));
  }

  private Crafting.Order order(String request, long created, String state, long finished) {
    return new Crafting.Order(
        UUID.randomUUID().toString(),
        request,
        network,
        UUID.randomUUID().toString(),
        new Crafting.Item("a", "测试材料", "个", "item", "test:material"),
        "64",
        "",
        "cpu",
        "合成处理器",
        "native",
        state,
        created,
        created,
        finished,
        null,
        "64",
        "0");
  }

  private void downgradeToV1() throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite"));
        var s = c.createStatement()) {
      s.execute("DROP TABLE inventory_capabilities");
      s.execute("DROP TABLE crafting_orders");
      s.execute("PRAGMA user_version=1");
    }
  }

  @Test
  void migratesV1WithoutChangingWorldOrStatistics() throws Exception {
    String world;
    try (var store = new HistoryStore(directory, 64L << 20)) {
      populate(store);
      world = store.worldId();
    }
    downgradeToV1();
    try (var store = new HistoryStore(directory, 64L << 20)) {
      assertEquals(world, store.worldId());
      assertEquals(
          new BigInteger("9007199254740993"),
          store.lastInventory(network, false, "", 0, 10).rows().get(1).quantity());
      assertTrue(store.orders(network, true, 50, "").orders().isEmpty());
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite"));
        var s = c.createStatement();
        var r = s.executeQuery("PRAGMA user_version")) {
      assertTrue(r.next());
      assertEquals(2, r.getInt(1));
    }
  }

  @Test
  void failedMigrationRollsBackAndNewerSchemaIsRejected() throws Exception {
    try (var store = new HistoryStore(directory, 64L << 20)) {
      populate(store);
    }
    downgradeToV1();
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite"));
        var s = c.createStatement()) {
      s.execute("CREATE VIEW crafting_orders AS SELECT 1 AS id");
    }
    assertThrows(SQLException.class, () -> new HistoryStore(directory, 64L << 20));
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite"));
        var s = c.createStatement()) {
      try (var r = s.executeQuery("PRAGMA user_version")) {
        assertTrue(r.next());
        assertEquals(1, r.getInt(1));
      }
      try (var r =
          s.executeQuery(
              "SELECT COUNT(*) FROM sqlite_master WHERE name='inventory_capabilities'")) {
        assertTrue(r.next());
        assertEquals(0, r.getInt(1));
      }
      s.execute("PRAGMA user_version=3");
    }
    assertThrows(SQLException.class, () -> new HistoryStore(directory, 64L << 20));
  }

  @Test
  void completeSamplesPersistAndRemoveCapabilitiesWithoutChangingAmounts() throws Exception {
    try (var store = new HistoryStore(directory, 64L << 20)) {
      populate(store);
    }
    try (var store = new HistoryStore(directory, 64L << 20)) {
      var rows = store.lastInventory(network, false, "", 0, 10).rows();
      assertTrue(rows.get(0).craftable());
      assertEquals(BigInteger.ZERO, rows.get(0).quantity());
      assertTrue(rows.get(1).infinite());
      assertEquals(new BigInteger("9007199254740993"), rows.get(1).quantity());
      var grouped = store.lastInventory(network, true, "", 0, 10).rows().get(0);
      assertTrue(grouped.craftable());
      assertTrue(grouped.infinite());
      assertEquals(1, store.lastObservation(network).craftableResources());
      assertEquals(1, store.lastObservation(network).nonZeroResources());
      store.append(
          Snapshot.of(
              network,
              "new",
              now + 10_000,
              2,
              List.of(new Resource.Stored(resource("b"), BigInteger.ONE))));
      assertTrue(
          store.lastInventory(network, false, "", 0, 10).rows().stream()
              .noneMatch(r -> r.craftable() || r.infinite()));
    }
  }

  @Test
  void zeroStockCraftableSurvivesMaintenance() throws Exception {
    try (var store = new HistoryStore(directory, 64L << 20)) {
      populate(store);
      store.append(
          Snapshot.of(
              network,
              "s",
              now + 10_000,
              2,
              List.of(
                  new Resource.Stored(resource("new-craftable"), BigInteger.ZERO, true, false))));
      store.maintain(now + 61_000);
      assertTrue(
          store.networkResources(network, false, 0, 10).stream()
              .anyMatch(r -> r.id().equals("new-craftable")));
    }
    try (var store = new HistoryStore(directory, 64L << 20)) {
      var row =
          store.lastInventory(network, false, "", 0, 10).rows().stream()
              .filter(r -> r.resource().id().equals("new-craftable"))
              .findFirst()
              .orElseThrow();
      assertTrue(row.craftable());
      assertEquals(BigInteger.ZERO, row.quantity());
    }
  }

  @Test
  void intentIsIdempotentAndTerminalStateCannotRegress() throws Exception {
    try (var store = new HistoryStore(directory, 64L << 20)) {
      populate(store);
      Crafting.Order first = order("same-request", now, "submitting", 0);
      assertEquals(first, store.insertOrderIntent(first));
      assertEquals(first, store.insertOrderIntent(order("same-request", now, "submitting", 0)));
      Crafting.Order done =
          new Crafting.Order(
              first.id(),
              first.requestId(),
              network,
              first.planId(),
              first.item(),
              first.quantity(),
              "",
              "cpu",
              "CPU",
              "job",
              "completed",
              now,
              now + 1000,
              now + 1000,
              null,
              "64",
              "64");
      store.saveOrder(done);
      store.saveOrder(first);
      assertEquals(done, store.findOrderByRequest(network, "same-request"));
      assertNull(store.findOrder(UUID.randomUUID().toString(), first.id()));
      assertTrue(store.activeOrders().isEmpty());
    }
  }

  @Test
  void stablePaginationRetentionAndActiveNetworkProtection() throws Exception {
    try (var store = new HistoryStore(directory, 64L << 20)) {
      populate(store);
      var active = store.insertOrderIntent(order("active", now, "running", 0));
      var one = store.insertOrderIntent(order("one", now, "completed", now));
      var two = store.insertOrderIntent(order("two", now, "cancelled", now));
      var page = store.orders(network, true, 1, "");
      assertNotNull(page.nextCursor());
      var next = store.orders(network, true, 1, page.nextCursor());
      assertEquals(
          Set.of(one.id(), two.id()), Set.of(page.orders().get(0).id(), next.orders().get(0).id()));
      assertEquals("", next.nextCursor());
      store.maintain(now + 31 * History.DAY);
      assertTrue(store.orders(network, true, 50, "").orders().isEmpty());
      assertEquals(active, store.findOrder(network, active.id()));
      assertTrue(store.networks().stream().anyMatch(n -> n.id().equals(network)));
    }
  }
}
