package local.ae2dashboard.model;

import java.math.*;
import java.util.*;

public final class History {
  private History() {}

  public static final long DAY = 86_400_000L;

  public static final class SnapshotChanged extends RuntimeException {}

  public static final class InvalidGranularity extends IllegalArgumentException {
    public final long suggestedStep;

    public InvalidGranularity(long suggestedStep) {
      super("Unsupported granularity");
      this.suggestedStep = suggestedStep;
    }
  }

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
      String rate,
      String smoothedRate,
      long rateWindowMs) {
    public Point(
        long timestamp,
        String segment,
        String quantity,
        String firstQuantity,
        String min,
        String max,
        long firstAt,
        long lastAt,
        long samples,
        String rate) {
      this(
          timestamp,
          segment,
          quantity,
          firstQuantity,
          min,
          max,
          firstAt,
          lastAt,
          samples,
          rate,
          null,
          0);
    }
  }

  public record Series(
      String id,
      List<Point> points,
      String delta,
      String rate,
      boolean complete,
      long statisticsFrom,
      long statisticsTo,
      long statisticsSamples,
      String statisticsScope) {
    public Series(String id, List<Point> points, String delta, String rate, boolean complete) {
      this(id, points, delta, rate, complete, 0, 0, 0, "insufficient");
    }
  }

  public record Statistics(
      String id,
      String delta,
      String rate,
      boolean complete,
      long statisticsFrom,
      long statisticsTo,
      long statisticsSamples,
      String statisticsScope) {}

  public record StatisticsPage(long version, int total, List<Statistics> rows) {}

  public record Result(
      long from, long to, long step, long availableFrom, boolean truncated, List<Series> series) {}

  public static Series summarize(
      String id,
      List<Point> source,
      long from,
      long to,
      long step,
      long tier,
      long requestedWindow) {
    if (source.isEmpty()) return new Series(id, List.of(), null, null, false);
    Point first = source.get(source.size() - 1), end = first;
    long count = 0;
    for (int i = source.size() - 1; i >= 0; i--) {
      Point p = source.get(i);
      if (!p.segment().equals(end.segment())) break;
      first = p;
      count += p.samples();
    }
    boolean complete =
        first == source.get(0)
            && count > 1
            && first.firstAt() <= from + Math.max(tier, 10_000)
            && end.lastAt() >= to - Math.max(tier, 30_000);
    String delta =
        count > 1 && end.lastAt() > first.firstAt()
            ? new BigInteger(end.quantity())
                .subtract(new BigInteger(first.firstQuantity()))
                .toString()
            : null;
    String average =
        delta == null ? null : rate(new BigInteger(delta), end.lastAt() - first.firstAt());
    long window = Math.max(tier, requestedWindow);
    List<Point> display = new ArrayList<>();
    int left = 0;
    Point previous = null;
    for (int i = 0; i < source.size(); i++) {
      Point p = source.get(i);
      if (previous == null || !previous.segment().equals(p.segment())) left = i;
      while (left < i && source.get(left).firstAt() < p.lastAt() - window) left++;
      Point a = source.get(left);
      String smooth =
          p.lastAt() > a.firstAt()
              ? rate(
                  new BigInteger(p.quantity()).subtract(new BigInteger(a.firstQuantity())),
                  p.lastAt() - a.firstAt())
              : null;
      Point next =
          new Point(
              p.timestamp() / step * step,
              p.segment(),
              p.quantity(),
              p.firstQuantity(),
              p.min(),
              p.max(),
              p.firstAt(),
              p.lastAt(),
              p.samples(),
              null,
              smooth,
              window);
      if (!display.isEmpty()) {
        Point old = display.get(display.size() - 1);
        if (old.timestamp() == next.timestamp() && old.segment().equals(next.segment())) {
          next =
              new Point(
                  old.timestamp(),
                  old.segment(),
                  next.quantity(),
                  old.firstQuantity(),
                  new BigInteger(old.min()).min(new BigInteger(next.min())).toString(),
                  new BigInteger(old.max()).max(new BigInteger(next.max())).toString(),
                  old.firstAt(),
                  next.lastAt(),
                  old.samples() + next.samples(),
                  null,
                  smooth,
                  window);
          display.remove(display.size() - 1);
        }
      }
      display.add(next);
      previous = p;
    }
    if (display.size() > 1200)
      throw new IllegalArgumentException("Too many continuous segments; narrow the history range");
    previous = null;
    for (int i = 0; i < display.size(); i++) {
      Point p = display.get(i);
      String adjacentRate =
          previous != null && previous.segment().equals(p.segment())
              ? rate(
                  new BigInteger(p.quantity()).subtract(new BigInteger(previous.quantity())),
                  p.lastAt() - previous.lastAt())
              : null;
      display.set(
          i,
          new Point(
              p.timestamp(),
              p.segment(),
              p.quantity(),
              p.firstQuantity(),
              p.min(),
              p.max(),
              p.firstAt(),
              p.lastAt(),
              p.samples(),
              adjacentRate,
              p.smoothedRate(),
              p.rateWindowMs()));
      previous = p;
    }
    return new Series(
        id,
        display,
        delta,
        average,
        complete,
        first.firstAt(),
        end.lastAt(),
        count,
        delta == null ? "insufficient" : complete ? "full" : "latest-continuous");
  }

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
        throw new InvalidGranularity(step(from, to, now, 0));
      return requested;
    }
    for (long step : STEPS)
      if (step >= minimum && (to - from + step - 1) / step <= 1200) return step;
    return 7 * DAY;
  }
}
