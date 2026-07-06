package com.syna.bridge.client;

import com.syna.bridge.SynaBridgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SynaVoiceFocusOverlay {
    private static long focusedUntilMillis = 0L;

    private SynaVoiceFocusOverlay() {
    }

    public static void markFocused() {
        focusedUntilMillis = System.currentTimeMillis() + 1800L;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null) {
            return;
        }

        boolean keyDown = BlueprintKeybinds.VOICE_FOCUS.isDown();
        if (!keyDown && System.currentTimeMillis() > focusedUntilMillis) {
            return;
        }

        String text = "\u6b63\u5728\u548c Syna \u5bf9\u8bdd";
        GuiGraphics graphics = event.getGuiGraphics();
        int x = 8;
        int y = 8;
        int width = mc.font.width(text);
        graphics.fill(x - 4, y - 3, x + width + 6, y + 12, 0xAA05050A);
        graphics.drawString(mc.font, text, x, y, 0xFFFFD6F7, true);
    }
}