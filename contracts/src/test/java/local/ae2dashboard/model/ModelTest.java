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
}
