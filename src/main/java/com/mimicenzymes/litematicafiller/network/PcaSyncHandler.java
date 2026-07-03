package com.mimicenzymes.litematicafiller.network;

import com.mimicenzymes.litematicafiller.LogUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PCA (PluslsCarpetAddition) sync protocol handler.
 */
public class PcaSyncHandler {
    private static final int MAX_INDEPENDENT_CACHE_SIZE = 1024;

    public static final Identifier ENABLE_PCA_SYNC_PROTOCOL  = new Identifier("pca", "enable_pca_sync_protocol");
    public static final Identifier DISABLE_PCA_SYNC_PROTOCOL = new Identifier("pca", "disable_pca_sync_protocol");
    public static final Identifier UPDATE_BLOCK_ENTITY       = new Identifier("pca", "update_block_entity");
    public static final Identifier SYNC_BLOCK_ENTITY         = new Identifier("pca", "sync_block_entity");

    public static final Map<BlockPos, Map<Integer, ItemStack>> INDEPENDENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> SLOT_COUNT_CACHE = new ConcurrentHashMap<>();

    private static boolean initialized = false;
    public static boolean enabled = false;

    // ---- Initialization ----

    public static void init() {
        if (initialized) return;
        initialized = true;
        LogUtil.info("[PCA] Registering channel handlers");

        ClientPlayNetworking.registerGlobalReceiver(ENABLE_PCA_SYNC_PROTOCOL, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                if (!client.isInSingleplayer()) {
                    LogUtil.info("[PCA] enabled (server sent enable_pca_sync_protocol)");
                    enabled = true;
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(DISABLE_PCA_SYNC_PROTOCOL, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                LogUtil.info("[PCA] disabled (server sent disable_pca_sync_protocol)");
                enabled = false;
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(UPDATE_BLOCK_ENTITY, (client, handler, buf, responseSender) -> {
            PcaUpdateBlockEntityData data = readUpdateBlockEntity(buf);
            if (data != null) {
                client.execute(() -> handleUpdateBlockEntity(client, data));
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> enabled = false);
    }

    // ---- Packet reading ----

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
            if (nbt == null) return null;
            return new PcaUpdateBlockEntityData(dimension, pos, nbt);
        } catch (Exception e) {
            LogUtil.error("[PCA] Failed to parse update_block_entity: %s", e.getMessage());
            return null;
        }
    }

    private static void handleUpdateBlockEntity(MinecraftClient client, PcaUpdateBlockEntityData data) {
        if (client.world == null) return;
        Identifier currentDimension = client.world.getRegistryKey().getValue();
        if (!currentDimension.equals(data.dimension)) return;

        BlockPos pos = data.pos.toImmutable();
        NbtCompound nbt = data.nbt;

        BlockEntity blockEntity = client.world.getBlockEntity(pos);
        if (blockEntity != null) {
            try { blockEntity.readNbt(nbt); } catch (Exception ignored) {}
        }

        Map<Integer, ItemStack> items = extractItemsFromNbt(nbt);
        if (items != null && !items.isEmpty()) {
            putIndependentCache(pos, items);
            LogUtil.info("[PCA] Received %d items for %s", items.size(), pos.toShortString());
        } else {
            LogUtil.debug("[PCA] Received empty container at %s", pos.toShortString());
        }
    }

    private static Map<Integer, ItemStack> extractItemsFromNbt(NbtCompound nbt) {
        if (nbt == null) return null;
        NbtList itemsList = nbt.getList("Items", NbtElement.COMPOUND_TYPE);
        if (itemsList.isEmpty()) return null;

        Map<Integer, ItemStack> items = new HashMap<>();
        for (int i = 0; i < itemsList.size(); i++) {
            NbtCompound itemTag = itemsList.getCompound(i);
            int slot = itemTag.getByte("Slot");
            ItemStack stack = ItemStack.fromNbt(itemTag);
            if (!stack.isEmpty()) items.put(slot, stack);
        }
        return items.isEmpty() ? null : items;
    }

    // ---- Public API ----

    public static Map<Integer, ItemStack> getCachedData(BlockPos pos) {
        Map<Integer, ItemStack> pcaData = INDEPENDENT_CACHE.get(pos);
        if (pcaData != null) return pcaData;

        // MiniHUD supplementary cache
        checkMinihud();
        if (minihudCacheClass != null) {
            try {
                for (Field field : minihudCacheMapFields) {
                    Map<?, ?> map = (Map<?, ?>) field.get(null);
                    if (map != null) {
                        Object result = map.get(pos);
                        if (result != null) {
                            rememberSlotCount(pos, result);
                            Map<Integer, ItemStack> extracted = extractItemsFromObject(result);
                            if (extracted != null && !extracted.isEmpty()) return extracted;
                        }
                    }
                }
                Object cacheInstance = getMinihudCacheInstance();
                for (Method method : minihudCacheLookupMethods) {
                    boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
                    if (!isStatic && cacheInstance == null) continue;
                    Object result = isStatic ? method.invoke(null, pos) : method.invoke(cacheInstance, pos);
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
        Integer slotCount = SLOT_COUNT_CACHE.get(pos);
        if (slotCount != null) return slotCount;
        return inferSlotCountFromItems(INDEPENDENT_CACHE.get(pos));
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

    /**
     * Request container data via PCA. PCA first, MiniHUD sender as fallback.
     */
    public static boolean requestData(BlockPos pos) {
        if (ClientPlayNetworking.canSend(SYNC_BLOCK_ENTITY)) {
            PacketByteBuf sendBuf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
            sendBuf.writeBlockPos(pos);
            ClientPlayNetworking.send(SYNC_BLOCK_ENTITY, sendBuf);
            return true;
        }

        checkMinihud();
        if (minihudSenderClass != null) {
            try {
                for (Method method : minihudSenderMethods) {
                    method.invoke(null, pos);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // ---- Internal cache management ----

    private static void putIndependentCache(BlockPos pos, Map<Integer, ItemStack> items) {
        if (INDEPENDENT_CACHE.size() >= MAX_INDEPENDENT_CACHE_SIZE) {
            var iterator = INDEPENDENT_CACHE.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.remove();
                SLOT_COUNT_CACHE.remove(iterator.next());
            }
        }
        INDEPENDENT_CACHE.put(pos, items);
        rememberSlotCount(pos, items);
    }

    private static void rememberSlotCount(BlockPos pos, Object inventoryData) {
        int slotCount = inferSlotCountFromObjectGeneric(inventoryData);
        if (slotCount > 0) SLOT_COUNT_CACHE.merge(pos, slotCount, Math::max);
    }

    // ---- Slot count inference ----

    private static int inferSlotCountFromObjectGeneric(Object obj) {
        if (obj == null) return -1;
        if (obj instanceof Map<?, ?> map) return inferSlotCountFromMap(map);
        if (obj instanceof java.util.Collection<?> list) return normalizeSlotCount(list.size());
        if (obj instanceof ItemStack[] arr) return normalizeSlotCount(arr.length);
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof java.util.Collection<?> list) { int c = normalizeSlotCount(list.size()); if (c > 0) return c; }
                if (val instanceof ItemStack[] arr) { int c = normalizeSlotCount(arr.length); if (c > 0) return c; }
                if (val instanceof Map<?, ?> map) { int c = inferSlotCountFromMap(map); if (c > 0) return c; }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static int inferSlotCountFromMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return -1;
        int maxSlot = -1;
        for (Object key : map.keySet()) { int slot = parseSlotKey(key); if (slot > maxSlot) maxSlot = slot; }
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

    private static int normalizeSlotCount(int rawCount) {
        if (rawCount >= 54) return 54;
        if (rawCount >= 27) return 27;
        return rawCount > 0 ? rawCount : -1;
    }

    // ---- MiniHUD reflection (supplementary) ----

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
            if (m.getName().equals("getInstance") && m.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) { m.setAccessible(true); minihudCacheInstanceGetter = m; }
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
                if (name.contains("container") || name.contains("inventory") || name.contains("request") || name.contains("data") || name.contains("sync")) { m.setAccessible(true); senderMethods.add(m); }
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
        if (obj instanceof java.util.Collection<?> list) { int slot = 0; for (Object item : list) { if (item instanceof ItemStack stack && !stack.isEmpty()) map.put(slot, stack.copy()); slot++; } if (!map.isEmpty()) return map; }
        else if (obj instanceof ItemStack[] arr) { for (int i = 0; i < arr.length; i++) { if (arr[i] != null && !arr[i].isEmpty()) map.put(i, arr[i].copy()); } if (!map.isEmpty()) return map; }
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof java.util.Collection<?> list) { int slot = 0; for (Object item : list) { if (item instanceof ItemStack stack && !stack.isEmpty()) map.put(slot, stack.copy()); slot++; } if (!map.isEmpty()) return map; }
                else if (val instanceof ItemStack[] arr) { for (int i = 0; i < arr.length; i++) { if (arr[i] != null && !arr[i].isEmpty()) map.put(i, arr[i].copy()); } if (!map.isEmpty()) return map; }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
