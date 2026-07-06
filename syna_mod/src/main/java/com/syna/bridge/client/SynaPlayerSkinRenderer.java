package com.syna.bridge.client;

import com.syna.bridge.SynaBridgeMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SynaPlayerSkinRenderer {
    private SynaPlayerSkinRenderer() {
    }

    @SubscribeEvent
    public static void install(EntityRenderersEvent.AddLayers event) {
        // Some client mod stacks expose the player skin renderer map as unmodifiable
        // during AddLayers. The dedicated AliceRenderer already uses the bundled
        // Syna skin, so skipping this player renderer override avoids client crashes.
        SynaBridgeMod.LOGGER.info("[SynaSkin] player renderer override skipped; mod entity renderer uses bundled skin");
    }
}