package com.syna.bridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(SynaBridgeMod.MOD_ID)
public class SynaBridgeMod {
    public static final String MOD_ID = "synabridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final BridgeHttpServer httpServer = new BridgeHttpServer();

    public SynaBridgeMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.register(modBus);
        modBus.addListener(this::onEntityAttributes);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new BotGuard());
        MinecraftForge.EVENT_BUS.register(new BotSkinInjector());
        MinecraftForge.EVENT_BUS.register(new CrosshairBroadcaster());
        MinecraftForge.EVENT_BUS.register(new BlueprintProtection());
        BlueprintNetwork.register();
        SynaVoiceNetwork.register();
        LOGGER.info("SynaBridge mod loaded (BotGuard + BotSkinInjector + CrosshairBroadcaster + BlueprintProtection + BlueprintNetwork + SynaVoiceNetwork active)");
    }

    public void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.ALICE.get(), AliceEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Plan-A：放行 NetworkRegistry channel 校验，让 vanilla 协议客户端
        // （如 mineflayer 上的 syna 机器人）能进入装了任意 mod 的服务器。
        // 必须在所有 mod 都完成 channel 注册之后做，所以放在 ServerStarting。
        FmlBypass.applyAll();
        // Plan-C：在 Netty pipeline 安装 guard，拦截空 login query response
        // 防止暮色森林等 mod 的 channel 导致 IndexOutOfBoundsException
        FmlBypass.hookServerConnections(event.getServer());
        httpServer.start();
        BridgeState.get().setLastEvent("server_starting");
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        httpServer.stop();
        BridgeState.get().setLastEvent("server_stopped");
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            BridgeState.get().bind(player);
            BridgeState.get().setLastEvent("player_login:" + player.getGameProfile().getName());
            // Push current blueprint state to the freshly-joined client so
            // their renderer doesn't have to wait for the next change tick.
            BlueprintNetwork.sendAllTo(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        BridgeState.get().setLastEvent("player_logout:" + event.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        net.minecraft.server.level.ServerPlayer player = event.getPlayer();
        boolean handled = SynaController.get().handleChatCommand(player, event.getRawText());
        if (handled) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        SynaController.get().onLivingEntityDeath(event.getEntity());
    }
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            BridgeCommandQueue.get().drainAndExecute();
            SynaController.get().tick();
            SynaController.get().tickMobilityDebugHud();
            BridgeState.get().onServerTick();
            BlueprintNetwork.onServerTickEnd();
        }
    }
}