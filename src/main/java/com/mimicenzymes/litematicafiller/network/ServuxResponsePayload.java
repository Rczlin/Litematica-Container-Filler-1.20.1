package com.mimicenzymes.litematicafiller.network;

import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class ServuxResponsePayload {
    public static final Identifier ID = new Identifier("servux", "hud_data_sync");

    private final BlockPos pos;
    private final Map<Integer, ItemStack> items;

    public ServuxResponsePayload(BlockPos pos, Map<Integer, ItemStack> items) {
        this.pos = pos;
        this.items = items;
    }

    public BlockPos pos() { return pos; }
    public Map<Integer, ItemStack> items() { return items; }

    public static ServuxResponsePayload read(PacketByteBuf buf) {
        Map<Integer, ItemStack> parsedItems = new HashMap<>();
        BlockPos parsedPos = null;

        try {
            parsedPos = buf.readBlockPos();
            int size = buf.readVarInt();

            for (int i = 0; i < size; i++) {
                int slot = buf.readVarInt();
                ItemStack stack = buf.readItemStack();
                if (!stack.isEmpty()) {
                    parsedItems.put(slot, stack);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (buf.readableBytes() > 0) {
                buf.skipBytes(buf.readableBytes());
            }
        }

        return new ServuxResponsePayload(parsedPos != null ? parsedPos : BlockPos.ORIGIN, parsedItems);
    }
}
