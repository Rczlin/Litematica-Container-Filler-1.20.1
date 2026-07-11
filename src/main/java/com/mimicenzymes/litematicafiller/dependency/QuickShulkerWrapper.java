package com.mimicenzymes.litematicafiller.dependency;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

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

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;

        ScreenHandler handler = client.player.playerScreenHandler;
        int quickShulkerSlotId = resolveQuickShulkerSlotId(handler, client, playerSlotIndex);
        if (quickShulkerSlotId < 0) return false;

        ItemStack stack = client.player.getInventory().getStack(playerSlotIndex);
        if (stack.isEmpty()) return false;

        try {
            Class<?> clientUtilClass = Class.forName("net.kyrptonaught.quickshulker.client.ClientUtil");
            java.lang.reflect.Method checkAndSend = clientUtilClass.getMethod("CheckAndSend", ItemStack.class, int.class);
            Object result = checkAndSend.invoke(null, stack, quickShulkerSlotId);
            if (result instanceof Boolean sent) {
                return sent;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            java.lang.reflect.Method sendMethod = packetClass.getMethod("sendOpenPacket", int.class);
            sendMethod.invoke(null, quickShulkerSlotId);
            return true;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            java.lang.reflect.Method legacyMethod = packetClass.getMethod("openShulkerBox", int.class);
            legacyMethod.invoke(null, quickShulkerSlotId);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private int resolveQuickShulkerSlotId(ScreenHandler handler, MinecraftClient client, int playerSlotIndex) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == client.player.getInventory() && slot.getIndex() == playerSlotIndex) {
                return slot.id;
            }
        }
        return -1;
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
