package com.mimicenzymes.litematicafiller.network;

import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * PCA (PluslsCarpetAddition) 同步协议处理器。
 * 
 * PCA 特性：
 * - 服务端推送模式：客户端请求一次，服务端在方块变化时自动推送更新
 * - 每个玩家只能同时「关注」一个方块实体
 * - 发送 sync_block_entity 会取消之前的关注并设置新的关注
 * 
 * 优化策略：
 * - 记录当前关注的唯一位置，避免重复发送请求
 * - 请求频率严格限制（至少间隔 3 秒针对同一位置）
 * - 只在主动填充操作时需要请求，高亮扫描只读缓存
 */
public class PcaSyncHandler {
    public static final Identifier PCA_SYNC_BLOCK_ENTITY = new Identifier("pca", "sync_block_entity");
    public static final Identifier PCA_UPDATE_BLOCK_ENTITY = new Identifier("pca", "update_block_entity");
    public static final Identifier PCA_ENABLE_SYNC = new Identifier("pca", "enable_pca_sync_protocol");
    public static final Identifier PCA_CANCEL_SYNC_BLOCK_ENTITY = new Identifier("pca", "cancel_sync_block_entity");

    private static boolean serverSupportsPca = false;
    private static boolean payloadsRegistered = false;
    /** 当前 PCA 关注的位置（PCA 协议每个玩家只能关注一个方块） */
    private static BlockPos watchedPosition = null;
    /** 上次请求时间 */
    private static long lastRequestTime = 0L;
    /** PCA 请求最小间隔 (ms) */
    private static final long PCA_REQUEST_MIN_INTERVAL = 3000L;
    /** 收到 enable 包后才认为支持 PCA */
    private static boolean receivedEnablePacket = false;

    public static void registerPayloads() {
        if (payloadsRegistered) return;
        payloadsRegistered = true;

        try {
            // 监听 update_block_entity：服务端推送方块实体 NBT
            ClientPlayNetworking.registerGlobalReceiver(PCA_UPDATE_BLOCK_ENTITY, (client, handler, buf, responseSender) -> {
                try {
                    Identifier dimensionId = buf.readIdentifier();
                    BlockPos pos = buf.readBlockPos();
                    NbtCompound nbt = buf.readNbt();

                    client.execute(() -> {
                        if (!RealContainerCache.hasActiveConsumers()) return;
                        if (client.world == null || nbt == null) return;

                        RegistryKey<World> packetDim = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
                        if (!client.world.getRegistryKey().equals(packetDim)) return;

                        RealContainerCache.handlePcaBlockEntityUpdate(pos, nbt);
                    });
                } catch (Exception ignored) {}
            });

            // 监听 enable_pca_sync_protocol：服务端通知已启用协议
            ClientPlayNetworking.registerGlobalReceiver(PCA_ENABLE_SYNC, (client, handler, buf, responseSender) -> {
                serverSupportsPca = true;
                receivedEnablePacket = true;
            });
        } catch (Exception ignored) {}
    }

    /**
     * 请求同步某个方块实体的数据。
     * PCA 每个玩家只能关注一个位置，发送新请求会覆盖之前的关注。
     * 这里做了频率限制，同一位置 3 秒内不会重复请求。
     * 
     * @param pos 目标方块位置
     * @return 是否发送了请求
     */
    public static boolean requestBlockEntityData(BlockPos pos) {
        if (pos == null) return false;

        long now = System.currentTimeMillis();
        // 频率限制
        if (now - lastRequestTime < PCA_REQUEST_MIN_INTERVAL) {
            // 如果是同一个位置且已经在关注中，跳过
            if (pos.equals(watchedPosition)) {
                return false;
            }
        }
        if (!receivedEnablePacket) return false;

        try {
            if (!ClientPlayNetworking.canSend(PCA_SYNC_BLOCK_ENTITY)) {
                return false;
            }

            lastRequestTime = now;
            watchedPosition = pos.toImmutable();

            PacketByteBuf sendBuf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
            sendBuf.writeBlockPos(pos);
            ClientPlayNetworking.send(PCA_SYNC_BLOCK_ENTITY, sendBuf);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取消当前的 PCA 关注（当不再需要数据时调用，减少服务端负载）。
     */
    public static void cancelWatch() {
        if (watchedPosition == null) return;
        if (!receivedEnablePacket) return;

        try {
            if (!ClientPlayNetworking.canSend(PCA_CANCEL_SYNC_BLOCK_ENTITY)) {
                return;
            }

            PacketByteBuf sendBuf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
            ClientPlayNetworking.send(PCA_CANCEL_SYNC_BLOCK_ENTITY, sendBuf);
            watchedPosition = null;
        } catch (Exception ignored) {}
    }

    /**
     * 检查服务端是否支持 PCA 协议。
     */
    public static boolean isServerSupportsPca() {
        return receivedEnablePacket;
    }

    /**
     * 重置状态（断线时调用）。
     */
    public static void reset() {
        serverSupportsPca = false;
        receivedEnablePacket = false;
        watchedPosition = null;
        lastRequestTime = 0L;
    }
}
