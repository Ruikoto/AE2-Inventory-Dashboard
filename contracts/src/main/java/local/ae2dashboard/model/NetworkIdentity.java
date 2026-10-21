package local.ae2dashboard.model;

import java.util.*;

/** Global one-to-one assignment is independent of AE callback order. */
public final class NetworkIdentity {
  private NetworkIdentity() {}

  public record Match(String current, Network network, Set<String> sources, boolean changed) {}

  private record Edge(String current, Network old, int overlap, String anchorSort) {}

  public static List<Match> match(
      Map<String, Set<String>> current, Collection<Network> previous, long now) {
    var edges = new ArrayList<Edge>();
    for (var entry : current.entrySet())
      for (Network old : previous) {
        int overlap = (int) entry.getValue().stream().filter(old.anchors()::contains).count();
        if (overlap > 0)
          edges.add(
              new Edge(
                  entry.getKey(), old, overlap, String.join(";", new TreeSet<>(entry.getValue()))));
      }
    edges.sort(
        Comparator.comparingInt(Edge::overlap)
            .reversed()
            .thenComparingLong(e -> e.old().createdAt())
            .thenComparing(e -> e.old().id())
            .thenComparing(Edge::anchorSort));
    Map<String, Network> assigned = new HashMap<>();
    Set<String> claimed = new HashSet<>();
    for (Edge edge : edges)
      if (!assigned.containsKey(edge.current()) && claimed.add(edge.old().id()))
        assigned.put(edge.current(), edge.old());
    List<Match> result = new ArrayList<>();
    for (var entry : new TreeMap<>(current).entrySet()) {
      Network old = assigned.get(entry.getKey());
      Set<String> sources = new TreeSet<>();
      for (Edge edge : edges)
        if (edge.current().equals(entry.getKey())) sources.add(edge.old().id());
      Network net =
          new Network(
              old == null ? UUID.randomUUID().toString() : old.id(),
              old == null ? now : old.createdAt(),
              now,
              "booting",
              entry.getValue());
      result.add(
          new Match(
              entry.getKey(),
              net,
              Set.copyOf(sources),
              old == null || !old.anchors().equals(entry.getValue()) || sources.size() > 1));
    }
    return result;
  }
}
