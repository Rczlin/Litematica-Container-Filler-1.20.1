package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HighlightScanner {
    private static final Map<BlockPos, HighlightState> HIGHLIGHT_MAP = new ConcurrentHashMap<>();
    private static final Map<BlockPos, HighlightState> NEXT_HIGHLIGHT_MAP = new ConcurrentHashMap<>();

    private static final Map<BlockPos, Map<Integer, ItemStack>> SCHEMATIC_REQ_CACHE = new ConcurrentHashMap<>();

    private static int scanIndex = 0;
    private static int currentRadius = 0;
    private static BlockPos currentCenter = null;
    private static int tickCounter = 0;

    private static final long MAX_NANOS_PER_TICK = 2_000_000L;

    private static List<BlockPos> refreshKeys = new ArrayList<>();
    private static int refreshIndex = 0;

    public static Map<BlockPos, HighlightState> getHighlights() {
        return HIGHLIGHT_MAP;
    }

    public static void clearCache() {
        SCHEMATIC_REQ_CACHE.clear();
    }

    private static Map<Integer, ItemStack> getCachedSchematicReq(BlockPos pos, MinecraftClient client) {
        Map<Integer, ItemStack> req = SCHEMATIC_REQ_CACHE.get(pos);
        if (req == null) {
            req = LitematicaContainerReader.getRequiredItems(pos, client.world.getRegistryManager());
            if (req != null) {
                SCHEMATIC_REQ_CACHE.put(pos, req);
            }
        }
        return req;
    }

    public static void tick(MinecraftClient client) {
        tickCounter++;
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            if (!HIGHLIGHT_MAP.isEmpty()) HIGHLIGHT_MAP.clear();
            return;
        }

        if (client.player == null) return;

        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!HIGHLIGHT_MAP.isEmpty()) HIGHLIGHT_MAP.clear();
            if (!SCHEMATIC_REQ_CACHE.isEmpty()) SCHEMATIC_REQ_CACHE.clear();
            return;
        }

        if (tickCounter % 100 == 0) SCHEMATIC_REQ_CACHE.clear();

        boolean hideCompleted = Configs.HIDE_COMPLETED_CONTAINERS.getBooleanValue();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();

        long startTime = System.nanoTime();

        if (!HIGHLIGHT_MAP.isEmpty()) {
            if (refreshKeys.size() != HIGHLIGHT_MAP.size() || tickCounter % 40 == 0) {
                refreshKeys = new ArrayList<>(HIGHLIGHT_MAP.keySet());
            }

            if (!refreshKeys.isEmpty()) {
                int loopCount = 0;
                while (loopCount < refreshKeys.size()) {
                    // 【时间片限流】复查阶段最多只允许占用当前帧的 1 毫秒
                    if (System.nanoTime() - startTime > 1_000_000L) break;

                    if (refreshIndex >= refreshKeys.size()) refreshIndex = 0;
                    BlockPos pos = refreshKeys.get(refreshIndex);
                    refreshIndex++;
                    loopCount++;

                    if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
                        HIGHLIGHT_MAP.remove(pos);
                        continue;
                    }

                    BlockState state = schematicWorld.getBlockState(pos);
                    if (state.isAir() || !state.hasBlockEntity()) {
                        HIGHLIGHT_MAP.remove(pos);
                        continue;
                    }

                    BlockPos checkPos = pos;
                    BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
                    if (halves != null) checkPos = halves[0];

                    Map<Integer, ItemStack> required = getCachedSchematicReq(checkPos, client);
                    boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;
                    boolean hasJob = (required != null && !required.isEmpty()) || isCrafter;

                    if (!hasJob) {
                        HIGHLIGHT_MAP.remove(pos);
                        continue;
                    }

                    Map<Integer, ItemStack> cached = RealContainerCache.getCachedItems(checkPos);
                    HighlightState type = (cached == null) ? HighlightState.UNKNOWN : evaluateState(cached, required, isCrafter, checkPos, client);

                    if (hideCompleted && type == HighlightState.SATISFIED) {
                        HIGHLIGHT_MAP.remove(pos);
                    } else {
                        HIGHLIGHT_MAP.put(pos, type);
                    }
                }
            }
        }

        int side = 2 * currentRadius + 1;
        int maxIndex = side * side * side;

        if (currentCenter == null || scanIndex >= maxIndex) {
            if (scanIndex >= maxIndex && !NEXT_HIGHLIGHT_MAP.isEmpty()) {
                HIGHLIGHT_MAP.keySet().retainAll(NEXT_HIGHLIGHT_MAP.keySet());
            }
            NEXT_HIGHLIGHT_MAP.clear();

            currentCenter = client.player.getBlockPos();
            currentRadius = Configs.RENDER_RADIUS.getIntegerValue();
            scanIndex = 0;
            side = 2 * currentRadius + 1;
            maxIndex = side * side * side;
        }

        int r = currentRadius;

        while (scanIndex < maxIndex) {
            if (System.nanoTime() - startTime > MAX_NANOS_PER_TICK) {
                break;
            }

            int x = (scanIndex % side) - r;
            int y = ((scanIndex / side) % side) - r;
            int z = ((scanIndex / (side * side)) % side) - r;
            scanIndex++;

            BlockPos pos = currentCenter.add(x, y, z);

            if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

            BlockState state = schematicWorld.getBlockState(pos);
            if (state.isAir() || !state.hasBlockEntity()) continue;

            BlockPos checkPos = pos;
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves != null) checkPos = halves[0];

            Map<Integer, ItemStack> required = getCachedSchematicReq(checkPos, client);
            boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;
            boolean hasJob = (required != null && !required.isEmpty()) || isCrafter;

            if (!hasJob) continue;

            Map<Integer, ItemStack> cached = RealContainerCache.getCachedItems(checkPos);
            HighlightState type;

            if (cached == null) {
                type = HighlightState.UNKNOWN;
                RealContainerCache.requestContainerData(checkPos);
            } else {
                type = evaluateState(cached, required, isCrafter, checkPos, client);
            }

            NEXT_HIGHLIGHT_MAP.put(pos.toImmutable(), type);

            if (!(hideCompleted && type == HighlightState.SATISFIED)) {
                HIGHLIGHT_MAP.put(pos.toImmutable(), type);
            }
        }
    }

    private static HighlightState evaluateState(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, boolean isCrafter, BlockPos pos, MinecraftClient client) {
        if (realItems == null) return HighlightState.UNKNOWN;

        int maxSlot = isCrafter ? 9 : 54;
        boolean hasAnyReal = false;
        boolean hasWrong = false;
        boolean hasExtra = false;
        boolean hasPartial = false;

        for (int i = 0; i < maxSlot; i++) {
            ItemStack real = realItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack req = (required != null) ? required.getOrDefault(i, ItemStack.EMPTY) : ItemStack.EMPTY;

            if (!real.isEmpty()) hasAnyReal = true;

            if (req.isEmpty() && !real.isEmpty()) hasExtra = true;
            else if (!req.isEmpty() && real.isEmpty()) hasPartial = true;
            else if (!req.isEmpty() && !real.isEmpty()) {
                if (!ItemMatcher.isSameItem(req, real)) hasWrong = true;
                else {
                    if (real.getCount() > req.getCount()) hasExtra = true;
                    else if (real.getCount() < req.getCount()) hasPartial = true;
                }
            }
        }

        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) hasPartial = true;

        if (hasWrong) return HighlightState.WRONG_ITEM;
        if (hasPartial) return hasAnyReal ? HighlightState.PARTIAL : HighlightState.UNFILLED;
        if (hasExtra) return HighlightState.OVERFILLED;

        return HighlightState.SATISFIED;
    }
}