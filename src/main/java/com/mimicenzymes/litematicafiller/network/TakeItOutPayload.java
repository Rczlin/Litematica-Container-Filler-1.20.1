package com.mimicenzymes.litematicafiller.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

public class TakeItOutPayload {
    public static final Identifier ID = new Identifier("takeitout", "getstack");

    private final int slot;
    private final int shulker;

    public TakeItOutPayload(int slot, int shulker) {
        this.slot = slot;
        this.shulker = shulker;
    }

    public int slot() { return slot; }
    public int shulker() { return shulker; }

    public static TakeItOutPayload read(PacketByteBuf buf) {
        return new TakeItOutPayload(buf.readInt(), buf.readInt());
    }

    public static void write(TakeItOutPayload payload, PacketByteBuf buf) {
        buf.writeInt(payload.slot());
        buf.writeInt(payload.shulker());
    }
}
