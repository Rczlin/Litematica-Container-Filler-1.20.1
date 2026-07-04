package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.log.DebugCategory;
import static com.mimicenzymes.litematicafiller.log.LcfLogger.*;
import com.mojang.blaze3d.systems.RenderSystem;
import fi.dy.masa.malilib.util.Color4f;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders container highlights and task overlays in the 3D world.
 * Uses immediate-mode drawing with frustum culling for better performance in Minecraft 1.20.1.
 */
public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    private static final float TOP_PLATE_MIN_INSET = 0.02f;
    private static final float TOP_PLATE_BOTTOM_OFFSET = 0.035f;
    private static final float TOP_PLATE_TOP_OFFSET = 0.095f;
    private static final float MANUAL_BADGE_GAP = 0.014f;
    private static final float MANUAL_BADGE_SIZE = 0.44f;
    private static final float MANUAL_BADGE_THICKNESS = 0.034f;
    private int cachedHighlightVersion = Integer.MIN_VALUE;
    private RenderCache cachedRenderCache = RenderCache.empty();


    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render() {
        render(null);
    }

    public void render(Object context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            return;
        }

        if (!(context instanceof WorldRenderContext renderContext)) {
            // Fallback for unexpected callers; in practice fabric always provides a context.
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) {
            return;
        }

        AutoFillerStateMachine filler = AutoFillerStateMachine.getInstance();
        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        boolean anyHighlight = !highlights.isEmpty();
        boolean renderFilling = Configs.RENDER_FILLING_ARROW.getBooleanValue();
        boolean renderQueued = Configs.RENDER_QUEUED_SPINNER.getBooleanValue();
        boolean renderMissing = Configs.RENDER_MISSING_MATERIAL_MARKER.getBooleanValue();
        boolean hasTaskOverlays = filler.hasRenderableTaskMarkers(renderFilling, renderQueued, renderMissing);

        if (!anyHighlight && !hasTaskOverlays) {
            return;
        }

        Camera camera = renderContext.camera();
        Frustum frustum = renderContext.frustum();
        boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();
        Vec3d cameraPos = camera.getPos();
        RenderCache renderCache = anyHighlight ? getOrBuildRenderCache(highlights) : RenderCache.empty();

        try {
            setupRenderState(xray, renderContext);

            if (anyHighlight) {
                float time = (float) (System.nanoTime() / 1_000_000_000.0D);
                renderHighlights(renderCache, cameraPos, frustum, time);
            }

            if (hasTaskOverlays) {
                BlockPos currentTaskPos = renderFilling ? filler.getCurrentTaskPos() : null;
                Set<BlockPos> queuedTaskPositions = renderQueued ? filler.getQueuedTaskPositions() : Collections.emptySet();
                Set<BlockPos> missingMaterialPositions = renderMissing ? filler.getMissingMaterialPositions() : Collections.emptySet();
                float time = (float) (System.nanoTime() / 1_000_000_000.0D);
                renderTaskOverlays(cameraPos, time, currentTaskPos, queuedTaskPositions, missingMaterialPositions, frustum);
            }
        } catch (Exception e) {
            // Only log the exception message in warning; full stack trace is at error level
            warn(DebugCategory.PERF, "Failed to render container highlights: {}", e.toString());
        } finally {
            restoreRenderState(xray, renderContext);
        }
    }

    private RenderCache getOrBuildRenderCache(Map<BlockPos, HighlightState> highlights) {
        int highlightVersion = HighlightScanner.getHighlightVersion();
        if (highlightVersion == cachedHighlightVersion) {
            return cachedRenderCache;
        }

        List<CachedHighlightEntry> entries = new ArrayList<>(highlights.size());
        for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
            HighlightBox box = getHighlightBox(entry.getKey());
            entries.add(new CachedHighlightEntry(entry.getValue(), box, box.toCullingBox(), isManualState(entry.getValue())));
        }

        cachedHighlightVersion = highlightVersion;
        cachedRenderCache = new RenderCache(entries);
        return cachedRenderCache;
    }

    private void renderHighlights(RenderCache renderCache, Vec3d cameraPos, Frustum frustum, float time) {
        boolean renderGlass = Configs.RENDER_STATE_GLASS.getBooleanValue();
        boolean renderTopPlate = Configs.RENDER_STATE_TOP_PLATE.getBooleanValue();

        if (!renderGlass && !renderTopPlate) {
            return;
        }

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int vertexCount = 0;

        for (CachedHighlightEntry entry : renderCache.entries()) {
            HighlightState state = entry.state();
            if (!shouldRenderState(state)) continue;

            HighlightBox box = entry.box();
            if (frustum != null && !frustum.isVisible(entry.cullingBox())) {
                continue;
            }

            Color4f base = getColor(state);

            if (renderGlass) {
                float alphaMultiplier = (float) Configs.HIGHLIGHT_GLASS_ALPHA_MULTIPLIER.getDoubleValue();
                Color4f glass = new Color4f(base.r, base.g, base.b, Math.min(0.24f, Math.max(0.04f, base.a * alphaMultiplier)));
                vertexCount += drawInflatedWorldBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), 0.012f, glass, cameraPos, buffer);
            }

            if (renderTopPlate) {
                Color4f crown = new Color4f(base.r, base.g, base.b, Math.min(0.34f, Math.max(0.12f, base.a * 0.36f)));
                float inset = Math.max(TOP_PLATE_MIN_INSET, (1.0f - (float) Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue()) * 0.5f);
                vertexCount += drawWorldBox(
                        box.minX() + inset, box.maxY() + TOP_PLATE_BOTTOM_OFFSET, box.minZ() + inset,
                        box.maxX() - inset, box.maxY() + TOP_PLATE_TOP_OFFSET, box.maxZ() - inset,
                        crown, cameraPos, buffer
                );
            }

            if (entry.manual()) {
                vertexCount += drawManualOverrideBadge(box, state, cameraPos, buffer, time);
            }
        }

        if (vertexCount > 0) {
            BufferBuilder.BuiltBuffer builtBuffer = buffer.end();
            if (builtBuffer != null && !builtBuffer.isEmpty()) {
                BufferRenderer.drawWithGlobalProgram(builtBuffer);
            }
        } else {
            // Must call end() to reset the building state, then discard the empty buffer
            BufferBuilder.BuiltBuffer builtBuffer = buffer.end();
            builtBuffer.release();
        }
    }

    private void renderTaskOverlays(Vec3d cameraPos, float time, BlockPos currentTaskPos,
                                     Set<BlockPos> queuedTaskPositions,
                                     Set<BlockPos> missingMaterialPositions,
                                     Frustum frustum) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int vertexCount = 0;

        if (Configs.RENDER_FILLING_ARROW.getBooleanValue() && currentTaskPos != null) {
            HighlightBox box = getHighlightBox(currentTaskPos);
            if (frustum == null || frustum.isVisible(new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()))) {
                vertexCount += drawFillingArrow(box, cameraPos, time, buffer);
            }
        }

        if (Configs.RENDER_QUEUED_SPINNER.getBooleanValue()) {
            int count = 0;
            int maxQueued = Configs.MAX_QUEUED_RENDER_OVERLAYS.getIntegerValue();
            for (BlockPos pos : queuedTaskPositions) {
                if (pos == null || pos.equals(currentTaskPos)) continue;
                if (count++ >= maxQueued) break;
                HighlightBox box = getHighlightBox(pos);
                if (frustum == null || frustum.isVisible(new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()))) {
                    vertexCount += drawQueuedSpinner(box, cameraPos, time + count * 0.17f, buffer);
                }
            }
        }

        if (Configs.RENDER_MISSING_MATERIAL_MARKER.getBooleanValue()) {
            int count = 0;
            int maxMissing = Configs.MAX_QUEUED_RENDER_OVERLAYS.getIntegerValue();
            for (BlockPos pos : missingMaterialPositions) {
                if (count++ >= maxMissing) break;
                if (pos != null) {
                    HighlightBox box = getHighlightBox(pos);
                    if (frustum == null || frustum.isVisible(new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()))) {
                        vertexCount += drawMissingMaterialMarker(box, cameraPos, time, buffer);
                    }
                }
            }
        }

        if (vertexCount > 0) {
            BufferBuilder.BuiltBuffer builtBuffer = buffer.end();
            if (builtBuffer != null && !builtBuffer.isEmpty()) {
                BufferRenderer.drawWithGlobalProgram(builtBuffer);
            }
        } else {
            // Must call end() to reset the building state, then discard the empty buffer
            BufferBuilder.BuiltBuffer builtBuffer = buffer.end();
            builtBuffer.release();
        }
    }

    private void setupRenderState(boolean xray, WorldRenderContext context) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        if (xray) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            GL11.glDepthRange(0.0, 0.0);
        } else {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
        }

        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.2f, -0.2f);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        MatrixStack modelView = RenderSystem.getModelViewStack();
        modelView.push();
        modelView.peek().getPositionMatrix().identity();
        Matrix4f cameraView = context.matrixStack().peek().getPositionMatrix();
        modelView.peek().getPositionMatrix().mul(cameraView);
        RenderSystem.applyModelViewMatrix();
    }

    private void restoreRenderState(boolean xray, WorldRenderContext context) {
        RenderSystem.polygonOffset(0f, 0f);
        RenderSystem.disablePolygonOffset();
        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        if (xray) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            GL11.glDepthRange(0.0, 1.0);
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        RenderSystem.getModelViewStack().pop();
        RenderSystem.applyModelViewMatrix();
    }

    // ──── Drawing helpers ────

    private int drawFillingArrow(HighlightBox box, Vec3d cameraPos, float time, BufferBuilder buffer) {
        float cx = box.centerX();
        float cz = box.centerZ();
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float y = box.maxY() + 0.64f + (float) Math.sin(time * 5.0f) * 0.045f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 7.0f);
        Color4f base = Configs.HIGHLIGHT_COLOR_FILLING.getColor();
        Color4f body = new Color4f(base.r, base.g, base.b, Math.min(0.82f, base.a * (0.50f + pulse * 0.12f)));
        Color4f core = new Color4f(0.82f, 1.0f, 0.96f, 0.36f);
        Color4f glow = new Color4f(base.r, base.g, base.b, 0.12f);

        int count = 0;
        count += drawVerticalDownArrow(cx, y, cz, 0.24f * scale, 0.62f * scale, 0.080f * scale, body, cameraPos, buffer);
        count += drawVerticalDownArrow(cx, y + 0.010f * scale, cz, 0.135f * scale, 0.39f * scale, 0.046f * scale, core, cameraPos, buffer);
        count += drawCenteredWorldBox(cx, box.maxY() + 0.045f, cz, (0.24f + pulse * 0.05f) * scale, 0.020f * scale, glow, cameraPos, buffer);
        return count;
    }

    private int drawQueuedSpinner(HighlightBox box, Vec3d cameraPos, float time, BufferBuilder buffer) {
        float cx = box.centerX();
        float cz = box.centerZ();
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float cy = box.maxY() + 0.34f + (float) Math.sin(time * 2.4f) * 0.028f;
        float radius = 0.32f * scale;
        Color4f base = Configs.HIGHLIGHT_COLOR_QUEUED.getColor();

        int count = 0;
        for (int i = 0; i < 8; i++) {
            float angle = time * 3.2f + i * ((float) Math.PI / 4.0f);
            float x = cx + (float) Math.cos(angle) * radius;
            float z = cz + (float) Math.sin(angle) * radius;
            float alpha = Math.min(0.70f, base.a * (0.16f + i * 0.055f));
            count += drawCenteredWorldBox(x, cy, z, 0.058f * scale, 0.035f * scale, new Color4f(base.r, base.g, base.b, alpha), cameraPos, buffer);
        }

        count += drawCenteredWorldBox(cx, cy, cz, 0.15f * scale, 0.025f * scale, new Color4f(base.r, base.g, base.b, 0.12f), cameraPos, buffer);
        return count;
    }

    private int drawMissingMaterialMarker(HighlightBox box, Vec3d cameraPos, float time, BufferBuilder buffer) {
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float cx = box.centerX();
        float cz = box.centerZ();
        float cy = box.maxY() + 0.34f + (float) Math.sin(time * 4.4f) * 0.035f;
        Color4f base = Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL.getColor();
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 8.0f);
        Color4f color = new Color4f(base.r, base.g, base.b, Math.min(0.82f, base.a * (0.42f + pulse * 0.22f)));

        int count = 0;
        count += drawCenteredWorldBox(cx, cy + 0.24f * scale, cz, 0.070f * scale, 0.045f * scale, color, cameraPos, buffer);
        count += drawCenteredWorldBox(cx, cy, cz, 0.060f * scale, 0.19f * scale, color, cameraPos, buffer);
        count += drawCenteredWorldBox(cx, cy - 0.30f * scale, cz, 0.070f * scale, 0.050f * scale, color, cameraPos, buffer);
        return count;
    }

    private int drawVerticalDownArrow(float cx, float cy, float cz, float halfWidth, float height, float halfDepth, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        float shaftHalf = halfWidth * 0.26f;
        float shaftTop = cy + height * 0.44f;
        float shaftBottom = cy - height * 0.04f;
        float headTop = cy - height * 0.02f;
        float tipY = cy - height * 0.48f;
        float[] headXs = {cx - halfWidth, cx + halfWidth, cx};
        float[] headYs = {headTop, headTop, tipY};

        int count = 0;
        count += drawWorldBox(cx - shaftHalf, shaftBottom, cz - halfDepth, cx + shaftHalf, shaftTop, cz + halfDepth, color, cameraPos, buffer);
        count += drawWorldPrism(headXs, headYs, cz, halfDepth, color, cameraPos, buffer);
        return count;
    }

    private int drawWorldPrism(float[] xs, float[] ys, float cz, float halfDepth, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        if (xs.length < 3 || xs.length != ys.length) return 0;

        float frontZ = (float) (cz - halfDepth - cameraPos.z);
        float backZ = (float) (cz + halfDepth - cameraPos.z);
        int count = 0;

        for (int i = 1; i + 1 < xs.length; i++) {
            vertex((float) (xs[0] - cameraPos.x), (float) (ys[0] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[i + 1] - cameraPos.x), (float) (ys[i + 1] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[i + 1] - cameraPos.x), (float) (ys[i + 1] - cameraPos.y), frontZ, color, buffer);

            vertex((float) (xs[0] - cameraPos.x), (float) (ys[0] - cameraPos.y), backZ, color, buffer);
            vertex((float) (xs[i + 1] - cameraPos.x), (float) (ys[i + 1] - cameraPos.y), backZ, color, buffer);
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), backZ, color, buffer);
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), backZ, color, buffer);
            count += 8;
        }

        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[next] - cameraPos.x), (float) (ys[next] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[next] - cameraPos.x), (float) (ys[next] - cameraPos.y), backZ, color, buffer);
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), backZ, color, buffer);
            count += 4;
        }
        return count;
    }

    private int drawCenteredWorldBox(float cx, float cy, float cz, float halfSize, float halfHeight, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        return drawWorldBox(
                cx - halfSize, cy - halfHeight, cz - halfSize,
                cx + halfSize, cy + halfHeight, cz + halfSize,
                color, cameraPos, buffer
        );
    }

    private int drawInflatedWorldBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float inflate, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        return drawWorldBox(
                minX - inflate, minY - inflate, minZ - inflate,
                maxX + inflate, maxY + inflate, maxZ + inflate,
                color, cameraPos, buffer
        );
    }

    private int drawWorldBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        float x1 = (float) (minX - cameraPos.x);
        float y1 = (float) (minY - cameraPos.y);
        float z1 = (float) (minZ - cameraPos.z);
        float x2 = (float) (maxX - cameraPos.x);
        float y2 = (float) (maxY - cameraPos.y);
        float z2 = (float) (maxZ - cameraPos.z);

        vertex(x1, y1, z1, color, buffer); vertex(x2, y1, z1, color, buffer); vertex(x2, y2, z1, color, buffer); vertex(x1, y2, z1, color, buffer);
        vertex(x2, y1, z2, color, buffer); vertex(x1, y1, z2, color, buffer); vertex(x1, y2, z2, color, buffer); vertex(x2, y2, z2, color, buffer);
        vertex(x1, y1, z2, color, buffer); vertex(x1, y1, z1, color, buffer); vertex(x1, y2, z1, color, buffer); vertex(x1, y2, z2, color, buffer);
        vertex(x2, y1, z1, color, buffer); vertex(x2, y1, z2, color, buffer); vertex(x2, y2, z2, color, buffer); vertex(x2, y2, z1, color, buffer);
        vertex(x1, y2, z1, color, buffer); vertex(x2, y2, z1, color, buffer); vertex(x2, y2, z2, color, buffer); vertex(x1, y2, z2, color, buffer);
        vertex(x1, y1, z2, color, buffer); vertex(x2, y1, z2, color, buffer); vertex(x2, y1, z1, color, buffer); vertex(x1, y1, z1, color, buffer);
        return 24;
    }

    private void vertex(float x, float y, float z, Color4f color, BufferBuilder buffer) {
        buffer.vertex(x, y, z).color(toChannel(color.r), toChannel(color.g), toChannel(color.b), toChannel(color.a)).next();
    }

    private int toChannel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    // ──── Color and state helpers ────

    private Color4f getColor(HighlightState type) {
        return switch (type) {
            case UNFILLED, MANUAL_NEEDS_FILL -> Configs.HIGHLIGHT_COLOR_UNFILLED.getColor();
            case PARTIAL -> Configs.HIGHLIGHT_COLOR_PARTIAL.getColor();
            case OVERFILLED -> Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor();
            case WRONG_ITEM -> Configs.HIGHLIGHT_COLOR_WRONG.getColor();
            case SATISFIED, MANUAL_COMPLETED -> Configs.HIGHLIGHT_COLOR_SATISFIED.getColor();
            case UNPLACED -> Configs.HIGHLIGHT_COLOR_UNPLACED.getColor();
            default -> Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor();
        };
    }

    private boolean shouldRenderState(HighlightState state) {
        return switch (state) {
            case UNFILLED, MANUAL_NEEDS_FILL -> Configs.RENDER_STATE_UNFILLED.getBooleanValue();
            case PARTIAL -> Configs.RENDER_STATE_PARTIAL.getBooleanValue();
            case OVERFILLED -> Configs.RENDER_STATE_OVERFILLED.getBooleanValue();
            case WRONG_ITEM -> Configs.RENDER_STATE_WRONG.getBooleanValue();
            case SATISFIED, MANUAL_COMPLETED -> Configs.RENDER_STATE_SATISFIED.getBooleanValue();
            case UNPLACED -> Configs.HIGHLIGHT_UNPLACED_CONTAINERS.getBooleanValue();
            default -> Configs.RENDER_STATE_UNKNOWN.getBooleanValue();
        };
    }

    private boolean isManualState(HighlightState state) {
        return state == HighlightState.MANUAL_COMPLETED || state == HighlightState.MANUAL_NEEDS_FILL;
    }

    private int drawManualOverrideBadge(HighlightBox box, HighlightState state, Vec3d cameraPos, BufferBuilder buffer, float time) {
        float size = Math.min(box.maxX() - box.minX(), box.maxZ() - box.minZ());
        float cx = box.centerX();
        float cz = box.centerZ();
        float thickness = Math.max(0.022f, size * MANUAL_BADGE_THICKNESS);
        float yBase = box.maxY() + TOP_PLATE_TOP_OFFSET + MANUAL_BADGE_GAP;
        float y = yBase + (float) Math.sin(time * 3.0f) * 0.012f;
        float half = size * MANUAL_BADGE_SIZE * 0.5f;
        Color4f ring = state == HighlightState.MANUAL_COMPLETED
                ? new Color4f(0.88f, 1.0f, 0.95f, 0.76f)
                : new Color4f(1.0f, 0.86f, 0.34f, 0.76f);
        Color4f accent = state == HighlightState.MANUAL_COMPLETED
                ? new Color4f(0.16f, 1.0f, 0.62f, 0.90f)
                : new Color4f(1.0f, 0.52f, 0.12f, 0.90f);

        int count = 0;
        count += drawWorldBox(cx - half, y, cz - half, cx + half, y + thickness, cz - half + thickness, ring, cameraPos, buffer);
        count += drawWorldBox(cx - half, y, cz + half - thickness, cx + half, y + thickness, cz + half, ring, cameraPos, buffer);
        count += drawWorldBox(cx - half, y, cz - half, cx - half + thickness, y + thickness, cz + half, ring, cameraPos, buffer);
        count += drawWorldBox(cx + half - thickness, y, cz - half, cx + half, y + thickness, cz + half, ring, cameraPos, buffer);

        if (state == HighlightState.MANUAL_COMPLETED) {
            count += drawWorldBox(cx - half * 0.48f, y + thickness, cz - thickness * 0.5f, cx - half * 0.08f, y + thickness * 2.0f, cz + thickness * 0.5f, accent, cameraPos, buffer);
            count += drawWorldBox(cx - half * 0.12f, y + thickness, cz - thickness * 0.5f, cx + half * 0.56f, y + thickness * 2.0f, cz + thickness * 0.5f, accent, cameraPos, buffer);
        } else {
            count += drawWorldBox(cx - thickness * 0.5f, y + thickness, cz - half * 0.58f, cx + thickness * 0.5f, y + thickness * 2.0f, cz + half * 0.22f, accent, cameraPos, buffer);
            count += drawWorldBox(cx - thickness * 0.6f, y + thickness, cz + half * 0.42f, cx + thickness * 0.6f, y + thickness * 2.0f, cz + half * 0.56f, accent, cameraPos, buffer);
        }
        return count;
    }

    private HighlightBox getHighlightBox(BlockPos pos) {
        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null || pos == null) {
            return HighlightBox.single(pos == null ? BlockPos.ORIGIN : pos);
        }

        BlockState state = schematicWorld.getBlockState(pos);
        BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(schematicWorld, pos, state);
        if (halves == null) {
            return HighlightBox.single(pos);
        }

        return HighlightBox.of(halves[0], halves[1]);
    }

    private record CachedHighlightEntry(HighlightState state, HighlightBox box, Box cullingBox, boolean manual) {
    }

    private record RenderCache(List<CachedHighlightEntry> entries) {
        private static RenderCache empty() {
            return new RenderCache(Collections.emptyList());
        }
    }

    private record HighlightBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        static HighlightBox single(BlockPos pos) {
            return new HighlightBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0f, pos.getY() + 1.0f, pos.getZ() + 1.0f);
        }

        static HighlightBox of(BlockPos first, BlockPos second) {
            return new HighlightBox(
                    Math.min(first.getX(), second.getX()),
                    Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ()),
                    Math.max(first.getX(), second.getX()) + 1.0f,
                    Math.max(first.getY(), second.getY()) + 1.0f,
                    Math.max(first.getZ(), second.getZ()) + 1.0f
            );
        }

        float centerX() { return (minX + maxX) * 0.5f; }
        float centerZ() { return (minZ + maxZ) * 0.5f; }
        Box toCullingBox() { return new Box(minX, minY, minZ, maxX, maxY, maxZ); }
    }
}
