package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ContainerHighlighter {

    public enum HighlightType {
        UNFILLED,    // 蓝色 - 完全未填充
        PARTIAL,     // 黄色 - 填充没完成或格子不对
        OVERFILLED,  // 粉色 - 填多了 / 有多余杂物
        WRONG_ITEM,  // 红色 - 填错了物品
        UNKNOWN,     // 橙色 - 数据未知 / 正在同步
        SATISFIED    // 绿色 - 完美满足
    }

    private static final Map<BlockPos, HighlightType> HIGHLIGHT_MAP = new ConcurrentHashMap<>();
    private static final Map<BlockPos, HighlightType> NEXT_HIGHLIGHT_MAP = new ConcurrentHashMap<>();

    private static int scanIndex = 0;
    private static int currentRadius = 0;
    private static BlockPos currentCenter = null;
    private static final int BLOCKS_PER_TICK = 5000; // 每刻最大扫描方块数，彻底杜绝掉帧

    public static void tick(MinecraftClient client) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            if (!HIGHLIGHT_MAP.isEmpty()) HIGHLIGHT_MAP.clear();
            return;
        }

        if (client.player == null) return;

        int side = 2 * currentRadius + 1;
        int maxIndex = side * side * side;

        if (currentCenter == null || scanIndex >= maxIndex) {

            HIGHLIGHT_MAP.clear();
            HIGHLIGHT_MAP.putAll(NEXT_HIGHLIGHT_MAP);
            NEXT_HIGHLIGHT_MAP.clear();

            currentCenter = client.player.getBlockPos();
            currentRadius = Configs.RENDER_RADIUS.getIntegerValue();
            scanIndex = 0;
            side = 2 * currentRadius + 1;
            maxIndex = side * side * side;
        }

        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return;

        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();
        boolean hideCompleted = Configs.HIDE_COMPLETED_CONTAINERS.getBooleanValue();
        int r = currentRadius;
        int processed = 0;

        while (processed < BLOCKS_PER_TICK && scanIndex < maxIndex) {
            int x = (scanIndex % side) - r;
            int y = ((scanIndex / side) % side) - r;
            int z = ((scanIndex / (side * side)) % side) - r;
            scanIndex++;
            processed++;

            BlockPos pos = currentCenter.add(x, y, z);

            if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

            BlockState state = schematicWorld.getBlockState(pos);
            if (state.isAir() || !state.hasBlockEntity()) continue;

            BlockPos checkPos = pos;
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves != null) {
                checkPos = halves[0];
            }

            Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(checkPos, client.world.getRegistryManager());
            boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;
            boolean hasJob = (required != null && !required.isEmpty()) || isCrafter;

            if (!hasJob) continue;

            Map<Integer, ItemStack> cached = RealContainerCache.getCachedItems(checkPos);
            HighlightType type;

            if (cached == null) {
                type = HighlightType.UNKNOWN;
                RealContainerCache.requestContainerData(checkPos);
            } else {
                type = evaluateState(cached, required, isCrafter, checkPos, client);
            }

            if (hideCompleted && type == HighlightType.SATISFIED) continue;

            NEXT_HIGHLIGHT_MAP.put(pos.toImmutable(), type);
        }
    }

    public static void onRender(WorldRenderContext context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) return;
        if (HIGHLIGHT_MAP.isEmpty()) return;

        Vec3d cam = context.gameRenderer().getCamera().getPos();
        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());

        for (Map.Entry<BlockPos, HighlightType> entry : HIGHLIGHT_MAP.entrySet()) {
            BlockPos pos = entry.getKey();
            HighlightType type = entry.getValue();
            Color4f c;

            switch (type) {
                case UNFILLED -> c = Configs.HIGHLIGHT_COLOR_UNFILLED.getColor();
                case PARTIAL -> c = Configs.HIGHLIGHT_COLOR_PARTIAL.getColor();
                case OVERFILLED -> c = Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor();
                case WRONG_ITEM -> c = Configs.HIGHLIGHT_COLOR_WRONG.getColor();
                case SATISFIED -> c = Configs.HIGHLIGHT_COLOR_SATISFIED.getColor();
                default -> c = Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor();
            }

            renderBox(matrices, buffer, pos, cam, c);
        }

        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            if (Configs.HIGHLIGHT_XRAY.getBooleanValue()) GL11.glDisable(GL11.GL_DEPTH_TEST);
            immediate.draw(RenderLayer.getLines());
            if (Configs.HIGHLIGHT_XRAY.getBooleanValue()) GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
    }

    private static HighlightType evaluateState(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, boolean isCrafter, BlockPos pos, MinecraftClient client) {
        if (realItems == null) return HighlightType.UNKNOWN;

        int maxSlot = isCrafter ? 9 : 54;
        boolean hasAnyReal = false;
        boolean hasWrong = false;
        boolean hasExtra = false;
        boolean hasPartial = false;

        for (int i = 0; i < maxSlot; i++) {
            ItemStack real = realItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack req = (required != null) ? required.getOrDefault(i, ItemStack.EMPTY) : ItemStack.EMPTY;

            if (!real.isEmpty()) hasAnyReal = true;

            if (req.isEmpty() && !real.isEmpty()) {
                hasExtra = true;
            } else if (!req.isEmpty() && real.isEmpty()) {
                hasPartial = true;
            } else if (!req.isEmpty() && !real.isEmpty()) {
                if (!ItemMatcher.isSameItem(req, real)) {
                    hasWrong = true;
                } else {
                    if (real.getCount() > req.getCount()) {
                        hasExtra = true;
                    } else if (real.getCount() < req.getCount()) {
                        hasPartial = true;
                    }
                }
            }
        }

        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) {
            hasPartial = true;
        }

        if (hasWrong) return HighlightType.WRONG_ITEM;
        if (hasPartial) {
            return hasAnyReal ? HighlightType.PARTIAL : HighlightType.UNFILLED;
        }
        if (hasExtra) return HighlightType.OVERFILLED;

        return HighlightType.SATISFIED;
    }

    private static void renderBox(MatrixStack matrices, VertexConsumer buffer, BlockPos pos, Vec3d cam, Color4f c) {
        matrices.push();
        matrices.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        Matrix4f model = matrices.peek().getPositionMatrix();
        float s = -0.005f, e = 1.005f;
        line(buffer, model, s, s, s, e, s, s, c); line(buffer, model, e, s, s, e, s, e, c);
        line(buffer, model, e, s, e, s, s, e, c); line(buffer, model, s, s, e, s, s, s, c);
        line(buffer, model, s, e, s, e, e, s, c); line(buffer, model, e, e, s, e, e, e, c);
        line(buffer, model, e, e, e, s, e, e, c); line(buffer, model, s, e, e, s, e, s, c);
        line(buffer, model, s, s, s, s, e, s, c); line(buffer, model, e, s, s, e, e, s, c);
        line(buffer, model, e, s, e, e, e, e, c); line(buffer, model, s, s, e, s, e, e, c);
        matrices.pop();
    }

    private static void line(VertexConsumer buffer, Matrix4f model, float x1, float y1, float z1, float x2, float y2, float z2, Color4f c) {
        buffer.vertex(model, x1, y1, z1).color(c.r, c.g, c.b, c.a).normal(0, 0, 0);
        buffer.vertex(model, x2, y2, z2).color(c.r, c.g, c.b, c.a).normal(0, 0, 0);
    }
}