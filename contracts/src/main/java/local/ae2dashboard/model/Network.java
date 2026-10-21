package local.ae2dashboard.model;

import java.util.Set;

public record Network(
    String id, long createdAt, long lastSeen, String status, Set<String> anchors) {
  public Network {
    anchors = Set.copyOf(anchors);
  }

  public String label() {
    return anchors.stream().sorted().findFirst().orElse("历史网络");
  }
}
