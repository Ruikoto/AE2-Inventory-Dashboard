package local.ae2dashboard;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import local.ae2dashboard.http.DashboardHttp;
import local.ae2dashboard.model.*;

/** Deterministic local fixture, never installed in a server release. */
public final class LocalHarness {
  public static void main(String[] args) throws Exception {
    Path world = Path.of(args.length == 0 ? ".local/demo" : args[0]);
    Config config =
        new Config(
            "127.0.0.1", 18766, "public", "", 10, 64, false, world.resolve("missing.zip"), "");
    var service = new DashboardService(world, config);
    var http = new DashboardHttp(service, config);
    http.start();
    String id = "8b7cc408-99b6-450f-ab76-d895c078f73c";
    long now = System.currentTimeMillis();
    service.network(
        new Network(id, now - 3_600_000, now, "online", Set.of("minecraft:overworld 128,64,-240")),
        "discovered",
        Set.of());
    String[] names = {
      "minecraft:iron_ingot",
      "minecraft:copper_ingot",
      "minecraft:gold_ingot",
      "minecraft:redstone",
      "create:andesite_alloy",
      "minecraft:quartz",
      "minecraft:diamond",
      "create:precision_mechanism",
      "ae2:engineering_processor",
      "minecraft:water"
    };
    long start = now - 3_600_000;
    for (int tick = 0; tick <= 360; tick++) {
      List<Resource.Stored> values = new ArrayList<>();
      for (int i = 0; i < names.length; i++) {
        String type = i == 9 ? "fluid" : "item";
        var r =
            new Resource(
                "fixture-" + i,
                Resource.group(type, names[i]),
                type,
                names[i],
                names[i],
                i == 9 ? "mB" : "个",
                i == 9 ? 1000 : 1,
                "{id:\"" + names[i] + "\"}");
        values.add(
            new Resource.Stored(
                r,
                BigInteger.valueOf(
                    500000 + i * 10000L + tick * 17L + Math.round(Math.sin(tick / 12.0) * 5000))));
      }
      var snapshot = Snapshot.of(id, "fixture", start + tick * 10000L, tick, values);
      // Seed directly before the HTTP fixture starts changing; wait for each queued write to drain.
      service.sample(snapshot);
      Thread.sleep(12);
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  http.close();
                  service.close();
                }));
    System.out.println("Local fixture http://127.0.0.1:18766 (no asset pack)");
    new CountDownLatch(1).await();
  }
}
