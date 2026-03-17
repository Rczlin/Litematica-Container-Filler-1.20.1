package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render() {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) return;

        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty()) return;

        try {
            boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();

            RenderContext ctx = new RenderContext(
                    () -> "litematica_filler_lines",
                    xray ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_OFFSET_2
            );

            var buffer = ctx.getBuilder();
            if (buffer == null) return;

            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f c = getColor(entry.getValue());
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(entry.getKey(), c, 0.005, buffer);
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

            if (meshData == null) {
                for (java.lang.reflect.Method m : buffer.getClass().getMethods()) {
                    if (m.getParameterCount() == 0) {
                        String retName = m.getReturnType().getSimpleName();
                        if (retName.contains("Mesh") || retName.contains("Built")) {
                            m.setAccessible(true);
                            meshData = m.invoke(buffer);
                            if (meshData != null) break;
                        }
                    }
                }
            }

            if (meshData != null) {
                for (java.lang.reflect.Method m : ctx.getClass().getMethods()) {
                    if (m.getName().equals("draw") && m.getParameterCount() == 3) {
                        m.invoke(ctx, meshData, false, true);
                        break;
                    }
                }

                for (java.lang.reflect.Method m : meshData.getClass().getMethods()) {
                    if (m.getName().equals("close") && m.getParameterCount() == 0) {
                        m.invoke(meshData);
                        break;
                    }
                }
            }

            ctx.reset();

        } catch (Throwable e) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.world != null) {
                if (client.world.getTime() % 60 == 0) {
                    client.player.sendMessage(net.minecraft.text.Text.literal("§c[容器填充机] 渲染错误: " + e.getMessage()), false);
                }
            }
            e.printStackTrace();
        }
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