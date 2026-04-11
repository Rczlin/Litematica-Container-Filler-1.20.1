package com.mimicenzymes.litematicafiller.core;

import net.minecraft.item.ItemStack;

public class ItemMatcher {

    public static boolean isSameItem(ItemStack current, ItemStack required) {
        if (current.isEmpty() || required.isEmpty()) return false;
        return ItemStack.areItemsAndComponentsEqual(current, required);
    }
}