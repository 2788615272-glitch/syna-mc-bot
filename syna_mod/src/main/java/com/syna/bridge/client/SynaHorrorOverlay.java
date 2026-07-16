package com.syna.bridge.client;

import com.syna.bridge.AliceEntity;
import com.syna.bridge.SynaBridgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SynaHorrorOverlay {
    private SynaHorrorOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.font == null) {
            return;
        }
        AliceEntity syna = findClientSyna(mc);
        if (syna == null) {
            return;
        }
        int stage = syna.getHorrorStage();
        if (stage < 3) {
            return;
        }
        String target = syna.getHorrorTargetName();
        if (target == null || target.isBlank()) {
            target = "未知目标";
        }
        if (!target.equalsIgnoreCase(mc.player.getGameProfile().getName())) {
            return;
        }
        String text;
        int color;
        if (stage >= 4) {
            text = "Syna 正在追来  目标：" + target;
            color = 0xFFFF2020;
        } else {
            int seconds = Math.max(0, syna.getHorrorCountdownTicks() / 20);
            text = "追杀倒计时  " + seconds + " 秒  目标：" + target;
            color = 0xFFFFD060;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int width = event.getWindow().getGuiScaledWidth();
        int x = Math.max(4, width - mc.font.width(text) - 8);
        int y = 8;
        graphics.fill(x - 4, y - 3, width - 4, y + 11, 0xAA080008);
        graphics.drawString(mc.font, text, x, y, color, true);

        String challenge = syna.getHorrorChallengeText();
        if (challenge != null && !challenge.isBlank()) {
            if (syna.getChallengeIntroTicks() > 0) {
                int centerX = (width - mc.font.width(challenge)) / 2;
                int centerY = event.getWindow().getGuiScaledHeight() / 2 - 8;
                graphics.fill(centerX - 8, centerY - 6, centerX + mc.font.width(challenge) + 8, centerY + 15, 0xCC080008);
                graphics.drawString(mc.font, challenge, centerX, centerY, 0xFFFFF080, true);
                return;
            }
            int cy = y + 14;
            int cx = Math.max(4, width - mc.font.width(challenge) - 8);
            graphics.fill(cx - 4, cy - 3, width - 4, cy + 11, 0xAA080008);
            graphics.drawString(mc.font, challenge, cx, cy, 0xFFFFF080, true);
        }
    }

    private static AliceEntity findClientSyna(Minecraft mc) {
        if (mc.level == null) {
            return null;
        }
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof AliceEntity alice && alice.isAlive()) {
                return alice;
            }
        }
        return null;
    }
}
