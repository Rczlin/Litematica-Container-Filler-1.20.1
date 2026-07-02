package com.mimicenzymes.litematicafiller.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

public class TakeItOutCompat {
    private static final boolean HAS_CLIENT_TAKEITOUT = FabricLoader.getInstance().isModLoaded("takeitout");
    private static boolean payloadRegistered = false;
    private static boolean payloadRegistrationAttempted = false;

    public static void registerPayload() {
        if (payloadRegistrationAttempted) return;
        payloadRegistrationAttempted = true;

        if (HAS_CLIENT_TAKEITOUT) {
            payloadRegistered = true;
            return;
        }

        try {
            // In 1.20.1, we don't need PayloadTypeRegistry - just mark as registered
            payloadRegistered = true;
        } catch (Exception ignored) {
            payloadRegistered = false;
        }
    }

    public static boolean canRequestStack() {
        try {
            return payloadRegistered && ClientPlayNetworking.canSend(TakeItOutPayload.ID);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean requestStack(int slotInShulker, int shulkerSlot) {
        if (!canRequestStack()) return false;

        try {
            if (HAS_CLIENT_TAKEITOUT) {
                createClientTakeItOutPayload(slotInShulker, shulkerSlot);
            } else {
                net.minecraft.network.PacketByteBuf sendBuf = new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
                TakeItOutPayload.write(new TakeItOutPayload(slotInShulker, shulkerSlot), sendBuf);
                ClientPlayNetworking.send(TakeItOutPayload.ID, sendBuf);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Object createClientTakeItOutPayload(int slotInShulker, int shulkerSlot) throws ReflectiveOperationException {
        Class<?> payloadClass = Class.forName("net.maxbel.takeitout.Takeitout$GetShulkerStackPayload");

        try {
            Object payload = payloadClass
                    .getDeclaredConstructor(int.class, int.class)
                    .newInstance(slotInShulker, shulkerSlot);
            // In 1.20.1, send via reflection to call ClientPlayNetworking.send with the payload
            try {
                Class<?> networkingClass = ClientPlayNetworking.class;
                for (java.lang.reflect.Method m : networkingClass.getDeclaredMethods()) {
                    if (m.getName().equals("send") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        m.invoke(null, payload);
                        return payload;
                    }
                }
            } catch (Throwable ignored) {}
            return payload;
        } catch (NoSuchMethodException ignored) {
            Object payload = payloadClass
                    .getDeclaredConstructor(int.class, int.class, boolean.class)
                    .newInstance(slotInShulker, shulkerSlot, false);
            try {
                Class<?> networkingClass = ClientPlayNetworking.class;
                for (java.lang.reflect.Method m : networkingClass.getDeclaredMethods()) {
                    if (m.getName().equals("send") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        m.invoke(null, payload);
                        return payload;
                    }
                }
            } catch (Throwable ignored2) {}
            return payload;
        }
    }
}
