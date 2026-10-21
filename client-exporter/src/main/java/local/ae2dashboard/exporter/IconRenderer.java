package local.ae2dashboard.exporter;

import appeng.api.stacks.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.awt.image.BufferedImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

public final class IconRenderer implements AutoCloseable {
  private TextureTarget target;
  private final MultiBufferSource.BufferSource buffers =
      MultiBufferSource.immediate(new BufferBuilder(4096));

  public static final class ItemRenderFailure extends Exception {
    ItemRenderFailure(Throwable cause) {
      super(cause);
    }
  }

  public BufferedImage capture(AEKey key) throws ItemRenderFailure {
    RenderSystem.assertOnRenderThread();
    var mc = Minecraft.getInstance();
    if (target == null) {
      target = new TextureTarget(64, 64, true, Minecraft.ON_OSX);
      target.setClearColor(0, 0, 0, 0);
    }
    int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
        texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int[] viewport = new int[4];
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
    int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    int[] shaderTextures = new int[12];
    for (int i = 0; i < shaderTextures.length; i++)
      shaderTextures[i] = RenderSystem.getShaderTexture(i);
    int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
        destRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
        sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
        destAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    var shader = RenderSystem.getShader();
    boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
        blend = GL11.glIsEnabled(GL11.GL_BLEND),
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    float[] color = RenderSystem.getShaderColor().clone();
    Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
    var sorting = RenderSystem.getVertexSorting();
    var model = RenderSystem.getModelViewStack();
    model.pushPose();
    try {
      RenderSystem.disableScissor();
      RenderSystem.depthMask(true);
      target.clear(Minecraft.ON_OSX);
      target.bindWrite(true);
      RenderSystem.setProjectionMatrix(
          new Matrix4f().setOrtho(0, 16, 16, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
      model.setIdentity();
      RenderSystem.applyModelViewMatrix();
      RenderSystem.setShaderColor(1, 1, 1, 1);
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.enableDepthTest();
      Lighting.setupFor3DItems();
      var graphics = new GuiGraphics(mc, buffers);
      try {
        if (key instanceof AEItemKey item) graphics.renderItem(item.toStack(), 0, 0);
        else if (key instanceof AEFluidKey fluid) {
          var stack = fluid.toStack(1000);
          var extension = IClientFluidTypeExtensions.of(stack.getFluid());
          var location = extension.getStillTexture(stack);
          int tint = extension.getTintColor(stack);
          var sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(location);
          graphics.blit(
              0,
              0,
              0,
              16,
              16,
              sprite,
              ((tint >> 16) & 255) / 255f,
              ((tint >> 8) & 255) / 255f,
              (tint & 255) / 255f,
              ((tint >>> 24) & 255) / 255f);
        }
        graphics.flush();
      } catch (RuntimeException e) {
        throw new ItemRenderFailure(e);
      }
      try (var image = new NativeImage(64, 64, false)) {
        RenderSystem.bindTexture(target.getColorTextureId());
        image.downloadTexture(0, false);
        image.flipY();
        BufferedImage result = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++)
          for (int x = 0; x < 64; x++) {
            int abgr = image.getPixelRGBA(x, y);
            result.setRGB(x, y, (abgr & 0xff00ff00) | ((abgr & 255) << 16) | ((abgr >>> 16) & 255));
          }
        return result;
      }
    } finally {
      model.popPose();
      RenderSystem.applyModelViewMatrix();
      RenderSystem.setProjectionMatrix(projection, sorting);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
      RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
      for (int i = 0; i < shaderTextures.length; i++)
        RenderSystem.setShaderTexture(i, shaderTextures[i]);
      RenderSystem.activeTexture(activeTexture);
      RenderSystem.bindTexture(texture);
      RenderSystem.setShader(() -> shader);
      RenderSystem.blendFuncSeparate(sourceRgb, destRgb, sourceAlpha, destAlpha);
      RenderSystem.depthMask(depthMask);
      RenderSystem.depthFunc(depthFunction);
      if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
      else GL11.glDisable(GL11.GL_SCISSOR_TEST);
      RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
      if (depth) RenderSystem.enableDepthTest();
      else RenderSystem.disableDepthTest();
      if (blend) RenderSystem.enableBlend();
      else RenderSystem.disableBlend();
      if (cull) RenderSystem.enableCull();
      else RenderSystem.disableCull();
    }
  }

  @Override
  public void close() {
    if (target != null) {
      target.destroyBuffers();
      target = null;
    }
  }
}
