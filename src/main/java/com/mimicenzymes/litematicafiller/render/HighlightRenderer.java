package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.Map;

public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render(Object context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) return;

        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty()) return;

        if (!(context instanceof MatrixStack matrices)) {
            return;
        }

        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            Vec3d cam = client.gameRenderer.getCamera().getPos();
            boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            if (xray) {
                RenderSystem.disableDepthTest();
                RenderSystem.depthFunc(GL11.GL_ALWAYS);
            } else {
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
            }

            RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);

            net.minecraft.client.render.Tessellator tessellator = net.minecraft.client.render.Tessellator.getInstance();
            net.minecraft.client.render.BufferBuilder buffer = tessellator.begin(
                    net.minecraft.client.render.VertexFormat.DrawMode.DEBUG_LINES,
                    net.minecraft.client.render.VertexFormats.POSITION_COLOR
            );

            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f c = getColor(entry.getValue());
                drawBox(matrices, buffer, entry.getKey(), cam, c);
            }

            Object meshData = null;
            for (java.lang.reflect.Method m : buffer.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0) {
                    String name = m.getName();
                    String retName = m.getReturnType().getSimpleName();
                    if (name.equals("build") || name.equals("end") || name.equals("buildOrThrow") || retName.contains("Mesh") || retName.contains("Built")) {
                        m.setAccessible(true);
                        meshData = m.invoke(buffer);
                        if (meshData != null) break;
                    }
                }
            }

            if (meshData != null) {
                for (java.lang.reflect.Method m : net.minecraft.client.render.BufferRenderer.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(meshData.getClass())) {
                        m.invoke(null, meshData);
                        break;
                    }
                }
            }

            if (xray) {
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
            }
            RenderSystem.enableCull();
            RenderSystem.disableBlend();

        } catch (Throwable e) {
        }
    }

    private void drawBox(MatrixStack matrices, VertexConsumer buffer, BlockPos pos, Vec3d cam, Color4f c) {
        matrices.push();
        matrices.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        Matrix4f model = matrices.peek().getPositionMatrix();

        float s = -0.005f;
        float e = 1.005f;

        int r = Math.max(0, Math.min(255, (int) (c.r * 255.0f)));
        int g = Math.max(0, Math.min(255, (int) (c.g * 255.0f)));
        int b = Math.max(0, Math.min(255, (int) (c.b * 255.0f)));
        int a = Math.max(0, Math.min(255, (int) (c.a * 255.0f)));

        line(buffer, model, s, s, s, e, s, s, r, g, b, a);
        line(buffer, model, e, s, s, e, s, e, r, g, b, a);
        line(buffer, model, e, s, e, s, s, e, r, g, b, a);
        line(buffer, model, s, s, e, s, s, s, r, g, b, a);

        line(buffer, model, s, e, s, e, e, s, r, g, b, a);
        line(buffer, model, e, e, s, e, e, e, r, g, b, a);
        line(buffer, model, e, e, e, s, e, e, r, g, b, a);
        line(buffer, model, s, e, e, s, e, s, r, g, b, a);

        line(buffer, model, s, s, s, s, e, s, r, g, b, a);
        line(buffer, model, e, s, s, e, e, s, r, g, b, a);
        line(buffer, model, e, s, e, e, e, e, r, g, b, a);
        line(buffer, model, s, s, e, s, e, e, r, g, b, a);

        matrices.pop();
    }

    private void line(VertexConsumer buffer, Matrix4f model, float x1, float y1, float z1, float x2, float y2, float z2, int r, int g, int b, int a) {
        buffer.vertex(model, x1, y1, z1).color(r, g, b, a);
        buffer.vertex(model, x2, y2, z2).color(r, g, b, a);
    }

    private Color4f getColor(HighlightState type) {
        return switch (type) {
            case UNFILLED -> Configs.HIGHLIGHT_COLOR_UNFILLED.getColor();
            case PARTIAL -> Configs.HIGHLIGHT_COLOR_PARTIAL.getColor();
            case OVERFILLED -> Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor();
            case WRONG_ITEM -> Configs.HIGHLIGHT_COLOR_WRONG.getColor();
            case SATISFIED -> Configs.HIGHLIGHT_COLOR_SATISFIED.getColor();
            default -> Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor();
        };
    }
}