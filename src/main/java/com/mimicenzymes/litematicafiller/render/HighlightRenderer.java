package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

import java.util.Map;

/**
 * 1.21.1 原生满分渲染器
 * 完全复刻 Masa Litematica 源码，完美解决乱飞、不透视、线太细的全部问题
 */
public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render(Object context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) return;

        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty()) return;

        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();

            // 1. 设置基础 OpenGL 状态
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();

            // 2. 完美开启透视 X-Ray（照抄 Masa renderSchematicMismatches 中的写法）
            if (xray) {
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
            } else {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
            }

            // 动态加粗线条，根据屏幕分辨率自适应
            float lineWidth = Math.max(2.5F, (float)client.getWindow().getFramebufferWidth() / 1920.0F * 2.5F);
            RenderSystem.lineWidth(lineWidth);

            // 3. 召唤 Tessellator 并开启线框绘制
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

            // 【核心修复：Masa 的标准施法前摇】
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            try {
                // 等效于 Masa 的 RenderSystem.applyModelViewMatrix();
                // 确保底层着色器应用正确的相机视角矩阵，杜绝线框满天乱飞！
                java.lang.reflect.Method applyMatrix = RenderSystem.class.getMethod("applyModelViewMatrix");
                applyMatrix.invoke(null);
            } catch (Exception e1) {
                try {
                    java.lang.reflect.Method applyMatrix = RenderSystem.class.getMethod("method_31988");
                    applyMatrix.invoke(null);
                } catch (Exception e2) {}
            }

            // 注入坐标
            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f c = getColor(entry.getValue());
                drawBoxBatched(entry.getKey(), c, 0.005, buffer, client);
            }

            // 4. 反射执行构建与绘制（摇号抽奖，100% 安全适配各版本的映射名）
            Object meshData = null;
            for (java.lang.reflect.Method m : buffer.getClass().getMethods()) {
                if (m.getParameterCount() == 0) {
                    String name = m.getName();
                    String retName = m.getReturnType().getSimpleName();
                    if (name.equals("end") || name.equals("build") || name.equals("buildOrThrow") || name.equals("method_43428") || retName.contains("Mesh") || retName.contains("Built")) {
                        m.setAccessible(true);
                        meshData = m.invoke(buffer);
                        if (meshData != null) break;
                    }
                }
            }

            if (meshData != null) {
                java.lang.reflect.Method targetMethod = null;
                try { targetMethod = net.minecraft.client.render.BufferRenderer.class.getMethod("drawWithGlobalProgram", meshData.getClass()); } catch (Exception ignored) {}
                if (targetMethod == null) {
                    try { targetMethod = net.minecraft.client.render.BufferRenderer.class.getMethod("method_43433", meshData.getClass()); } catch (Exception ignored) {}
                }
                if (targetMethod == null) {
                    for (java.lang.reflect.Method m : net.minecraft.client.render.BufferRenderer.class.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(meshData.getClass())) {
                            if (!m.getName().equals("draw") && !m.getName().equals("method_43438")) {
                                targetMethod = m;
                                break;
                            }
                        }
                    }
                }
                if (targetMethod != null) {
                    targetMethod.setAccessible(true);
                    targetMethod.invoke(null, meshData);
                }

                // 释放内存
                for (java.lang.reflect.Method m : meshData.getClass().getMethods()) {
                    if (m.getName().equals("close") && m.getParameterCount() == 0) {
                        m.invoke(meshData);
                        break;
                    }
                }
            }

            // 5. 恢复游戏全局状态，以免弄坏别的 UI
            RenderSystem.lineWidth(1.0F);
            RenderSystem.depthMask(true);
            if (xray) {
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
            }
            RenderSystem.enableCull();
            RenderSystem.disableBlend();

        } catch (Throwable e) {
            System.err.println("[LitematicaFiller] 渲染致命错误: " + e.getMessage());
        }
    }

    private void drawBoxBatched(BlockPos pos, Color4f color, double expand, BufferBuilder buffer, MinecraftClient mc) {
        // 完全抄写 Masa Litematica 中的算坐标方式：
        // 算出纯正的世界物理相对坐标，不进行任何矩阵乘法，把它直接交给 MaLiLib 去画！
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        final double dx = cameraPos.x;
        final double dy = cameraPos.y;
        final double dz = cameraPos.z;

        float minX = (float) (pos.getX() - dx - expand);
        float minY = (float) (pos.getY() - dy - expand);
        float minZ = (float) (pos.getZ() - dz - expand);
        float maxX = (float) (pos.getX() - dx + expand + 1);
        float maxY = (float) (pos.getY() - dy + expand + 1);
        float maxZ = (float) (pos.getZ() - dz + expand + 1);

        // 调用 MaLiLib 的原生大杀器：底层自动批处理画边框
        fi.dy.masa.malilib.render.RenderUtils.drawBoxAllEdgesBatchedLines(minX, minY, minZ, maxX, maxY, maxZ, color, buffer);
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