package local.ae2dashboard.model;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelTest {
  @Test
  void groupsWithoutLosingLargeIntegers() {
    var resource = new Resource("a", "group:item:test:x", "item", "test:x", "x", "个", 1, "nbt:a");
    var other = new Resource("b", resource.groupId(), "item", "test:x", "x", "个", 1, "nbt:b");
    BigInteger amount = BigInteger.valueOf(Long.MAX_VALUE);
    var snapshot =
        Snapshot.of(
            "n",
            "s",
            1,
            1,
            List.of(new Resource.Stored(resource, amount), new Resource.Stored(other, amount)));
    assertEquals(
        amount.multiply(BigInteger.TWO), snapshot.values().get(resource.groupId()).quantity());
  }

  @Test
  void splitAssignmentIsDeterministic() {
    var old = new Network("old", 1, 1, "online", Set.of("a", "b", "c"));
    var matches =
        NetworkIdentity.match(Map.of("x", Set.of("a"), "y", Set.of("b", "c")), List.of(old), 10);
    assertEquals(
        "old",
        matches.stream()
            .filter(m -> m.current().equals("y"))
            .findFirst()
            .orElseThrow()
            .network()
            .id());
    assertEquals(2, matches.stream().map(m -> m.network().id()).distinct().count());
  }

  @Test
  void granularityDoesNotInventOldPrecision() {
    long now = 40 * History.DAY;
    assertThrows(
        IllegalArgumentException.class,
        () -> History.step(now - 8 * History.DAY, now, now, 10_000));
    assertTrue(History.step(now - 30 * History.DAY, now, now, 0) >= 900_000);
    assertEquals("120", History.rate(BigInteger.valueOf(20), 10_000));
  }

  @Test
  void displayedRateRetainsAdjacentPointSemanticsWhileSmoothingUsesRawObservations() {
    List<History.Point> points = new ArrayList<>();
    long[] times = {0, 10000, 20000, 60000, 70000};
    String[] quantities = {"0", "50", "0", "100", "50"};
    for (int i = 0; i < times.length; i++)
      points.add(
          new History.Point(
              times[i],
              "s",
              quantities[i],
              quantities[i],
              quantities[i],
              quantities[i],
              times[i],
              times[i],
              1,
              null));
    var fine = History.summarize("r", points, 0, 70000, 10000, 10000, 300000);
    var coarse = History.summarize("r", points, 0, 70000, 60000, 10000, 300000);
    assertEquals(2, coarse.points().size());
    assertNull(coarse.points().get(0).rate());
    assertEquals("60", coarse.points().get(1).rate());
    assertEquals("42.857143", coarse.points().get(1).smoothedRate());
    assertEquals(fine.points().get(4).smoothedRate(), coarse.points().get(1).smoothedRate());
    assertEquals(fine.delta(), coarse.delta());
    points.add(new History.Point(80000, "next", "200", "200", "200", "200", 80000, 80000, 1, null));
    var resumed = History.summarize("r", points, 0, 80000, 60000, 10000, 300000);
    assertNull(resumed.delta());
    assertNull(resumed.points().get(2).rate());
    assertNull(resumed.points().get(2).smoothedRate());
  }

  @Test
  void lookbackSmoothsTheVisibleBoundaryWithoutChangingItsStatistics() {
    List<History.Point> points = new ArrayList<>();
    for (int i = 0; i <= 90; i++) {
      long at = i * 10000L;
      String quantity = "" + (i * 10 + (i == 90 ? 100 : 0));
      points.add(
          new History.Point(at, "s", quantity, quantity, quantity, quantity, at, at, 1, null));
    }
    long[] windows = {60000, 300000, 900000};
    String[] rates = {"160", "80", "66.666667"};
    for (int i = 0; i < windows.length; i++) {
      var narrow = History.summarize("r", points, 890000, 900000, 10000, 10000, windows[i]);
      var wide = History.summarize("r", points, 0, 900000, 10000, 10000, windows[i]);
      assertEquals(2, narrow.points().size());
      assertEquals("60", narrow.points().get(0).smoothedRate());
      assertEquals(rates[i], narrow.points().get(1).smoothedRate());
      assertEquals(wide.points().get(90).smoothedRate(), narrow.points().get(1).smoothedRate());
      assertNull(narrow.points().get(0).rate());
      assertEquals("110", narrow.delta());
      assertEquals("660", narrow.rate());
      assertEquals(890000, narrow.statisticsFrom());
      assertEquals(900000, narrow.statisticsTo());
      assertEquals(2, narrow.statisticsSamples());
      assertTrue(narrow.complete());
    }
    var single = History.summarize("r", points, 900000, 910000, 10000, 10000, 300000);
    assertEquals("80", single.points().get(0).smoothedRate());
    assertNull(single.points().get(0).rate());
    assertNull(single.delta());
    assertNull(single.rate());
    assertEquals(1, single.statisticsSamples());
    assertEquals("insufficient", single.statisticsScope());
  }

  @Test
  void hiddenObservationsDoNotLeakIntoOneDisplayBucketOrFillANewSegment() {
    var source =
        List.of(
            new History.Point(0, "old", "9000", "9000", "9000", "9000", 0, 0, 1, null),
            new History.Point(10000, "new", "10", "10", "10", "10", 10000, 10000, 1, null),
            new History.Point(20000, "new", "20", "20", "20", "20", 20000, 20000, 1, null),
            new History.Point(30000, "new", "30", "30", "30", "30", 30000, 30000, 1, null));
    var single = History.summarize("r", source, 10000, 15000, 60000, 10000, 300000);
    assertNull(single.points().get(0).smoothedRate());
    assertNull(single.delta());
    var bucket = History.summarize("r", source, 20000, 30000, 60000, 10000, 300000);
    assertEquals(1, bucket.points().size());
    var point = bucket.points().get(0);
    assertEquals("20", point.firstQuantity());
    assertEquals("20", point.min());
    assertEquals("30", point.max());
    assertEquals(2, point.samples());
    assertEquals("60", point.smoothedRate());
    assertNull(point.rate());
    assertEquals("10", bucket.delta());
    assertEquals(20000, bucket.statisticsFrom());
    assertEquals(2, bucket.statisticsSamples());
  }
}
