package local.ae2dashboard.forge;

import appeng.api.networking.GridServices;
import java.nio.file.Path;
import local.ae2dashboard.*;
import local.ae2dashboard.http.DashboardHttp;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.*;

@Mod("ae2dashboard")
public final class DashboardMod {
  private static final Logger LOG = LoggerFactory.getLogger("ae2dashboard");
  private DashboardService service;
  private DashboardHttp http;
  private AeSampler sampler;
  private AeCrafting crafting;
  private Path world;

  public DashboardMod() {
    if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
    GridServices.register(MonitoringGridService.class, MonitoringGridService.class);
    MinecraftForge.EVENT_BUS.addListener(this::start);
    MinecraftForge.EVENT_BUS.addListener(this::stop);
    MinecraftForge.EVENT_BUS.addListener(this::tick);
    MinecraftForge.EVENT_BUS.addListener(this::commands);
  }

  private void start(ServerStartedEvent event) {
    try {
      Config config = Config.load(FMLPaths.CONFIGDIR.get());
      world = event.getServer().getWorldPath(LevelResource.ROOT);
      service = new DashboardService(world, config);
      sampler = new AeSampler(service, config.sampleIntervalSeconds());
      crafting = new AeCrafting(service, sampler, event.getServer());
      service.crafting().setBackend(crafting);
      try {
        http = new DashboardHttp(service, config);
        http.start();
        service.httpStatus("listening");
      } catch (Exception e) {
        service.httpStatus("unavailable");
        LOG.error("Dashboard HTTP unavailable; sampling remains enabled", e);
      }
      LOG.info("AE dashboard recording enabled; configuration is config/ae2-dashboard.properties");
    } catch (Exception e) {
      LOG.error("AE dashboard could not initialize; database was not replaced", e);
    }
  }

  private void tick(TickEvent.ServerTickEvent event) {
    if (event.phase == TickEvent.Phase.END && sampler != null) {
      sampler.tick();
      crafting.tick();
    }
  }

  private void stop(ServerStoppingEvent event) {
    sampler = null;
    if (http != null) http.close();
    if (crafting != null) crafting.close();
    if (service != null) service.close();
    crafting = null;
    http = null;
    service = null;
    MonitoringGridService.reset();
  }

  private void commands(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("ae2dashboard")
                .requires(source -> source.hasPermission(2))
                .then(
                    Commands.literal("export-keys")
                        .executes(
                            context -> {
                              if (service == null) {
                                context
                                    .getSource()
                                    .sendFailure(Component.literal("Dashboard unavailable"));
                                return 0;
                              }
                              var source = context.getSource();
                              service
                                  .exportKeys(world)
                                  .whenComplete(
                                      (path, error) ->
                                          source
                                              .getServer()
                                              .execute(
                                                  () -> {
                                                    if (error != null)
                                                      source.sendFailure(
                                                          Component.literal(
                                                              "Resource key export failed; check"
                                                                  + " server log"));
                                                    else
                                                      source.sendSuccess(
                                                          () ->
                                                              Component.literal(
                                                                  "Exported local identity list:"
                                                                      + " ae2-dashboard/resource-keys.json"),
                                                          false);
                                                  }));
                              return 1;
                            })));
  }
}
