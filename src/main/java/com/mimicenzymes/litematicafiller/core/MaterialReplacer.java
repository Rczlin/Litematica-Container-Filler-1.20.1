package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.dependency.TechUtilsDeceiver;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;

public class MaterialReplacer {

    public static boolean isSaving = false;
    private static boolean isPatching = false;

    private static class ItemRule {
        final Item item;
        final String name;

        ItemRule(Item item, String name) {
            this.item = item;
            this.name = name;
        }

        boolean matches(ItemStack stack) {
            if (stack.getItem() != this.item) return false;
            Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);

            if (this.name == null) {
                return true;
            } else {
                if (customName == null) return false;
                return customName.getString().contains(this.name);
            }
        }
    }

    private static class Replacement {
        final ItemRule source;
        final ItemRule target;
        Replacement(ItemRule source, ItemRule target) { this.source = source; this.target = target; }
    }

    private static final List<Replacement> REPLACEMENTS = new ArrayList<>();
    private static int lastHash = -1;
    private static int lastPlacementsHash = -1;

    private static final Map<NbtCompound, NbtList> ORIGINAL_ITEMS_BACKUP = new IdentityHashMap<>();

    public static void checkReload() {
        if (isSaving || isPatching) return;

        List<String> strings = Configs.MATERIAL_REPLACEMENTS.getStrings();
        int currentHash = strings.hashCode();

        int currentPlacementsHash = 0;
        try {
            Object manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
            java.util.Collection<?> placements = (java.util.Collection<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
            if (placements != null) {
                currentPlacementsHash = placements.hashCode();
            }
        } catch (Exception e) {}

        if (currentHash != lastHash || currentPlacementsHash != lastPlacementsHash) {
            isPatching = true;
            try {
                lastHash = currentHash;
                lastPlacementsHash = currentPlacementsHash;

                REPLACEMENTS.clear();
                for (String rule : strings) {
                    if (rule == null || !rule.contains("->")) continue;
                    String[] parts = rule.split("->");
                    if (parts.length != 2) continue;

                    ItemRule source = parseRule(parts[0].trim());
                    ItemRule target = parseRule(parts[1].trim());

                    if (source.item != net.minecraft.item.Items.AIR && target.item != net.minecraft.item.Items.AIR) {
                        REPLACEMENTS.add(new Replacement(source, target));
                    }
                }

                applyToMemory();
            } finally {
                isPatching = false;
            }
        }
    }

    public static void applyToMemory() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.world == null) return;

        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

        // 1. 无损还原底层 NBT
        for (Map.Entry<NbtCompound, NbtList> entry : ORIGINAL_ITEMS_BACKUP.entrySet()) {
            entry.getKey().put("Items", entry.getValue().copy());
            entry.getKey().put("items", entry.getValue().copy());
        }
        ORIGINAL_ITEMS_BACKUP.clear();

        List<NbtCompound> allNbts = new ArrayList<>();
        Map<net.minecraft.util.math.BlockPos, NbtCompound> posToNbt = new HashMap<>();

        try {
            Object manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
            java.util.Collection<?> placements = (java.util.Collection<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
            if (placements != null) {
                java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                for (Object p : placements) {
                    List<NbtCompound> placementNbts = new ArrayList<>();
                    extractNbtsFromMemory(p, placementNbts, visited, 0);
                    allNbts.addAll(placementNbts);

                    net.minecraft.util.math.BlockPos origin = extractOrigin(p);
                    if (origin == null) origin = net.minecraft.util.math.BlockPos.ORIGIN;

                    // 坐标绝对推演：将内存中的独立 NBT 映射到它在游戏里的真实绝对坐标
                    for (NbtCompound nbt : placementNbts) {
                        if (nbt.contains("x") && nbt.contains("y") && nbt.contains("z")) {
                            int nx = getIntFromNbt(nbt.get("x"));
                            int ny = getIntFromNbt(nbt.get("y"));
                            int nz = getIntFromNbt(nbt.get("z"));

                            net.minecraft.util.math.BlockPos directPos = new net.minecraft.util.math.BlockPos(nx, ny, nz);
                            net.minecraft.util.math.BlockPos offsetPos = origin.add(nx, ny, nz);

                            net.minecraft.util.math.BlockPos worldPos = directPos;
                            if (schematicWorld != null) {
                                if (schematicWorld.getBlockState(offsetPos).hasBlockEntity()) {
                                    worldPos = offsetPos;
                                } else if (schematicWorld.getBlockState(directPos).hasBlockEntity()) {
                                    worldPos = directPos;
                                } else {
                                    worldPos = (Math.abs(nx) < 2048 && Math.abs(ny) < 1024 && Math.abs(nz) < 2048) ? offsetPos : directPos;
                                }
                            }
                            posToNbt.put(worldPos, nbt);
                        }
                    }
                }
            }
        } catch (Exception e) {}

        // 2. 底层 NBT 篡改执行
        if (!REPLACEMENTS.isEmpty()) {
            for (NbtCompound nbt : allNbts) {
                String targetKey = nbt.contains("Items") ? "Items" : (nbt.contains("items") ? "items" : null);
                if (targetKey != null) {
                    net.minecraft.nbt.NbtElement itemsElem = nbt.get(targetKey);
                    if (itemsElem instanceof NbtList list) {
                        ORIGINAL_ITEMS_BACKUP.put(nbt, list.copy());
                        replaceInNbtList((NbtList) nbt.get(targetKey), client.world.getRegistryManager());
                    }
                }
            }
        }

        // ==============================================================================================
        // 【核心破局：活体对象绝对同步 (Live Object Sync)】
        // 抛弃所有移动、重载、标记脏数据的伪刷新！
        // 直接潜入 Litematica 加载好的投影虚拟世界，强行覆盖所有已实例化容器（BlockEntity）的内部内存数组。
        // TechUtils 和高亮扫描器读取的就是这批活体对象，修改后视觉与逻辑将瞬间更新，连1.21.4的引擎都无法阻挡！
        // ==============================================================================================
        if (schematicWorld != null) {
            for (Map.Entry<net.minecraft.util.math.BlockPos, NbtCompound> entry : posToNbt.entrySet()) {
                net.minecraft.block.entity.BlockEntity be = schematicWorld.getBlockEntity(entry.getKey());

                // 只要目标是个容器（箱子、合成器等），当场执行内存覆盖
                if (be instanceof net.minecraft.inventory.Inventory inv) {
                    Map<Integer, ItemStack> targetItems = RealContainerCache.parseNbtInventory(entry.getValue(), client.world.getRegistryManager());

                    // 掏空容器当前的旧数据
                    for (int i = 0; i < inv.size(); i++) {
                        inv.setStack(i, ItemStack.EMPTY);
                    }

                    // 注入篡改后的新数据
                    for (Map.Entry<Integer, ItemStack> itemEntry : targetItems.entrySet()) {
                        if (itemEntry.getKey() < inv.size()) {
                            inv.setStack(itemEntry.getKey(), itemEntry.getValue().copy());
                        }
                    }
                }
            }
        }

        // 3. 全局缓存强制肃清，逼迫所有功能组件立刻重新读取刚才修改的“活体对象”
        RealContainerCache.clear();
        com.mimicenzymes.litematicafiller.render.HighlightScanner.getHighlights().clear();
        try { TechUtilsDeceiver.forceTechUtilsUpdate(); } catch (Throwable ignored) {}
    }

    private static int getIntFromNbt(net.minecraft.nbt.NbtElement elem) {
        if (elem instanceof net.minecraft.nbt.AbstractNbtNumber num) return num.intValue();
        return 0;
    }

    private static net.minecraft.util.math.BlockPos extractOrigin(Object placement) {
        if (placement == null) return null;
        try {
            for (java.lang.reflect.Method m : placement.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == net.minecraft.util.math.BlockPos.class) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("origin") || name.contains("pos")) {
                        return (net.minecraft.util.math.BlockPos) m.invoke(placement);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void extractNbtsFromMemory(Object obj, List<NbtCompound> results, java.util.Set<Object> visited, int depth) {
        if (obj == null || depth > 25 || !visited.add(obj)) return;

        if (obj instanceof NbtCompound c) {
            if (c.contains("Items") || c.contains("items")) results.add(c);
            for (String key : c.getKeys()) {
                net.minecraft.nbt.NbtElement el = c.get(key);
                if (el instanceof NbtCompound child) extractNbtsFromMemory(child, results, visited, depth + 1);
                else if (el instanceof NbtList list) {
                    for (int i = 0; i < list.size(); i++) extractNbtsFromMemory(list.get(i), results, visited, depth + 1);
                }
            }
            return;
        }

        if (obj instanceof net.minecraft.block.entity.BlockEntity) return;

        if (obj instanceof Map<?, ?> map) {
            for (Object val : map.values()) extractNbtsFromMemory(val, results, visited, depth + 1);
            return;
        }
        if (obj instanceof Iterable<?> iter) {
            for (Object val : iter) extractNbtsFromMemory(val, results, visited, depth + 1);
            return;
        }
        if (obj.getClass().isArray() && !obj.getClass().getComponentType().isPrimitive()) {
            for (Object val : (Object[]) obj) extractNbtsFromMemory(val, results, visited, depth + 1);
            return;
        }

        String pkg = obj.getClass().getPackage() != null ? obj.getClass().getPackage().getName() : "";
        if (!pkg.startsWith("fi.dy.masa")) return;

        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                String fname = f.getName().toLowerCase();
                if (fname.contains("parent") || fname.contains("screen") || fname.contains("gui") || fname.contains("client") || fname.contains("world") || fname.contains("manager")) continue;
                try {
                    f.setAccessible(true);
                    extractNbtsFromMemory(f.get(obj), results, visited, depth + 1);
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static ItemRule parseRule(String str) {
        String idStr = str;
        String nameStr = null;
        if (str.contains("#")) {
            String[] parts = str.split("#", 2);
            idStr = parts[0].trim();
            nameStr = parts[1].trim();
            if (nameStr.isEmpty()) nameStr = null;
        }
        if (!idStr.contains(":")) idStr = "minecraft:" + idStr;
        Identifier id = Identifier.tryParse(idStr);
        Item item = net.minecraft.item.Items.AIR;
        if (id != null && Registries.ITEM.containsId(id)) item = Registries.ITEM.get(id);
        return new ItemRule(item, nameStr);
    }

    public static ItemStack replaceSingleStack(ItemStack original) {
        if (original == null || original.isEmpty()) return original;
        checkReload();
        if (REPLACEMENTS.isEmpty()) return original;

        for (Replacement rep : REPLACEMENTS) {
            if (rep.source.matches(original)) {
                ItemStack newStack = new ItemStack(rep.target.item, original.getCount());
                if (original.contains(DataComponentTypes.CUSTOM_NAME)) newStack.set(DataComponentTypes.CUSTOM_NAME, original.get(DataComponentTypes.CUSTOM_NAME));
                if (original.contains(DataComponentTypes.ENCHANTMENTS)) newStack.set(DataComponentTypes.ENCHANTMENTS, original.get(DataComponentTypes.ENCHANTMENTS));
                if (original.contains(DataComponentTypes.CUSTOM_DATA)) newStack.set(DataComponentTypes.CUSTOM_DATA, original.get(DataComponentTypes.CUSTOM_DATA));
                if (rep.target.name != null) newStack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(rep.target.name));
                return newStack;
            }
        }
        return original;
    }

    public static void replaceInMap(Map<Integer, ItemStack> inventory) {
        if (inventory == null || inventory.isEmpty()) return;
        checkReload();
        if (REPLACEMENTS.isEmpty()) return;
        for (Map.Entry<Integer, ItemStack> entry : inventory.entrySet()) {
            ItemStack replaced = replaceSingleStack(entry.getValue());
            if (replaced != entry.getValue()) entry.setValue(replaced);
        }
    }

    public static void replaceInNbtList(NbtList itemsList, net.minecraft.registry.RegistryWrapper.WrapperLookup registries) {
        for (int i = 0; i < itemsList.size(); i++) {
            if (itemsList.get(i) instanceof NbtCompound itemTag) {
                ItemStack original = ItemStack.EMPTY;
                try {
                    original = ItemStack.OPTIONAL_CODEC.parse(registries.getOps(net.minecraft.nbt.NbtOps.INSTANCE), itemTag).resultOrPartial().orElse(ItemStack.EMPTY);
                } catch (Exception ignored) {}

                if (original.isEmpty() && itemTag.contains("id")) {
                    String idStr = itemTag.get("id").toString().replace("\"", "");
                    Identifier id = Identifier.tryParse(idStr);
                    if (id != null) {
                        Item item = Registries.ITEM.get(id);
                        if (item != null && item != net.minecraft.item.Items.AIR) {
                            int count = 1;
                            try {
                                if (itemTag.contains("Count")) count = Integer.parseInt(itemTag.get("Count").toString().replaceAll("[^0-9]", ""));
                                else if (itemTag.contains("count")) count = Integer.parseInt(itemTag.get("count").toString().replaceAll("[^0-9]", ""));
                            } catch (Exception ignored) {}
                            original = new ItemStack(item, count);
                        }
                    }
                }

                if (!original.isEmpty()) {
                    ItemStack replaced = replaceSingleStack(original);
                    if (replaced != original) {
                        try {
                            net.minecraft.nbt.NbtElement newTag = ItemStack.OPTIONAL_CODEC.encodeStart(registries.getOps(net.minecraft.nbt.NbtOps.INSTANCE), replaced).resultOrPartial().orElse(null);
                            if (newTag instanceof NbtCompound newCompound) {
                                if (itemTag.contains("Slot")) newCompound.put("Slot", itemTag.get("Slot"));
                                itemsList.set(i, newCompound);
                            } else {
                                itemTag.putString("id", Registries.ITEM.getId(replaced.getItem()).toString());
                                itemTag.putInt("count", replaced.getCount());
                            }
                        } catch (Exception e) {
                            itemTag.putString("id", Registries.ITEM.getId(replaced.getItem()).toString());
                            itemTag.putInt("count", replaced.getCount());
                        }
                    }
                }
            }
        }
    }
}