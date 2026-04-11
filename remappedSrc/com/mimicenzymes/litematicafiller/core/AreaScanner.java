package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import java.util.HashMap;
import java.util.Map;

public class AreaScanner {
    private static final Map<BlockPos, Long> ATTEMPT_COOLDOWNS = new HashMap<>();

    public static void executeScan(MinecraftClient mc, boolean isSilentPrinter) {
        if (mc.player == null || mc.world == null) return;

        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!isSilentPrinter) mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_schematic_world"), true);
            return;
        }

        BlockPos center = mc.player.getBlockPos();
        int r = Configs.FILL_RADIUS.getIntegerValue();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();
        int count = 0;
        long now = System.currentTimeMillis();

        int maxTasks = isSilentPrinter ? 15 : 40;

        scanLoop:
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = center.add(x, y, z);

                    if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

                    BlockState state = schematicWorld.getBlockState(pos);
                    if (state.isAir() || !state.hasBlockEntity()) continue;

                    BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
                    if (halves != null) {
                        pos = halves[0];
                    }

                    if (isSilentPrinter && ATTEMPT_COOLDOWNS.containsKey(pos) && now - ATTEMPT_COOLDOWNS.get(pos) < 5000) {
                        continue;
                    }

                    Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(pos, mc.world.getRegistryManager());

                    boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;
                    boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, mc);
                    boolean hasItems = required != null && !required.isEmpty() && !RealContainerCache.isSatisfied(pos, required);

                    if (!hasItems && !needsLocking) continue;

                    AutoFillerStateMachine.getInstance().addTask(pos, required == null ? new HashMap<>() : required);
                    ATTEMPT_COOLDOWNS.put(pos, now);
                    count++;

                    if (count >= maxTasks) {
                        break scanLoop;
                    }
                }
            }
        }

        if (!isSilentPrinter) {
            if (count > 0) {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.scan_start", count), true);
            } else {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_requirements"), true);
            }
        }
    }
}