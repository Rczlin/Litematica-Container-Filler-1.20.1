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

import com.mimicenzymes.litematicafiller.config.Configs;

/**
 * PCA (PluslsCarpetAddition) sync protocol handler.
 *
 * Client -> Server: pca:sync_block_entity(BlockPos)
 * Server -> Client: pca:update_block_entity(dimension, BlockPos, NBT)
 */
public class PcaSyncHandler {
    private static final Logger LOGGER = LogManager.getLogger(Reference.MOD_ID);
    private static final int MAX_UPDATES_PER_TICK = 128;
    private static final long REQUEST_TIMEOUT_TICKS = 20L;

    public static final Identifier ENABLE_PCA_SYNC_PROTOCOL  = new Identifier("pca", "enable_pca_sync_protocol");
    public static final Identifier DISABLE_PCA_SYNC_PROTOCOL = new Identifier("pca", "disable_pca_sync_protocol");
    public static final Identifier UPDATE_BLOCK_ENTITY       = new Identifier("pca", "update_block_entity");
    public static final Identifier SYNC_BLOCK_ENTITY         = new Identifier("pca", "sync_block_entity");

    private static final Map<BlockPos, PcaUpdateBlockEntityData> PENDING_UPDATES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<BlockPos> PENDING_UPDATE_ORDER = new ConcurrentLinkedDeque<>();
    private static final Set<BlockPos> QUEUED_POSITIONS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedDeque<BlockPos> PENDING_REQUEST_ORDER = new ConcurrentLinkedDeque<>();
    private static final Set<BlockPos> QUEUED_REQUEST_POSITIONS = ConcurrentHashMap.newKeySet();
    private static final Map<BlockPos, Long> IN_FLIGHT_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> RETRY_COOLDOWNS = new ConcurrentHashMap<>();

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
        if (client == null || client.world == null) {
            return;
        }

        long worldTime = client.world.getTime();
        expireTimedOutRequests(worldTime);
        pumpQueuedRequests(worldTime);

        if (PENDING_UPDATE_ORDER.isEmpty()) {
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
        BlockPos key = pos.toImmutable();
        PENDING_UPDATES.remove(key);
        clearRequestState(key);
    }

    public static void clearPendingUpdates() {
        PENDING_UPDATES.clear();
        PENDING_UPDATE_ORDER.clear();
        QUEUED_POSITIONS.clear();
        PENDING_REQUEST_ORDER.clear();
        QUEUED_REQUEST_POSITIONS.clear();
        IN_FLIGHT_REQUESTS.clear();
        RETRY_COOLDOWNS.clear();
    }

    public static boolean requestData(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || !ClientPlayNetworking.canSend(SYNC_BLOCK_ENTITY)) {
            return false;
        }

        BlockPos key = pos.toImmutable();
        if (IN_FLIGHT_REQUESTS.containsKey(key) || QUEUED_REQUEST_POSITIONS.contains(key)) {
            return true;
        }

        long worldTime = client.world.getTime();
        long retryAt = RETRY_COOLDOWNS.getOrDefault(key, Long.MIN_VALUE);
        if (worldTime < retryAt) {
            return false;
        }

        RETRY_COOLDOWNS.remove(key);
        if (QUEUED_REQUEST_POSITIONS.add(key)) {
            PENDING_REQUEST_ORDER.offerLast(key);
        }
        return true;
    }

    private static void pumpQueuedRequests(long worldTime) {
        int sent = 0;
        int requestBudget = Configs.PCA_SYNC_REQUESTS_PER_TICK.getIntegerValue();
        boolean unlimited = requestBudget == 0;

        while ((unlimited || sent < requestBudget) && !PENDING_REQUEST_ORDER.isEmpty()) {
            BlockPos pos = PENDING_REQUEST_ORDER.pollFirst();
            if (pos == null) {
                continue;
            }

            QUEUED_REQUEST_POSITIONS.remove(pos);
            if (IN_FLIGHT_REQUESTS.containsKey(pos)) {
                continue;
            }

            long retryAt = RETRY_COOLDOWNS.getOrDefault(pos, Long.MIN_VALUE);
            if (worldTime < retryAt) {
                continue;
            }

            if (!ClientPlayNetworking.canSend(SYNC_BLOCK_ENTITY)) {
                if (QUEUED_REQUEST_POSITIONS.add(pos)) {
                    PENDING_REQUEST_ORDER.offerFirst(pos);
                }
                break;
            }

            PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeBlockPos(pos);
            ClientPlayNetworking.send(SYNC_BLOCK_ENTITY, buf);
            IN_FLIGHT_REQUESTS.put(pos, worldTime);
            RETRY_COOLDOWNS.remove(pos);
            sent++;
        }
    }

    private static void expireTimedOutRequests(long worldTime) {
        for (Map.Entry<BlockPos, Long> entry : IN_FLIGHT_REQUESTS.entrySet()) {
            Long requestedAt = entry.getValue();
            if (requestedAt == null || worldTime - requestedAt < REQUEST_TIMEOUT_TICKS) {
                continue;
            }

            BlockPos pos = entry.getKey();
            if (pos == null || !IN_FLIGHT_REQUESTS.remove(pos, requestedAt)) {
                continue;
            }

            int cooldown = Configs.PCA_SYNC_RETRY_COOLDOWN_TICKS.getIntegerValue();
            if (cooldown > 0) {
                RETRY_COOLDOWNS.put(pos, worldTime + cooldown);
            } else {
                RETRY_COOLDOWNS.remove(pos);
            }
        }
    }

    private static void clearRequestState(BlockPos pos) {
        if (pos == null) return;
        QUEUED_REQUEST_POSITIONS.remove(pos);
        IN_FLIGHT_REQUESTS.remove(pos);
        RETRY_COOLDOWNS.remove(pos);
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
        clearRequestState(pos);
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
