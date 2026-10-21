package local.ae2dashboard.exporter;

import appeng.api.stacks.*;
import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import local.ae2dashboard.assets.AssetManifest;
import local.ae2dashboard.identity.NativeItemIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.*;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Client commands and copied stacks only. No server handlers, packets, mixins or world writes. */
@Mod.EventBusSubscriber(modid = "ae2dashboard_assets", value = Dist.CLIENT)
public final class ClientCommands {
  private static Session active;

  private ClientCommands() {}

  @SubscribeEvent
  public static void register(RegisterClientCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("ae2dashboardassets")
                .then(Commands.literal("sample").executes(c -> start(false)))
                .then(Commands.literal("export").executes(c -> start(true)))
                .then(
                    Commands.literal("cancel")
                        .executes(
                            c -> {
                              if (active != null) {
                                if (!active.writer.cancel()) return say("导出已经结束，请查看输出目录");
                                active.cancelled = true;
                              }
                              return say("已请求取消导出");
                            })));
  }

  private static int say(String text) {
    var mc = Minecraft.getInstance();
    if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
    return 1;
  }

  private static int start(boolean full) {
    var mc = Minecraft.getInstance();
    if (active != null) return say("已有导出任务");
    if (mc.level == null || mc.player == null) return say("请先进入世界");
    if (!mc.options.languageCode.equals("zh_cn")) return say("请先选择简体中文");
    if (mc.getOverlay() != null) return say("请等待资源加载完成");
    try {
      active = new Session(mc, full);
      return say("开始采集名称与图标；/ae2dashboardassets cancel 取消");
    } catch (Exception e) {
      return say("无法开始导出：" + e.getClass().getSimpleName());
    }
  }

  @SubscribeEvent
  public static void render(TickEvent.RenderTickEvent event) {
    if (event.phase != TickEvent.Phase.END || active == null) return;
    Session session = active;
    var mc = Minecraft.getInstance();
    if (mc.level != session.world
        || mc.player == null
        || !mc.options.languageCode.equals(session.language)
        || mc.getOverlay() != null
        || !session.packIds.equals(selectedPacks(mc))) session.cancelled = true;
    try {
      if (session.cancelled) {
        boolean stopped = session.writer.cancel();
        session.renderer.close();
        active = null;
        say(stopped ? "已取消；未发布资源包" : "导出已完成，请查看输出目录");
        return;
      }
      if (session.completion != null) {
        if (session.completion.isDone()) {
          active = null;
          session.renderer.close();
          say("资源包已导出：" + session.completion.join().getFileName());
        }
        return;
      }
      session.writer.check();
      if (!session.writer.ready() || !session.requested.isDone()) return;
      long deadline = System.nanoTime() + 4_000_000;
      for (int count = 0; count < 8 && System.nanoTime() < deadline; count++) {
        AEKey key = session.next();
        if (key == null) {
          session.completion = session.writer.finish(session.skipped);
          return;
        }
        String id = NativeItemIdentity.getKey(key).toString();
        String canonical = key.toTagGeneric().toString();
        String old = session.seen.putIfAbsent(id, canonical);
        if (old != null) {
          if (!old.equals(canonical)) throw new IllegalStateException("Identity collision");
          continue;
        }
        if (session.seen.size() > 100000) throw new IllegalStateException("Too many resources");
        String type =
            key instanceof AEItemKey
                ? "item"
                : key instanceof AEFluidKey ? "fluid" : "ae-key:" + key.getType().getId();
        if (!type.equals("item") && !type.equals("fluid")) {
          session.skipped++;
          continue;
        }
        {
          BufferedImage pixels = session.capture(key);
          // Only detached strings and pixels reach the worker. A unique file is published on
          // completion.
          session.writer.add(id, key.getId().toString(), type, clientName(key), pixels);
          AEKey base = key.dropSecondary();
          String group = "group:" + type + ":" + key.getId();
          if (session.seen.putIfAbsent(group, "") == null)
            session.writer.add(
                group, key.getId().toString(), type, clientName(base), session.capture(base));
        }
      }
    } catch (Exception e) {
      session.writer.cancel();
      session.renderer.close();
      active = null;
      say("导出失败：" + e.getClass().getSimpleName() + "；未发布资源包");
    }
  }

  private static String clientName(AEKey key) {
    if (key instanceof AEItemKey item) return item.toStack().getHoverName().getString();
    if (key instanceof AEFluidKey fluid) return fluid.toStack(1).getDisplayName().getString();
    return key.getDisplayName().getString();
  }

  private static List<String> selectedPacks(Minecraft mc) {
    return mc.getResourcePackRepository().getSelectedPacks().stream().map(Pack::getId).toList();
  }

  private static final class Session {
    final ClientLevel world;
    final String language;
    final List<String> packIds;
    final Deque<AEKey> inventory = new ArrayDeque<>();
    final Iterator<Item> items;
    final Iterator<net.minecraft.world.level.material.Fluid> fluids;
    final Iterator<CreativeModeTab> tabs;
    Iterator<ItemStack> variants = Collections.emptyIterator();
    final Map<String, String> seen = new HashMap<>();
    final IconRenderer renderer = new IconRenderer();
    final PackWriter writer;
    final CompletableFuture<List<JsonObject>> requested;
    Iterator<JsonObject> extras;
    CompletableFuture<Path> completion;
    boolean cancelled;
    int skipped;

    Session(Minecraft mc, boolean full) throws Exception {
      world = mc.level;
      language = mc.options.languageCode;
      packIds = selectedPacks(mc);
      // Preserve inventory, counts, NBT and capabilities by working with detached copies.
      for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
        ItemStack copy = mc.player.getInventory().getItem(slot).copy();
        if (!copy.isEmpty()) {
          copy.setCount(1);
          inventory.add(AEItemKey.of(copy));
          FluidUtil.getFluidContained(copy.copy()).ifPresent(f -> inventory.add(AEFluidKey.of(f)));
        }
      }
      if (full)
        CreativeModeTabs.tryRebuildTabContents(
            world.enabledFeatures(), false, world.registryAccess());
      items = full ? ForgeRegistries.ITEMS.iterator() : Collections.emptyIterator();
      fluids = full ? ForgeRegistries.FLUIDS.iterator() : Collections.emptyIterator();
      tabs = full ? CreativeModeTabs.allTabs().iterator() : Collections.emptyIterator();
      Path directory = mc.gameDirectory.toPath().resolve("ae2-dashboard-assets");
      writer =
          new PackWriter(
              directory,
              language,
              packIds,
              ModList.get().getMods().stream()
                  .map(m -> m.getModId() + "@" + m.getVersion())
                  .toList());
      // File reads run off-thread; native decoding is deferred until the render thread consumes the
      // result.
      Path input = directory.resolve("resource-keys.json");
      requested =
          CompletableFuture.supplyAsync(
              () -> {
                if (!Files.isRegularFile(input)) return List.of();
                try {
                  if (Files.size(input) > 32L * 1024 * 1024)
                    throw new IllegalArgumentException("Key list too large");
                  try (var reader = Files.newBufferedReader(input)) {
                    var root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (!AssetManifest.CONTRACT.equals(root.get("identityContract").getAsString()))
                      throw new IllegalArgumentException("Identity contract mismatch");
                    if (root.getAsJsonArray("entries").size() > 100000)
                      throw new IllegalArgumentException("Key count exceeded");
                    List<JsonObject> keys = new ArrayList<>();
                    for (var entry : root.getAsJsonArray("entries"))
                      keys.add(entry.getAsJsonObject());
                    return keys;
                  }
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              });
    }

    AEKey next() {
      while (true) {
        if (!inventory.isEmpty()) return inventory.removeFirst();
        if (items.hasNext()) {
          var stack = items.next().getDefaultInstance().copy();
          if (!stack.isEmpty()) return AEItemKey.of(stack);
          continue;
        }
        if (variants.hasNext()) {
          var stack = variants.next().copy();
          if (!stack.isEmpty()) return AEItemKey.of(stack);
          continue;
        }
        if (tabs.hasNext()) {
          // Search tabs include additional display variants on supported modded tabs.
          CreativeModeTab tab = tabs.next();
          variants =
              java.util.stream.Stream.concat(
                      tab.getDisplayItems().stream(), tab.getSearchTabDisplayItems().stream())
                  .iterator();
          continue;
        }
        if (fluids.hasNext()) {
          var fluid = fluids.next();
          if (fluid != Fluids.EMPTY) return AEFluidKey.of(new FluidStack(fluid, 1));
          continue;
        }
        if (extras == null) extras = requested.join().iterator();
        if (extras.hasNext()) {
          try {
            var obj = extras.next();
            String nbt = obj.get("identity").getAsString();
            if (nbt.length() > 1_048_576)
              throw new IllegalArgumentException("Identity exceeds limit");
            AEKey key = AEKey.fromTagGeneric(TagParser.parseTag(nbt));
            if (key != null
                && NativeItemIdentity.getKey(key).toString().equals(obj.get("id").getAsString()))
              return key;
          } catch (Exception e) {
            skipped++;
          }
          continue;
        }
        return null;
      }
    }

    BufferedImage capture(AEKey key) {
      try {
        return renderer.capture(key);
      } catch (IconRenderer.ItemRenderFailure e) {
        skipped++;
        return null;
      }
    }
  }
}
