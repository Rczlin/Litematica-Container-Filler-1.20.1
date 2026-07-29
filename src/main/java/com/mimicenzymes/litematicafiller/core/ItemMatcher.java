package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ItemMatcher {

    public static boolean isSameItem(ItemStack current, ItemStack required) {
        if (current.isEmpty() || required.isEmpty()) return false;
        if (canCombineIgnoringCount(current, required)) return true;
        return Configs.MATCH_SHULKER_BOXES_BY_CONTENT.getBooleanValue()
                && isShulkerBox(current)
                && isShulkerBox(required)
                && hasSameContainerContents(current, required);
    }

    public static int matchingHash(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (Configs.MATCH_SHULKER_BOXES_BY_CONTENT.getBooleanValue() && isShulkerBox(stack)) {
            int hash = ShulkerBoxBlock.class.hashCode();
            for (ItemStack inner : getContainerSlots(stack)) {
                hash = 31 * hash + matchingHash(inner);
                hash = 31 * hash + inner.getCount();
            }
            return hash;
        }
        int hash = stack.getItem().hashCode();
        NbtCompound nbt = stack.getNbt();
        return 31 * hash + Objects.hashCode(nbt);
    }

    private static boolean canCombineIgnoringCount(ItemStack current, ItemStack required) {
        return ItemStack.canCombine(current, required);
    }

    private static boolean hasSameContainerContents(ItemStack current, ItemStack required) {
        List<ItemStack> currentSlots = getContainerSlots(current);
        List<ItemStack> requiredSlots = getContainerSlots(required);
        if (currentSlots.size() != requiredSlots.size()) return false;

        for (int i = 0; i < currentSlots.size(); i++) {
            ItemStack currentSlot = currentSlots.get(i);
            ItemStack requiredSlot = requiredSlots.get(i);
            if (currentSlot.isEmpty() != requiredSlot.isEmpty()) return false;
            if (currentSlot.isEmpty()) continue;
            if (currentSlot.getCount() != requiredSlot.getCount()) return false;
            if (!isSameItem(currentSlot, requiredSlot)) return false;
        }
        return true;
    }

    private static List<ItemStack> getContainerSlots(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) return List.of();
        NbtCompound blockEntityTag = nbt.contains("BlockEntityTag") ? nbt.getCompound("BlockEntityTag") : null;
        net.minecraft.nbt.NbtList itemsList = (blockEntityTag != null && blockEntityTag.contains("Items")) ? blockEntityTag.getList("Items", 10) : null;
        if (itemsList == null) return List.of();

        List<ItemStack> slots = new ArrayList<>(java.util.Collections.nCopies(27, ItemStack.EMPTY));
        for (int i = 0; i < itemsList.size(); i++) {
            NbtCompound itemTag = itemsList.getCompound(i);
            int slot = getContainerSlot(itemTag, i);
            if (slot < 0 || slot >= slots.size()) continue;
            ItemStack inner = ItemStack.fromNbt(itemTag);
            slots.set(slot, inner.isEmpty() ? ItemStack.EMPTY : inner);
        }
        return slots;
    }

    private static int getContainerSlot(NbtCompound itemTag, int fallback) {
        if (itemTag == null || !itemTag.contains("Slot", NbtElement.NUMBER_TYPE)) return fallback;
        return itemTag.getByte("Slot") & 0xFF;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }
}
