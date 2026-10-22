package local.ae2dashboard.model;

/** 仅用于显示的品质归一化结果，不参与资源身份或库存统计。 */
public record DisplayHint(String baseId, int quality) {
  public static final DisplayHint NONE = new DisplayHint("", 0);
}
