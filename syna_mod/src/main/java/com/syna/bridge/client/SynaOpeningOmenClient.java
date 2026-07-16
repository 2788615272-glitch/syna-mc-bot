package com.syna.bridge.client;

import com.syna.bridge.SynaBridgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SynaOpeningOmenClient {
    private static int phase;
    private static int remainingTicks;
    private static int totalTicks;

    private SynaOpeningOmenClient() {}

    public static void trigger(int nextPhase, int durationTicks) {
        Minecraft mc = Minecraft.getInstance();
        phase = Math.max(1, Math.min(2, nextPhase));
        totalTicks = Math.max(1, durationTicks);
        remainingTicks = totalTicks;
        mc.getMusicManager().stopPlaying();
        if (mc.level == null || mc.player == null) return;
        if (phase == 1) {
            mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 0.55F, 0.62F, false);
        } else {
            SynaVoiceClient.interrupt();
            mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.AMBIENT, 0.72F, 0.58F, false);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || remainingTicks <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            clear();
            return;
        }
        mc.getMusicManager().stopPlaying();
        remainingTicks--;
        if (remainingTicks <= 0) clear();
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (remainingTicks > 0 && event.getOriginalSound().getSource() == SoundSource.MUSIC) {
            event.setSound(null);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
        if (remainingTicks <= 0) return;
        GuiGraphics graphics = event.getGuiGraphics();
        int width = event.getWindow().getGuiScaledWidth();
        int height = event.getWindow().getGuiScaledHeight();
        float progress = remainingTicks / (float) Math.max(1, totalTicks);
        float pulse = 0.5F + 0.5F * (float) Math.sin(remainingTicks * (phase == 2 ? 0.85D : 0.32D));
        int darkness = phase == 2 ? 70 + Math.round(65 * pulse) : 25 + Math.round(25 * pulse);
        int edge = phase == 2 ? 85 + Math.round(65 * progress) : 45 + Math.round(30 * pulse);
        graphics.fill(0, 0, width, height, darkness << 24);
        int thickness = phase == 2 ? Math.max(12, Math.min(width, height) / 13)
                : Math.max(7, Math.min(width, height) / 22);
        int edgeColor = (Math.min(190, edge) << 24) | 0x240005;
        graphics.fill(0, 0, width, thickness, edgeColor);
        graphics.fill(0, height - thickness, width, height, edgeColor);
        graphics.fill(0, 0, thickness, height, edgeColor);
        graphics.fill(width - thickness, 0, width, height, edgeColor);
    }

    private static void clear() {
        phase = 0;
        remainingTicks = 0;
        totalTicks = 0;
    }
}
