package com.syna.bridge;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Broadcasts the block the player is looking at (crosshair target) to bot
 * players via private system message every N ticks.
 * 
 * Format: [CROSSHAIR] blockName x y z
 * 
 * The human player sees the info on the ACTION BAR (not chat).
 * Bot players receive it as a system message (picked up by messagestr event).
 */
public class CrosshairBroadcaster {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Broadcast interval in ticks (20 ticks = 1 second)
    private static final int INTERVAL_TICKS = 10; // every 0.5s
    private static final double MAX_REACH = 64.0; // blocks
    // Force re-broadcast even if unchanged every N ticks (5 seconds)
    private static final int FORCE_RESEND_TICKS = 100;

    /** Bot name prefixes — must match BotGuard's list */
    private static final Set<String> BOT_PREFIXES = new HashSet<>();
    private static final Set<String> BOT_NAMES = new HashSet<>();

    static {
        BOT_PREFIXES.add("Syna");
        BOT_PREFIXES.add("Andy");
        BOT_PREFIXES.add("Jill");
        BOT_PREFIXES.add("syna");
    }

    private int tickCounter = 0;
    private int forceSendCounter = 0;
    private String lastBroadcast = "";

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter < INTERVAL_TICKS) return;
        tickCounter = 0;
        forceSendCounter += INTERVAL_TICKS;

        // Find the human player (non-bot) to raycast from
        ServerPlayer player = findHumanPlayer();
        if (player == null) return;

        // Perform raycast from player's eye position along look vector
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.x * MAX_REACH, lookVec.y * MAX_REACH, lookVec.z * MAX_REACH);

        ClipContext ctx = new ClipContext(
            eyePos, endPos,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            player
        );

        BlockHitResult hitResult = player.level().clip(ctx);

        String msg;
        String displayMsg;

        if (hitResult.getType() == HitResult.Type.MISS) {
            msg = "[CROSSHAIR] air -1 -1 -1";
            displayMsg = "\u00a77Looking at: \u00a7fair";
        } else {
            BlockPos pos = hitResult.getBlockPos();
            BlockState state = player.level().getBlockState(pos);
            String registryName = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getKey(state.getBlock()).getPath();
            msg = String.format("[CROSSHAIR] %s %d %d %d", registryName, pos.getX(), pos.getY(), pos.getZ());
            displayMsg = String.format("\u00a77Target: \u00a7f%s \u00a77[%d, %d, %d]",
                registryName, pos.getX(), pos.getY(), pos.getZ());
        }

        // Broadcast when target changes OR force resend every 5 seconds
        boolean changed = !msg.equals(lastBroadcast);
        boolean forceResend = forceSendCounter >= FORCE_RESEND_TICKS;

        if (changed || forceResend) {
            if (forceResend) forceSendCounter = 0;
            lastBroadcast = msg;
            sendToBots(player, msg);
            // Show action bar to the human player (does NOT appear in chat)
            showActionBar(player, displayMsg);
        }
    }

    /**
     * Find the first non-bot (human) player on the server.
     * This ensures we always raycast from the human's perspective,
     * regardless of login order.
     */
    private ServerPlayer findHumanPlayer() {
        // First try the bound player from BridgeState
        ServerPlayer bound = BridgeState.get().getBoundPlayer();
        if (bound != null && !isBotPlayer(bound.getGameProfile().getName())) {
            return bound;
        }
        // Fallback: search all players for a non-bot
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!isBotPlayer(p.getGameProfile().getName())) {
                return p;
            }
        }
        return null;
    }

    /**
     * Send the crosshair data ONLY to bot players via private system message.
     * This does NOT appear in the human player's chat.
     */
    private void sendToBots(ServerPlayer sourcePlayer, String message) {
        if (sourcePlayer.getServer() == null) return;

        // Format: "PlayerName: [CROSSHAIR] blockName x y z"
        Component component = Component.literal(
            sourcePlayer.getGameProfile().getName() + ": " + message
        );

        int sent = 0;
        for (ServerPlayer target : sourcePlayer.getServer().getPlayerList().getPlayers()) {
            if (target == sourcePlayer) continue; // skip the human
            String name = target.getGameProfile().getName();
            if (isBotPlayer(name)) {
                target.sendSystemMessage(component);
                sent++;
            }
        }

        if (sent == 0) {
            // Debug: no bots found to send to
            LOGGER.debug("[CrosshairBroadcaster] No bot players online to receive: {}", message);
        }
    }

    /**
     * Show info on the human player's action bar (the text above hotbar).
     * This auto-fades and never enters chat history.
     */
    private void showActionBar(ServerPlayer player, String text) {
        player.connection.send(
            new ClientboundSetActionBarTextPacket(Component.literal(text))
        );
    }

    private static boolean isBotPlayer(String playerName) {
        if (BOT_NAMES.contains(playerName)) return true;
        for (String prefix : BOT_PREFIXES) {
            if (playerName.startsWith(prefix)) return true;
        }
        return false;
    }
}
