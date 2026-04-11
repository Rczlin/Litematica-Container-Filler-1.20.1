package com.mimicenzymes.litematicafiller.core;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.HashMap;
import java.util.Map;

public class SlotMapper {

    private final Map<Integer, Integer> playerToUiMap = new HashMap<>();
    private final Map<Integer, Integer> containerToUiMap = new HashMap<>();

    public SlotMapper(ScreenHandler handler, PlayerInventory playerInv) {
        for (int uiSlotId = 0; uiSlotId < handler.slots.size(); uiSlotId++) {
            Slot slot = handler.slots.get(uiSlotId);
            if (slot.inventory == null) continue;

            if (slot.inventory == playerInv) {
                playerToUiMap.putIfAbsent(slot.getIndex(), uiSlotId);
            } else {
                if (handler instanceof net.minecraft.screen.CrafterScreenHandler && slot.getIndex() == 9) {
                    continue;
                }
                containerToUiMap.putIfAbsent(slot.getIndex(), uiSlotId);
            }
        }
    }

    public int getUiSlotForPlayer(int playerSlotIndex) {
        return playerToUiMap.getOrDefault(playerSlotIndex, -1);
    }

    public int getUiSlotForContainer(int containerSlotIndex) {
        return containerToUiMap.getOrDefault(containerSlotIndex, -1);
    }
}