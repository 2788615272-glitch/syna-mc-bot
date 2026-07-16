package com.syna.bridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(SynaBridgeMod.MOD_ID)
public class SynaBridgeMod {
    public static final String MOD_ID = "synabridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final BridgeHttpServer httpServer = new BridgeHttpServer();

    public SynaBridgeMod() {
        SynaGameRules.bootstrap();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SynaHorrorConfig.SPEC, "synabridge-horror.toml");
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.register(modBus);
        ModItems.register(modBus);
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
        HorrorEntityEventDirector.get().reset(event.getServer());
        httpServer.stop();
        BridgeState.get().setLastEvent("server_stopped");
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            SynaController.get().clearSessionPresence(player.server);
            BridgeState.get().bind(player);
            BridgeState.get().setLastEvent("player_login:" + player.getGameProfile().getName());
            // Push current blueprint state to the freshly-joined client so
            // their renderer doesn't have to wait for the next change tick.
            BlueprintNetwork.sendAllTo(player);
            SynaFirstContactDirector.get().onLogin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            SynaFirstContactDirector.get().onLogout(player);
        }
        BridgeState.get().setLastEvent("player_logout:" + event.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof net.minecraft.server.level.ServerPlayer original
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer replacement) {
            SynaFirstContactDirector.get().onClone(original, replacement);
            SynaOpeningOmenDirector.get().onClone(original, replacement);
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        net.minecraft.server.level.ServerPlayer player = event.getPlayer();
        if (SynaTrueNameDirector.get().handleRitualSpeech(player, event.getRawText())) {
            event.setCanceled(true);
            return;
        }
        boolean handled = SynaController.get().handleChatCommand(player, event.getRawText());
        if (handled) {
            event.setCanceled(true);
            return;
        }
        BridgeConversation.get().record(player.getGameProfile().getName(), event.getRawText());
        SynaStoryDirector.get().onPlayerChat(player, event.getRawText());
        SynaBoredomDirector.get().recordConversation(player);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (HorrorEntityEventDirector.get().isDirectedEntity(event.getEntity())) return;
        net.minecraft.world.entity.LivingEntity living = event.getEntity();
        if (living.getKillCredit() instanceof net.minecraft.server.level.ServerPlayer player) {
            SynaBoredomDirector.get().recordKill(player, living);
            String victim = living.getType().toString();
            if (victim.contains("ender_dragon") || victim.contains("wither") || victim.contains("warden")) {
                SynaBoredomDirector.get().observe(player, "major_kill", victim);
            }
        }
        SynaController.get().onLivingEntityDeath(event.getEntity());
    }

    @SubscribeEvent
    public void onItemCrafted(ItemCraftedEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            SynaBoredomDirector.get().record(player, SynaBoredomPolicy.Activity.CRAFTING);
        }
    }

    @SubscribeEvent
    public void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            SynaBoredomDirector.get().record(player, SynaBoredomPolicy.Activity.DIMENSION_TRAVEL);
            SynaBoredomDirector.get().observe(player, "dimension", event.getTo().location().toString());
        }
    }

    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            SynaBoredomDirector.get().recordPlacedBlock(player);
        }
    }

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        if (HorrorEntityEventDirector.get().isDirectedEntity(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onPlayerDestroyItem(PlayerDestroyItemEvent event) {
        SynaEpisodeDirector.get().onToolDestroyed(event);
    }

    @SubscribeEvent
    public void onBlockBroken(BlockEvent.BreakEvent event) {
        SynaEpisodeDirector.get().onBlockBroken(event);
    }
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            BridgeCommandQueue.get().drainAndExecute();
            BridgeIntentQueue.get().drainAndExecute();
            SynaController.get().tick();
            SynaController.get().tickMobilityDebugHud();
            PlayerAttentionTracker.get().tick(event.getServer());
            HorrorEntityEventDirector.get().tick(event.getServer());
            SynaStoryDirector.get().tick(event.getServer());
            SynaDangerousSilenceDirector.get().tick(event.getServer());
            SynaBoredomDirector.get().tick(event.getServer());
            SynaEpisodeDirector.get().tick(event.getServer());
            SynaManifestationDirector.get().tick(event.getServer());
            SynaFirstContactDirector.get().tick(event.getServer());
            SynaOpeningOmenDirector.get().tick(event.getServer());
            BridgeState.get().onServerTick();
            BlueprintNetwork.onServerTickEnd();
        }
    }
}
