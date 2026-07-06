package com.syna.bridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.syna.bridge.SynaBridgeMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Client hotkeys for SynaBridge.
 */
public final class BlueprintKeybinds {
    public static final String CATEGORY = "key.categories." + SynaBridgeMod.MOD_ID;
    private static final long VOICE_FOCUS_COOLDOWN_MS = 1500L;
    private static long lastVoiceFocusSentAt = 0L;

    public static final KeyMapping TOGGLE_GHOST = new KeyMapping(
            "key." + SynaBridgeMod.MOD_ID + ".toggle_ghost",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY);

    public static final KeyMapping VOICE_FOCUS = new KeyMapping(
            "key." + SynaBridgeMod.MOD_ID + ".voice_focus",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    private BlueprintKeybinds() {}

    @Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_GHOST);
            event.register(VOICE_FOCUS);
            SynaBridgeMod.LOGGER.info("[BlueprintKeybinds] toggle_ghost registered (B), voice_focus registered (V)");
        }
    }

    @Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {}

        @SubscribeEvent
        public static void onKey(net.minecraftforge.client.event.InputEvent.Key event) {
            while (TOGGLE_GHOST.consumeClick()) {
                BlueprintMirror.toggleVisible();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    String state = BlueprintMirror.isVisible() ? "ON" : "OFF";
                    mc.player.displayClientMessage(Component.literal("[Syna] blueprint ghost: " + state), true);
                }
            }

            while (VOICE_FOCUS.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.getConnection() != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastVoiceFocusSentAt < VOICE_FOCUS_COOLDOWN_MS) {
                        SynaVoiceFocusOverlay.markFocused();
                        continue;
                    }
                    lastVoiceFocusSentAt = now;
                    mc.player.displayClientMessage(Component.literal("[Syna] voice focus armed"), true);
                    SynaVoiceFocusOverlay.markFocused();
                    mc.getConnection().sendChat("syna focus");
                }
            }
        }
    }
}