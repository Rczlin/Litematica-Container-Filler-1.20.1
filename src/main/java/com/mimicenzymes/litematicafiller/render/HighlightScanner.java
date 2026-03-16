package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HighlightScanner {
    private static final Map<BlockPos, HighlightState> HIGHLIGHT_MAP = new ConcurrentHashMap<>();
    private static final Map<BlockPos, HighlightState> NEXT_HIGHLIGHT_MAP = new ConcurrentHashMap<>();

    private static int scanIndex = 0;
    private static int currentRadius = 0;
    private static BlockPos currentCenter = null;
    private static final int BLOCKS_PER_TICK = 5000;

    public static Map<BlockPos, HighlightState> getHighlights() {
        return HIGHLIGHT_MAP;
    }

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
            if (halves != null) checkPos = halves[0];

            Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(checkPos, client.world.getRegistryManager());
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

            if (hideCompleted && type == HighlightState.SATISFIED) continue;

            NEXT_HIGHLIGHT_MAP.put(pos.toImmutable(), type);
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