package com.mimicenzymes.litematicafiller.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record ServuxRequestPayload(int action, BlockPos pos) implements CustomPayload {

    public static final CustomPayload.Id<ServuxRequestPayload> ID = new CustomPayload.Id<>(Identifier.of("servux", "hud_data_request"));

    public static final PacketCodec<PacketByteBuf, ServuxRequestPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.action());
                buf.writeBlockPos(value.pos());
            },
            buf -> new ServuxRequestPayload(buf.readVarInt(), buf.readBlockPos())
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}