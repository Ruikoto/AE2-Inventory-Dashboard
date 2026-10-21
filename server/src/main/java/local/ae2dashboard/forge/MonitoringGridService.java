package local.ae2dashboard.forge;

import appeng.api.networking.*;
import appeng.blockentity.networking.ControllerBlockEntity;
import java.util.*;
import net.minecraft.nbt.CompoundTag;

public final class MonitoringGridService implements IGridService, IGridServiceProvider {
  private static final Map<IGrid, MonitoringGridService> ACTIVE =
      Collections.synchronizedMap(new WeakHashMap<>());
  final IGrid grid;
  boolean dirty = true;
  private final Set<String> controllers = new TreeSet<>();

  public MonitoringGridService(IGrid grid) {
    this.grid = grid;
  }

  @Override
  public void addNode(IGridNode node, CompoundTag savedData) {
    dirty = true;
    String anchor = controller(node);
    if (anchor != null) controllers.add(anchor);
    ACTIVE.put(grid, this);
  }

  @Override
  public void removeNode(IGridNode node) {
    dirty = true;
    String anchor = controller(node);
    if (anchor != null) controllers.remove(anchor);
  }

  public Set<String> controllers() {
    return Set.copyOf(controllers);
  }

  private static String controller(IGridNode node) {
    if (!(node.getOwner() instanceof ControllerBlockEntity controller)) return null;
    var pos = controller.getBlockPos();
    return node.getLevel().dimension().location()
        + " "
        + pos.getX()
        + ","
        + pos.getY()
        + ","
        + pos.getZ();
  }

  public static List<MonitoringGridService> active() {
    synchronized (ACTIVE) {
      return List.copyOf(ACTIVE.values());
    }
  }

  public static void reset() {
    ACTIVE.clear();
  }

  public static void retire(IGrid grid) {
    ACTIVE.remove(grid);
  }
}
