package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.dependency.TechUtilsDeceiver;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class MaterialReplacer {

    public static boolean isSaving = false;
    private static boolean isPatching = false; // 防死循环锁

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

        for (Map.Entry<NbtCompound, NbtList> entry : ORIGINAL_ITEMS_BACKUP.entrySet()) {
            entry.getKey().put("Items", entry.getValue().copy());
        }
        ORIGINAL_ITEMS_BACKUP.clear();

        if (!REPLACEMENTS.isEmpty()) {
            List<NbtCompound> allNbts = new ArrayList<>();
            try {
                Object manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
                java.util.Collection<?> placements = (java.util.Collection<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
                if (placements != null) {
                    java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    for (Object p : placements) {
                        extractNbtsFromMemory(p, allNbts, visited, 0);
                    }
                }
            } catch (Exception e) {}

            for (NbtCompound nbt : allNbts) {
                if (nbt.contains("Items")) {
                    net.minecraft.nbt.NbtElement itemsElem = nbt.get("Items");
                    if (itemsElem instanceof NbtList list) {
                        ORIGINAL_ITEMS_BACKUP.put(nbt, list.copy());
                        replaceInNbtList((NbtList) nbt.get("Items"), client.world.getRegistryManager());
                    }
                }
            }
        }

        try { TechUtilsDeceiver.forceTechUtilsUpdate(); } catch (Throwable ignored) {}
    }

    private static void extractNbtsFromMemory(Object obj, List<NbtCompound> results, java.util.Set<Object> visited, int depth) {
        if (obj == null || depth > 25 || !visited.add(obj)) return;

        if (obj instanceof NbtCompound c) {
            if (c.contains("Items")) results.add(c);
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