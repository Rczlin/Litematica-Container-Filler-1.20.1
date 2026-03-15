package com.mimicenzymes.litematicafiller.network;

import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public record ServuxResponsePayload(BlockPos pos, Map<Integer, ItemStack> items) implements CustomPayload {

    public static final CustomPayload.Id<ServuxResponsePayload> ID = new CustomPayload.Id<>(Identifier.of("servux", "hud_data_sync"));

    public static final PacketCodec<RegistryByteBuf, ServuxResponsePayload> CODEC = PacketCodec.of(
            (value, buf) -> {
            },
            buf -> {
                Map<Integer, ItemStack> parsedItems = new HashMap<>();
                BlockPos parsedPos = null;

                try {

                    parsedPos = buf.readBlockPos();
                    int size = buf.readVarInt();

                    for (int i = 0; i < size; i++) {
                        int slot = buf.readVarInt();
                        ItemStack stack = ItemStack.PACKET_CODEC.decode(buf);
                        if (!stack.isEmpty()) {
                            parsedItems.put(slot, stack);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[LitematicaFiller] 脱离 MiniHUD 独立解析 Servux 数据失败，协议不匹配: " + e.getMessage());
                }

                return new ServuxResponsePayload(parsedPos != null ? parsedPos : BlockPos.ORIGIN, parsedItems);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}