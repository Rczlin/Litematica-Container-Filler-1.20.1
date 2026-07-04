package com.mimicenzymes.litematicafiller.network;

import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PCA (PluslsCarpetAddition) sync protocol handler.
 * Replaces old Servux-based container data sync with PCA protocol.
 *
 * Client -> Server: pca:sync_block_entity(BlockPos)
 * Server -> Client: pca:update_block_entity(dimension, BlockPos, NBT)
 */
public class PcaSyncHandler {
    private static final Logger LOGGER = LogManager.getLogger(Reference.MOD_ID);

    public static final Identifier ENABLE_PCA_SYNC_PROTOCOL  = new Identifier("pca", "enable_pca_sync_protocol");
    public static final Identifier DISABLE_PCA_SYNC_PROTOCOL = new Identifier("pca", "disable_pca_sync_protocol");
    public static final Identifier UPDATE_BLOCK_ENTITY       = new Identifier("pca", "update_block_entity");
    public static final Identifier SYNC_BLOCK_ENTITY         = new Identifier("pca", "sync_block_entity");

    public static final Map<BlockPos, Map<Integer, ItemStack>> INDEPENDENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> SLOT_COUNT_CACHE = new ConcurrentHashMap<>();

    private static boolean initialized = false;
    /** True when server has PCA protocol enabled. */
    public static volatile boolean enabled = false;

    // ---- Initialization (called from LitematicaContainerFillerClient) ----

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
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(UPDATE_BLOCK_ENTITY, (client, handler, buf, responseSender) -> {
            PcaUpdateBlockEntityData data = readUpdateBlockEntity(buf);
            if (data != null) {
                client.execute(() -> handleUpdateBlockEntity(client, data));
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            enabled = false;
        });
    }

    // ---- Packet reading & caching ----

    private static class PcaUpdateBlockEntityData {
        final Identifier dimension;
        final BlockPos pos;
        final NbtCompound nbt;
        PcaUpdateBlockEntityData(Identifier d, BlockPos p, NbtCompound n) { dimension = d; pos = p; nbt = n; }
    }

    private static PcaUpdateBlockEntityData readUpdateBlockEntity(PacketByteBuf buf) {
        try {
            Identifier dimension = buf.readIdentifier();
            BlockPos pos = buf.readBlockPos();
            NbtCompound nbt = buf.readNbt();
            return nbt != null ? new PcaUpdateBlockEntityData(dimension, pos, nbt) : null;
        } catch (Exception e) {
            LOGGER.error("[LCF DEBUG] [PCA] Failed to parse update_block_entity: {}", e.toString());
            return null;
        }
    }

    private static void handleUpdateBlockEntity(MinecraftClient client, PcaUpdateBlockEntityData data) {
        if (client.world == null) return;
        if (!client.world.getRegistryKey().getValue().equals(data.dimension)) return;

        BlockPos pos = data.pos.toImmutable();

        // Update client-side BlockEntity (so MiniHUD etc. also see fresh data)
        BlockEntity be = client.world.getBlockEntity(pos);
        if (be != null) {
            try { be.readNbt(data.nbt); } catch (Exception ignored) {}
        }

        Map<Integer, ItemStack> items = extractItemsFromNbt(data.nbt);
        int slotCount = inferSlotCountFromUpdate(client, pos, be, items);
        if (items != null && (slotCount > 0 || !items.isEmpty())) {
            putIndependentCache(pos, items, slotCount);
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
            if (!stack.isEmpty()) items.put(slot, stack);
        }
        return items;
    }

    private static int inferSlotCountFromUpdate(MinecraftClient client, BlockPos pos, BlockEntity be, Map<Integer, ItemStack> items) {
        int slotCount = inferSlotCountFromItems(items);
        if (slotCount > 0) return slotCount;

        if (be instanceof Inventory inventory) {
            return normalizeSlotCount(inventory.size());
        }

        slotCount = inferSlotCountFromObjectGeneric(be);
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

    // ---- Public API ----

    public static Map<Integer, ItemStack> getCachedData(BlockPos pos) {
        Map<Integer, ItemStack> pcaData = INDEPENDENT_CACHE.get(pos);
        if (pcaData != null) return pcaData;

        // MiniHUD supplementary cache
        checkMinihud();
        if (minihudCacheClass != null) {
            try {
                for (Field f : minihudCacheMapFields) {
                    Map<?, ?> map = (Map<?, ?>) f.get(null);
                    if (map != null && map.containsKey(pos)) {
                        Object result = map.get(pos);
                        if (result != null) {
                            rememberSlotCount(pos, result);
                            Map<Integer, ItemStack> extracted = extractItemsFromObject(result);
                            if (extracted != null && !extracted.isEmpty()) return extracted;
                        }
                    }
                }
                Object ci = getMinihudCacheInstance();
                for (Method m : minihudCacheLookupMethods) {
                    boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                    if (!isStatic && ci == null) continue;
                    Object result = isStatic ? m.invoke(null, pos) : m.invoke(ci, pos);
                    if (result != null) {
                        rememberSlotCount(pos, result);
                        Map<Integer, ItemStack> extracted = extractItemsFromObject(result);
                        if (extracted != null && !extracted.isEmpty()) return extracted;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static int getCachedSlotCount(BlockPos pos) {
        Integer sc = SLOT_COUNT_CACHE.get(pos);
        return sc != null ? sc : inferSlotCountFromItems(INDEPENDENT_CACHE.get(pos));
    }

    public static int getIndependentCacheEntryCount() {
        return INDEPENDENT_CACHE.size();
    }

    public static Set<BlockPos> getCachedPositionsSnapshot() {
        return new HashSet<>(INDEPENDENT_CACHE.keySet());
    }

    public static void clearCachedData(BlockPos pos) {
        if (pos == null) return;
        BlockPos key = pos.toImmutable();
        INDEPENDENT_CACHE.remove(key);
        SLOT_COUNT_CACHE.remove(key);
    }

    public static void clearAllCachedData() {
        INDEPENDENT_CACHE.clear();
        SLOT_COUNT_CACHE.clear();
    }

    public static void applyConfiguredCacheLimit() {
        trimToConfiguredLimit();
    }

    /** Attempt to request container data. PCA first, MiniHUD sender as fallback. */
    public static boolean requestData(BlockPos pos) {
        if (ClientPlayNetworking.canSend(SYNC_BLOCK_ENTITY)) {
            PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeBlockPos(pos);
            ClientPlayNetworking.send(SYNC_BLOCK_ENTITY, buf);
            return true;
        }

        checkMinihud();
        if (minihudSenderClass != null) {
            try {
                for (Method m : minihudSenderMethods) { m.invoke(null, pos); return true; }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // ---- Internal cache ----

    private static void putIndependentCache(BlockPos pos, Map<Integer, ItemStack> items, int slotCount) {
        INDEPENDENT_CACHE.put(pos, items);
        if (slotCount > 0) {
            SLOT_COUNT_CACHE.merge(pos, slotCount, Math::max);
        } else {
            rememberSlotCount(pos, items);
        }
        trimToConfiguredLimit();
    }

    private static void trimToConfiguredLimit() {
        int limit = getMaxIndependentCacheSize();
        while (INDEPENDENT_CACHE.size() > limit) {
            var it = INDEPENDENT_CACHE.keySet().iterator();
            if (!it.hasNext()) break;
            BlockPos evict = it.next();
            it.remove();
            SLOT_COUNT_CACHE.remove(evict);
        }
    }

    private static int getMaxIndependentCacheSize() {
        return Math.max(256, Configs.getConfiguredCacheEntryLimit());
    }

    private static void rememberSlotCount(BlockPos pos, Object inventoryData) {
        int n = inferSlotCountFromObjectGeneric(inventoryData);
        if (n > 0) SLOT_COUNT_CACHE.merge(pos, n, Math::max);
    }

    // ---- Slot count helpers ----

    private static int inferSlotCountFromObjectGeneric(Object obj) {
        if (obj == null) return -1;
        if (obj instanceof Map<?, ?> m) return inferSlotCountFromMap(m);
        if (obj instanceof java.util.Collection<?> c) return normalizeSlotCount(c.size());
        if (obj instanceof ItemStack[] a) return normalizeSlotCount(a.length);
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof java.util.Collection<?> c) { int n = normalizeSlotCount(c.size()); if (n > 0) return n; }
                if (v instanceof ItemStack[] a) { int n = normalizeSlotCount(a.length); if (n > 0) return n; }
                if (v instanceof Map<?, ?> m) { int n = inferSlotCountFromMap(m); if (n > 0) return n; }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static int inferSlotCountFromMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return -1;
        int maxSlot = -1;
        for (Object key : map.keySet()) { int s = parseSlotKey(key); if (s > maxSlot) maxSlot = s; }
        return maxSlot < 0 ? -1 : normalizeSlotCount(maxSlot + 1);
    }

    private static int inferSlotCountFromItems(Map<Integer, ItemStack> items) {
        if (items == null || items.isEmpty()) return -1;
        int maxSlot = -1;
        for (Integer slot : items.keySet()) { if (slot != null && slot > maxSlot) maxSlot = slot; }
        return maxSlot < 0 ? -1 : normalizeSlotCount(maxSlot + 1);
    }

    private static int parseSlotKey(Object key) {
        if (key instanceof Number n) return n.intValue();
        if (key instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return -1;
    }

    private static int normalizeSlotCount(int raw) {
        if (raw >= 54) return 54;
        if (raw >= 27) return 27;
        return Math.max(raw, -1);
    }

    // ---- MiniHUD reflection (supplementary, kept for compatibility) ----

    private static boolean minihudChecked;
    private static Class<?> minihudCacheClass, minihudSenderClass;
    private static Field[] minihudCacheMapFields = new Field[0];
    private static Method minihudCacheInstanceGetter;
    private static Method[] minihudCacheLookupMethods = new Method[0];
    private static Method[] minihudSenderMethods = new Method[0];
    private static Object minihudCacheInstance;

    private static void checkMinihud() {
        if (minihudChecked) return;
        minihudChecked = true;
        for (String c : new String[]{"fi.dy.masa.minihud.feature.InventoryCache", "fi.dy.masa.minihud.inventory.InventoryCache", "fi.dy.masa.minihud.util.InventoryCache"}) {
            try { minihudCacheClass = Class.forName(c); if (minihudCacheClass != null) initMinihudCacheAccessors(); break; } catch (Throwable ignored) {}
        }
        for (String c : new String[]{"fi.dy.masa.minihud.network.ClientPacketSender", "fi.dy.masa.minihud.network.PacketSender"}) {
            try { minihudSenderClass = Class.forName(c); if (minihudSenderClass != null) initMinihudSenderAccessors(); break; } catch (Throwable ignored) {}
        }
    }

    private static void initMinihudCacheAccessors() {
        List<Field> mapFields = new ArrayList<>();
        List<Method> lookupMethods = new ArrayList<>();
        for (Field f : minihudCacheClass.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) { f.setAccessible(true); mapFields.add(f); }
        }
        for (Method m : minihudCacheClass.getDeclaredMethods()) {
            if ("getInstance".equals(m.getName()) && m.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) { m.setAccessible(true); minihudCacheInstanceGetter = m; }
            else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class) { m.setAccessible(true); lookupMethods.add(m); }
        }
        minihudCacheMapFields = mapFields.toArray(new Field[0]);
        minihudCacheLookupMethods = lookupMethods.toArray(new Method[0]);
    }

    private static void initMinihudSenderAccessors() {
        List<Method> senderMethods = new ArrayList<>();
        for (Method m : minihudSenderClass.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class) {
                String name = m.getName().toLowerCase();
                if (name.contains("container") || name.contains("inventory") || name.contains("request") || name.contains("data") || name.contains("sync")) {
                    m.setAccessible(true); senderMethods.add(m);
                }
            }
        }
        minihudSenderMethods = senderMethods.toArray(new Method[0]);
    }

    private static Object getMinihudCacheInstance() {
        if (minihudCacheInstance != null || minihudCacheInstanceGetter == null) return minihudCacheInstance;
        try { minihudCacheInstance = minihudCacheInstanceGetter.invoke(null); } catch (Throwable ignored) {}
        return minihudCacheInstance;
    }

    private static Map<Integer, ItemStack> extractItemsFromObject(Object obj) {
        if (obj == null) return null;
        Map<Integer, ItemStack> map = new HashMap<>();
        if (obj instanceof java.util.Collection<?> list) { int slot = 0; for (Object item : list) { if (item instanceof ItemStack s && !s.isEmpty()) map.put(slot, s.copy()); slot++; } if (!map.isEmpty()) return map; }
        else if (obj instanceof ItemStack[] arr) { for (int i = 0; i < arr.length; i++) { if (arr[i] != null && !arr[i].isEmpty()) map.put(i, arr[i].copy()); } if (!map.isEmpty()) return map; }
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof java.util.Collection<?> list) { int slot = 0; for (Object item : list) { if (item instanceof ItemStack s && !s.isEmpty()) map.put(slot, s.copy()); slot++; } if (!map.isEmpty()) return map; }
                else if (v instanceof ItemStack[] arr) { for (int i = 0; i < arr.length; i++) { if (arr[i] != null && !arr[i].isEmpty()) map.put(i, arr[i].copy()); } if (!map.isEmpty()) return map; }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
