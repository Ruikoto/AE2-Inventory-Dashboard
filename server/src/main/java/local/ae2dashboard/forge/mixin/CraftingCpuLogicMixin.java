package local.ae2dashboard.forge.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import java.util.UUID;
import local.ae2dashboard.forge.AeCrafting;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** 只接收本网页独立任务的生命周期，不接管其他终端或 AE2 的合成逻辑。 */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicMixin {
  @Shadow @Final CraftingCPUCluster cluster;

  @Shadow
  public abstract ICraftingLink getLastLink();

  @Unique private UUID ae2dashboard$finishing;

  @Inject(method = "trySubmitJob", at = @At("RETURN"))
  private void ae2dashboard$submitted(
      IGrid grid,
      ICraftingPlan plan,
      IActionSource source,
      ICraftingRequester requester,
      CallbackInfoReturnable<ICraftingSubmitResult> result) {
    if (result.getReturnValue().successful()) AeCrafting.submitted(grid, plan, cluster);
  }

  @Inject(method = "finishJob", at = @At("HEAD"))
  private void ae2dashboard$beforeFinish(boolean success, CallbackInfo info) {
    var link = getLastLink();
    ae2dashboard$finishing = link == null ? null : link.getCraftingID();
  }

  @Inject(method = "finishJob", at = @At("RETURN"))
  private void ae2dashboard$finished(boolean success, CallbackInfo info) {
    var id = ae2dashboard$finishing;
    ae2dashboard$finishing = null;
    AeCrafting.finished(id, success);
  }
}
