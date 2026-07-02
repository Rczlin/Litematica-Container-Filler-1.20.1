package com.mimicenzymes.litematicafiller.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ServuxRequestPayload {
    public static final Identifier ID = new Identifier("servux", "hud_data_request");

    private final int action;
    private final BlockPos pos;

    public ServuxRequestPayload(int action, BlockPos pos) {
        this.action = action;
        this.pos = pos;
    }

    public int action() { return action; }
    public BlockPos pos() { return pos; }

    public static ServuxRequestPayload read(PacketByteBuf buf) {
        return new ServuxRequestPayload(buf.readVarInt(), buf.readBlockPos());
    }

    public static void write(ServuxRequestPayload payload, PacketByteBuf buf) {
        buf.writeVarInt(payload.action());
        buf.writeBlockPos(payload.pos());
    }
}
