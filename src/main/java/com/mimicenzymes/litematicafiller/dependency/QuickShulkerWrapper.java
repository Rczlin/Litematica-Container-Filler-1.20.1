package com.mimicenzymes.litematicafiller.dependency;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.packet.CustomPayload;

public class QuickShulkerWrapper implements IShulkerExtractor {

    @Override
    public boolean requestOpenShulker(int playerSlotIndex) {
        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");

            Object packet = packetClass.getConstructor(int.class).newInstance(playerSlotIndex);

            if (packet instanceof CustomPayload payload) {
                if (ClientPlayNetworking.canSend(payload.getId())) {
                    ClientPlayNetworking.send(payload);
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            System.err.println("[LitematicaFiller] 尝试发送 QuickShulker 数据包失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}