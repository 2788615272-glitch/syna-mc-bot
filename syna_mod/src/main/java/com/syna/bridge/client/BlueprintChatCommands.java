package com.syna.bridge.client;

import com.syna.bridge.SynaBridgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side chat command parser for blueprint visibility.
 *
 * Listens to {@link ClientChatEvent} (fires before the message goes to
 * the server) and intercepts {@code /synabp hide|show|toggle}. We handle
 * this on the client because visibility is a per-client display option,
 * not server state — no networking needed.
 *
 * The event is canceled on match so the message never reaches the
 * server's chat broadcast.
 */
@Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BlueprintChatCommands {
    private BlueprintChatCommands() {}

    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        String msg = event.getMessage();
        if (msg == null) return;
        String trimmed = msg.trim();
        if (!trimmed.startsWith("/synabp")) return;

        String[] parts = trimmed.split("\\s+");
        String sub = parts.length >= 2 ? parts[1].toLowerCase() : "toggle";
        switch (sub) {
            case "hide", "off" -> BlueprintMirror.setVisible(false);
            case "show", "on"  -> BlueprintMirror.setVisible(true);
            case "toggle"      -> BlueprintMirror.toggleVisible();
            default -> {
                feedback("usage: /synabp [show|hide|toggle]");
                event.setCanceled(true);
                return;
            }
        }
        feedback("blueprint ghost: " + (BlueprintMirror.isVisible() ? "ON" : "OFF"));
        event.setCanceled(true);
    }

    private static void feedback(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Syna] " + text), false);
        }
    }
}
