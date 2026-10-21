package local.ae2dashboard.model;

import java.math.*;
import java.util.*;

public final class History {
  private History() {}

  public static final long DAY = 86_400_000L;
  public static final long[] STEPS = {
    10_000,
    60_000,
    300_000,
    600_000,
    900_000,
    1_800_000,
    3_600_000,
    14_400_000,
    28_800_000,
    43_200_000,
    DAY,
    2 * DAY,
    3 * DAY,
    7 * DAY
  };

  public record Point(
      long timestamp,
      String segment,
      String quantity,
      String firstQuantity,
      String min,
      String max,
      long firstAt,
      long lastAt,
      long samples,
      String rate) {}

  public record Series(
      String id, List<Point> points, String delta, String rate, boolean complete) {}

  public record Result(
      long from, long to, long step, long availableFrom, boolean truncated, List<Series> series) {}

  public static String rate(BigInteger delta, long elapsedMs) {
    return elapsedMs <= 0
        ? null
        : new BigDecimal(delta)
            .multiply(BigDecimal.valueOf(60_000))
            .divide(BigDecimal.valueOf(elapsedMs), 6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
  }

  public static long step(long from, long to, long now, long requested) {
    if (from >= to || to > now + 60_000 || to - from > 30 * DAY || from < 0)
      throw new IllegalArgumentException("Invalid history range");
    long minimum = now - from > 7 * DAY ? 900_000 : now - from > DAY ? 60_000 : 10_000;
    if (requested != 0) {
      if (Arrays.stream(STEPS).noneMatch(s -> s == requested)
          || requested < minimum
          || (to - from + requested - 1) / requested > 1200)
        throw new IllegalArgumentException("Unsupported granularity; use auto");
      return requested;
    }
    for (long step : STEPS)
      if (step >= minimum && (to - from + step - 1) / step <= 1200) return step;
    return 7 * DAY;
  }
}
