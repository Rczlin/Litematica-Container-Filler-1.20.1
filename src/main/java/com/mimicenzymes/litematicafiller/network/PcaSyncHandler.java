package com.mimicenzymes.litematicafiller.network;

import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * PCA (PluslsCarpetAddition) sync protocol handler.
 *
 * Client -> Server: pca:sync_block_entity(BlockPos)
 * Server -> Client: pca:update_block_entity(dimension, BlockPos, NBT)
 */
public class PcaSyncHandler {
    private static final Logger LOGGER = LogManager.getLogger(Reference.MOD_ID);
    private static final int MAX_UPDATES_PER_TICK = 128;

    public static final Identifier ENABLE_PCA_SYNC_PROTOCOL  = new Identifier("pca", "enable_pca_sync_protocol");
    public static final Identifier DISABLE_PCA_SYNC_PROTOCOL = new Identifier("pca", "disable_pca_sync_protocol");
    public static final Identifier UPDATE_BLOCK_ENTITY       = new Identifier("pca", "update_block_entity");
    public static final Identifier SYNC_BLOCK_ENTITY         = new Identifier("pca", "sync_block_entity");

    private static final Map<BlockPos, PcaUpdateBlockEntityData> PENDING_UPDATES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<BlockPos> PENDING_UPDATE_ORDER = new ConcurrentLinkedDeque<>();
    private static final Set<BlockPos> QUEUED_POSITIONS = ConcurrentHashMap.newKeySet();

    private static boolean initialized = false;
    /** True when server has PCA protocol enabled. */
    public static volatile boolean enabled = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[LCF DEBUG] [PCA] Registering channel handlers");

        ClientPlayNetworking.registerGlobalReceiver(ENABLE_PCA_SYNC_PROTOCOL, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                if (!client.isInSingleplayer()) {
                    LOGGER.info("[LCF DEBUG] [PCA] Protocol enabled by server");
                    enabled = true;
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(DISABLE_PCA_SYNC_PROTOCOL, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                LOGGER.info("[LCF DEBUG] [PCA] Protocol disabled by server");
                enabled = false;
                clearPendingUpdates();
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(UPDATE_BLOCK_ENTITY, (client, handler, buf, responseSender) -> {
            PcaUpdateBlockEntityData data = readUpdateBlockEntity(buf);
            if (data != null) {
                enqueueUpdate(data);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            enabled = false;
            clearPendingUpdates();
        });
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null || PENDING_UPDATE_ORDER.isEmpty()) {
            return;
        }

        int processed = 0;
        while (processed < MAX_UPDATES_PER_TICK) {
            BlockPos pos = PENDING_UPDATE_ORDER.pollFirst();
            if (pos == null) {
                break;
            }

            QUEUED_POSITIONS.remove(pos);
            PcaUpdateBlockEntityData data = PENDING_UPDATES.remove(pos);
            if (data == null) {
                continue;
            }

            applyQueuedUpdate(client, data);
            processed++;
        }
    }

    public static int getPendingUpdateCount() {
        return PENDING_UPDATES.size();
    }

    public static Set<BlockPos> getPendingPositionsSnapshot() {
        return new HashSet<>(PENDING_UPDATES.keySet());
    }

    public static void clearPendingUpdate(BlockPos pos) {
        if (pos == null) return;
        PENDING_UPDATES.remove(pos.toImmutable());
    }

    public static void clearPendingUpdates() {
        PENDING_UPDATES.clear();
        PENDING_UPDATE_ORDER.clear();
        QUEUED_POSITIONS.clear();
    }

    public static boolean requestData(BlockPos pos) {
        if (!ClientPlayNetworking.canSend(SYNC_BLOCK_ENTITY)) {
            return false;
        }

        PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBlockPos(pos);
        ClientPlayNetworking.send(SYNC_BLOCK_ENTITY, buf);
        return true;
    }

    private static class PcaUpdateBlockEntityData {
        final Identifier dimension;
        final BlockPos pos;
        final Map<Integer, ItemStack> items;
        final int slotCountHint;

        PcaUpdateBlockEntityData(Identifier dimension, BlockPos pos, Map<Integer, ItemStack> items, int slotCountHint) {
            this.dimension = dimension;
            this.pos = pos.toImmutable();
            this.items = items;
            this.slotCountHint = slotCountHint;
        }
    }

    private static PcaUpdateBlockEntityData readUpdateBlockEntity(PacketByteBuf buf) {
        try {
            Identifier dimension = buf.readIdentifier();
            BlockPos pos = buf.readBlockPos();
            NbtCompound nbt = buf.readNbt();
            if (nbt == null) {
                return null;
            }
            Map<Integer, ItemStack> items = extractItemsFromNbt(nbt);
            return new PcaUpdateBlockEntityData(dimension, pos, items, inferSlotCountFromItems(items));
        } catch (Exception e) {
            LOGGER.error("[LCF DEBUG] [PCA] Failed to parse update_block_entity: {}", e.toString());
            return null;
        }
    }

    private static void enqueueUpdate(PcaUpdateBlockEntityData data) {
        BlockPos pos = data.pos;
        PENDING_UPDATES.put(pos, data);
        if (QUEUED_POSITIONS.add(pos)) {
            PENDING_UPDATE_ORDER.offerLast(pos);
        }
    }

    private static void applyQueuedUpdate(MinecraftClient client, PcaUpdateBlockEntityData data) {
        if (client.world == null) return;
        if (!client.world.getRegistryKey().getValue().equals(data.dimension)) return;

        BlockPos pos = data.pos;
        Map<Integer, ItemStack> items = data.items;
        int slotCount = inferSlotCountFromUpdate(client, pos, data.slotCountHint);
        if (items != null && (slotCount > 0 || !items.isEmpty())) {
            RealContainerCache.acceptExternalContainerData(pos, items, slotCount);
            LOGGER.info("[LCF DEBUG] [PCA] Got {} items for {}", items.size(), pos.toShortString());
        }
    }

    private static Map<Integer, ItemStack> extractItemsFromNbt(NbtCompound nbt) {
        if (nbt == null) return null;

        NbtList itemsList = nbt.getList("Items", NbtElement.COMPOUND_TYPE);
        Map<Integer, ItemStack> items = new HashMap<>();
        for (int i = 0; i < itemsList.size(); i++) {
            NbtCompound tag = itemsList.getCompound(i);
            int slot = tag.getByte("Slot");
            ItemStack stack = ItemStack.fromNbt(tag);
            if (!stack.isEmpty()) {
                items.put(slot, stack);
            }
        }
        return items;
    }

    private static int inferSlotCountFromUpdate(MinecraftClient client, BlockPos pos, int slotCountHint) {
        int slotCount = slotCountHint;
        if (slotCount > 0) return slotCount;

        if (client.world == null) return -1;
        var state = client.world.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.block.ChestBlock ||
                state.getBlock() instanceof net.minecraft.block.BarrelBlock ||
                state.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock ||
                state.isOf(net.minecraft.block.Blocks.ENDER_CHEST)) {
            return 27;
        }
        if (state.isOf(net.minecraft.block.Blocks.HOPPER) ||
                state.isOf(net.minecraft.block.Blocks.BREWING_STAND)) {
            return 5;
        }
        if (state.isOf(net.minecraft.block.Blocks.FURNACE) ||
                state.isOf(net.minecraft.block.Blocks.BLAST_FURNACE) ||
                state.isOf(net.minecraft.block.Blocks.SMOKER)) {
            return 3;
        }
        if (state.isOf(net.minecraft.block.Blocks.DISPENSER) ||
                state.isOf(net.minecraft.block.Blocks.DROPPER)) {
            return 9;
        }
        return -1;
    }

    private static int inferSlotCountFromItems(Map<Integer, ItemStack> items) {
        if (items == null || items.isEmpty()) return -1;

        int maxSlot = -1;
        for (Integer slot : items.keySet()) {
            if (slot != null && slot > maxSlot) {
                maxSlot = slot;
            }
        }

        return maxSlot < 0 ? -1 : normalizeSlotCount(maxSlot + 1);
    }

    private static int normalizeSlotCount(int raw) {
        if (raw >= 54) return 54;
        if (raw >= 27) return 27;
        return Math.max(raw, -1);
    }
}
