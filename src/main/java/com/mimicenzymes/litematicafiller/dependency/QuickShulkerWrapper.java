package com.mimicenzymes.litematicafiller.dependency;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

/**
 * QuickShulker compatibility wrapper using reflection for 1.20.1
 */
public class QuickShulkerWrapper implements IShulkerExtractor {

    private static Boolean quickShulkerPresent = null;

    private static boolean isQuickShulkerPresent() {
        if (quickShulkerPresent == null) {
            try {
                Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
                quickShulkerPresent = true;
            } catch (ClassNotFoundException e) {
                quickShulkerPresent = false;
            }
        }
        return quickShulkerPresent;
    }

    @Override
    public boolean requestOpenShulker(int playerSlotIndex) {
        if (!isQuickShulkerPresent()) return false;

        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            java.lang.reflect.Method openMethod = packetClass.getMethod("openShulkerBox", int.class);
            openMethod.invoke(null, playerSlotIndex);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public ItemStack getFirstItem(ItemStack shulker, int targetIndex) {
        NbtCompound beTag = shulker.getOrCreateSubNbt("BlockEntityTag");
        if (beTag == null || !beTag.contains("Items")) return ItemStack.EMPTY;

        net.minecraft.nbt.NbtList items = beTag.getList("Items", 10);
        int index = 0;
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemTag = items.getCompound(i);
            ItemStack stack = ItemStack.fromNbt(itemTag);
            if (!stack.isEmpty()) {
                if (index == targetIndex) return stack;
                index++;
            }
        }
        return ItemStack.EMPTY;
    }
}
