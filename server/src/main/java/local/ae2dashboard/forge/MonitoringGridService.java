package local.ae2dashboard.forge;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.*;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.networking.ControllerBlockEntity;
import java.util.*;
import net.minecraft.nbt.CompoundTag;

public final class MonitoringGridService implements IGridService, IGridServiceProvider {
  private static final Map<IGrid, MonitoringGridService> ACTIVE =
      Collections.synchronizedMap(new WeakHashMap<>());
  final IGrid grid;
  boolean dirty = true;
  private final Map<String, IGridNode> controllers = new TreeMap<>();
  private final Map<IGridNode, IChestOrDrive> cellHosts = new IdentityHashMap<>();

  public MonitoringGridService(IGrid grid) {
    this.grid = grid;
  }

  @Override
  public void addNode(IGridNode node, CompoundTag savedData) {
    dirty = true;
    String anchor = controller(node);
    if (anchor != null) controllers.put(anchor, node);
    if (node.getOwner() instanceof IChestOrDrive host) cellHosts.put(node, host);
    ACTIVE.put(grid, this);
  }

  @Override
  public void removeNode(IGridNode node) {
    dirty = true;
    String anchor = controller(node);
    if (anchor != null) controllers.remove(anchor);
    cellHosts.remove(node);
  }

  public Set<String> controllers() {
    return Set.copyOf(controllers.keySet());
  }

  IGridNode controllerNode() {
    return controllers.isEmpty() ? null : controllers.values().iterator().next();
  }

  /** 只识别已加载且接入本网络的真实无限单元，不用数量猜测来源。 */
  Set<AEKey> infiniteKeys() {
    Set<AEKey> keys = new HashSet<>();
    for (var entry : cellHosts.entrySet()) {
      if (!entry.getKey().isActive() || entry.getKey().getGrid() != grid) continue;
      var host = entry.getValue();
      for (int slot = 0; slot < host.getCellCount(); slot++) {
        var cell = host.getOriginalCellInventory(slot);
        if (cell == null
            || !cell.getClass()
                .getName()
                .equals("com.glodblock.github.extendedae.common.inventory.InfinityCellInventory"))
          continue;
        var contents = new KeyCounter();
        cell.getAvailableStacks(contents);
        for (var value : contents) keys.add(value.getKey());
      }
    }
    return keys;
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
