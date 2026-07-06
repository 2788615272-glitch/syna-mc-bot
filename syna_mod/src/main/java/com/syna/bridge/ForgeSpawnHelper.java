package com.syna.bridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ForgeSpawnHelper — 解决 mineflayer bot 在 Forge 服务器上的 chunk 加载问题。
 *
 * 问题：mineflayer 作为 vanilla 协议客户端连接 Forge 服务器时，
 * 由于 FML3 握手的时序差异，spawn 事件可能在 chunk 数据到达之前触发，
 * 导致 bot 无法感知周围方块（nearbyBlocks = "none"）。
 *
 * 此外，进入 mod 维度（如暮色森林）时，维度切换后 chunk 数据可能
 * 因为包含 mod 方块的 palette 而被 mineflayer 解析失败，导致：
 * - 人物浮空（physics 引擎无碰撞数据）
 * - nearbyBlocks 永远为空
 *
 * 解决方案：
 * 1. 登录时延迟重发 position（已有）
 * 2. 维度切换时重新触发 chunk 重发 + position 同步
 * 3. 提供 HTTP API 让客户端主动请求 chunk reload
 */
@Mod.EventBusSubscriber(modid = "synabridge", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeSpawnHelper {

    private static final Logger LOG = LoggerFactory.getLogger("SynaBridge");
    private static final String TAG = "[ForgeSpawnHelper]";

    // 已处理过首次登录的玩家（防止重复触发）
    private static final Set<String> processedPlayers = ConcurrentHashMap.newKeySet();

    // Bot 名字列表（从 BotIdentity 获取）
    private static boolean isBot(String name) {
        return BotIdentity.isBot(name);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String name = player.getGameProfile().getName();
        if (!isBot(name)) return;
        if (!processedPlayers.add(name)) return; // 已处理过

        MinecraftServer server = player.getServer();
        if (server == null) return;

        LOG.info("{} Bot '{}' logged in, scheduling position resend + chunk reload", TAG, name);
        scheduleChunkResend(player, server, name, 1500, "login-1");
        scheduleChunkResend(player, server, name, 3500, "login-2");
    }

    /**
     * P1: 维度切换事件处理。
     * 当 bot 进入暮色森林等 mod 维度时，重新发送 chunk 数据。
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String name = player.getGameProfile().getName();
        if (!isBot(name)) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        LOG.info("{} Bot '{}' changed dimension from {} to {}, scheduling chunk resend",
            TAG, name, event.getFrom().location(), event.getTo().location());

        // 维度切换后延迟发送，给服务器时间完成 respawn 流程
        scheduleChunkResend(player, server, name, 2000, "dimchange-1");
        scheduleChunkResend(player, server, name, 5000, "dimchange-2");
    }

    /**
     * P0: 供 HTTP API 调用的主动 chunk 重发。
     * 当客户端检测到 ChunkWait 超时时，通过 /chunk_reload 请求触发。
     */
    public static void forceChunkReload(String playerName) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
            if (player == null || player.hasDisconnected()) {
                LOG.warn("{} Cannot force chunk reload: player '{}' not found or disconnected", TAG, playerName);
                return;
            }

            LOG.info("{} Force chunk reload for '{}'", TAG, playerName);
            resendChunksAroundPlayer(player);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static void scheduleChunkResend(ServerPlayer player, MinecraftServer server, String name, long delayMs, String label) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                return;
            }
            server.execute(() -> {
                try {
                    if (player.hasDisconnected()) return;

                    // 1. Teleport to self (forces position sync)
                    Vec3 pos = player.position();
                    player.connection.teleport(
                        pos.x, pos.y, pos.z,
                        player.getYRot(), player.getXRot()
                    );

                    // 2. Resend surrounding chunks
                    resendChunksAroundPlayer(player);

                    LOG.info("{} [{}] Resent position + chunks to bot '{}' at ({}, {}, {})",
                        TAG, label, name, (int)pos.x, (int)pos.y, (int)pos.z);
                } catch (Throwable t) {
                    LOG.warn("{} [{}] Failed for '{}': {}", TAG, label, name, t.getMessage());
                }
            });
        }, "SynaSpawnHelper-" + label + "-" + name).start();
    }

    /**
     * 重发玩家周围 chunk 数据。
     * 通过 double-teleport 触发服务器重新发送 chunk tracking。
     * 这比手动构造 ClientboundLevelChunkWithLightPacket 更可靠
     * （避免 1.20.1 构造函数签名差异）。
     */
    private static void resendChunksAroundPlayer(ServerPlayer player) {
        try {
            Vec3 pos = player.position();
            // 微小偏移 teleport 触发 chunk tracking 重新计算
            player.connection.teleport(pos.x, pos.y + 0.01, pos.z, player.getYRot(), player.getXRot());
            player.connection.teleport(pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
            LOG.info("{} Triggered chunk re-track for '{}' via double-teleport", TAG, player.getGameProfile().getName());
        } catch (Throwable t) {
            LOG.warn("{} resendChunksAroundPlayer failed: {}", TAG, t.getMessage());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            String name = event.getEntity().getGameProfile().getName();
            processedPlayers.remove(name);
        }
    }
}
