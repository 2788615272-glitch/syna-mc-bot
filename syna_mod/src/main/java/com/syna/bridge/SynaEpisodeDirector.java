package com.syna.bridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.event.level.BlockEvent;

public final class SynaEpisodeDirector {
    private static final SynaEpisodeDirector INSTANCE = new SynaEpisodeDirector();
    private static final int MAX_PROACTIVE_HELP = 2;

    private SynaEpisodeDirector() {}

    public static SynaEpisodeDirector get() {
        return INSTANCE;
    }

    public void tick(MinecraftServer server) {
        if (server == null || server.getTickCount() % 40 != 0) return;
        SynaStoryData data = SynaStoryData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getFoodData().getFoodLevel() <= 6) {
                offerHelp(player, data, "low_food", Items.COOKED_BEEF, 3);
            }
        }
    }

    public void onToolDestroyed(PlayerDestroyItemEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Item replacement = replacementFor(event.getOriginal());
        if (replacement == null) return;
        SynaStoryData data = SynaStoryData.get(player.server);
        SynaBoredomData boredom = SynaBoredomData.get(player.server);
        long currentTick = data.storyTicks;
        boolean eligible = ToolGiftPolicy.canOffer(boredom.cycleNumber, currentTick,
                data.lastToolGiftCycle, data.lastEligibleToolBreakTick);
        data.lastEligibleToolBreakTick = currentTick;
        data.setDirty();
        if (!eligible) return;
        if (offerHelp(player, data, "tool_broke_" + replacement, replacement, 1)) {
            data.lastToolGiftCycle = boredom.cycleNumber;
            data.setDirty();
        }
    }

    public void onHorrorResolved(MinecraftServer server, String outcome) {
        if (server == null || outcome == null || outcome.equals("active") || outcome.equals("none")) return;
        SynaStoryData data = SynaStoryData.get(server);
        data.episodeId++;
        data.proactiveHelpCount = 0;
        data.blocksMinedThisEpisode = 0;
        data.stoneRevealThreshold = 5 + Math.floorMod(data.episodeId * 3, 4);
        data.episodeEvents.clear();
        if ("trial_completed".equals(outcome) || "confession_accepted".equals(outcome) || "forgiven".equals(outcome)) {
            data.trust = Math.min(100, data.trust + 6);
            data.pressure = Math.max(-100, data.pressure - 15);
            data.scene = "aftermath";
            data.nextSceneTick = data.storyTicks + 20L * 90L;
        }
        data.lastReason = "episode_reset:" + outcome;
        data.setDirty();
    }

    public void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        BlockState broken = event.getState();
        if (broken.is(net.minecraft.world.level.block.Blocks.DIAMOND_ORE)
                || broken.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_DIAMOND_ORE)) {
            SynaBoredomDirector.get().observe(player, "rare_block", "diamond");
            SynaBoredomDirector.get().record(player, SynaBoredomPolicy.Activity.DISCOVERY);
        } else if (broken.is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS)) {
            SynaBoredomDirector.get().observe(player, "rare_block", "ancient_debris");
            SynaBoredomDirector.get().record(player, SynaBoredomPolicy.Activity.DISCOVERY);
        } else if (broken.is(net.minecraft.world.level.block.Blocks.EMERALD_ORE)
                || broken.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_EMERALD_ORE)) {
            SynaBoredomDirector.get().observe(player, "rare_block", "emerald");
        }
        MiningTrajectoryTracker.Observation trajectory = MiningTrajectoryTracker.get()
                .observe(player, event.getPos(), broken);
        if (!trajectory.accepted()) return;
        SynaStoryData data = SynaStoryData.get(player.server);
        data.blocksMinedThisEpisode++;
        data.setDirty();
        SynaBoredomDirector.get().recordMinedBlock(player);
        if (data.blocksMinedThisEpisode < data.stoneRevealThreshold
                || data.episodeEvents.contains("manifest:mine_reveal")) return;
        if (SynaManifestationDirector.get().revealAfterMining(player, event.getPos())) {
            data.episodeEvents.add("manifest:mine_reveal");
            data.setDirty();
        }
    }

    private boolean offerHelp(ServerPlayer player, SynaStoryData data, String eventKey, Item item, int count) {
        if (data.proactiveHelpCount >= MAX_PROACTIVE_HELP || !data.episodeEvents.add("help:" + eventKey)) return false;
        data.proactiveHelpCount++;
        data.dependency = Math.min(100, data.dependency + 6);
        data.lastReason = "proactive_help:" + eventKey;
        data.setDirty();

        SynaController.get().handle(new BridgeCommand("spawn_syna", null, null, null, null, null,
                null, null, null, null, null));
        SynaManifestationDirector.get().onManifested("gift:" + eventKey, true);
        AliceEntity syna = SynaController.get().getSyna();
        double x = syna == null ? player.getX() : syna.getX();
        double y = syna == null ? player.getY() + 0.5D : syna.getY() + 0.5D;
        double z = syna == null ? player.getZ() : syna.getZ();
        ItemEntity drop = new ItemEntity(player.serverLevel(), x, y, z, new ItemStack(item, count));
        drop.getPersistentData().putBoolean("SynaGift", true);
        drop.getPersistentData().putUUID("SynaGiftTarget", player.getUUID());
        drop.setPickUpDelay(10);
        player.serverLevel().addFreshEntity(drop);
        SynaManifestationDirector.get().shortenVisit();
        BridgeConversation.get().recordEpisodeHelp(player.getGameProfile().getName(), eventKey,
                item.toString(), count);
        BridgeState.get().setLastEvent("episode_help:" + eventKey + ":" + data.proactiveHelpCount);
        return true;
    }

    private Item replacementFor(ItemStack broken) {
        if (broken == null || broken.isEmpty()) return null;
        Item item = broken.getItem();
        if (item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE) {
            return Items.STONE_PICKAXE;
        }
        if (item == Items.WOODEN_AXE || item == Items.STONE_AXE) {
            return Items.STONE_AXE;
        }
        if (item == Items.WOODEN_SHOVEL || item == Items.STONE_SHOVEL) {
            return Items.STONE_SHOVEL;
        }
        return null;
    }
}
