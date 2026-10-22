package local.ae2dashboard.model;

import java.util.List;

/** 合成边界的不可变数据；不包含游戏对象、原始身份或会话凭据。 */
public final class Crafting {
  private Crafting() {}

  public record Item(
      String id,
      String name,
      String unit,
      String type,
      String registryId,
      String baseDisplayId,
      int quality) {
    public Item(String id, String name, String unit, String type, String registryId) {
      this(id, name, unit, type, registryId, null, 0);
    }
  }

  public record Cpu(
      String id,
      String name,
      String storage,
      int coprocessors,
      boolean busy,
      boolean selectable,
      boolean autoSelectable,
      String taskId,
      Item output,
      String outputQuantity,
      String totalWork,
      String completedWork,
      String elapsedNanos,
      String dashboardOrderId,
      boolean canCancel) {}

  public record CpuMaterial(Item item, String stored, String active, String pending) {}

  /** CPU 明细只在内存中缓存，绝不作为订单历史写入数据库。 */
  public record CpuStatus(
      String cpuId,
      String taskId,
      long version,
      long sampledAt,
      String state,
      boolean cantStoreItems,
      String totalWork,
      String completedWork,
      String elapsedNanos,
      int offset,
      int total,
      List<CpuMaterial> entries,
      boolean detailsAvailable,
      String error) {
    public CpuStatus {
      entries = List.copyOf(entries);
    }
  }

  public record Material(Item item, String stored, String craft, String missing) {}

  public record Plan(
      String id,
      String networkId,
      Item item,
      String quantity,
      String state,
      String bytes,
      List<Material> materials,
      String error,
      long createdAt) {
    public Plan {
      materials = List.copyOf(materials);
    }
  }

  public record Order(
      String id,
      String requestId,
      String networkId,
      String planId,
      Item item,
      String quantity,
      String requestedCpuId,
      String cpuId,
      String cpuName,
      String nativeId,
      String state,
      long createdAt,
      long updatedAt,
      long finishedAt,
      String error,
      String total,
      String progress) {
    public boolean terminal() {
      return List.of("completed", "cancelled", "failed", "unknown").contains(state);
    }
  }

  public record OrderPage(List<Order> orders, String nextCursor) {
    public OrderPage {
      orders = List.copyOf(orders);
    }
  }

  /** HTTP 层只发布稳定错误代码，异常上下文留在服务端。 */
  public static final class Failure extends RuntimeException {
    private final String code;

    public Failure(String code) {
      super(code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
