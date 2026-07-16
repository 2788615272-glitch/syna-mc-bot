package com.syna.bridge;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

public final class SynaBoredomDirector {
    private static final SynaBoredomDirector INSTANCE = new SynaBoredomDirector();

    private SynaBoredomDirector() {}

    public static SynaBoredomDirector get() {
        return INSTANCE;
    }

    public void tick(MinecraftServer server) {
        if (server == null || server.getPlayerList().getPlayers().isEmpty()) return;
        if (SynaStoryData.get(server).trueNameSealed) return;
        SynaBoredomData data = SynaBoredomData.get(server);
        data.cycleTicks++;
        if (data.cycleTicks - data.lastPassiveTick >= SynaBoredomPolicy.PASSIVE_INTERVAL_TICKS) {
            data.lastPassiveTick = data.cycleTicks;
            data.boredom = SynaBoredomPolicy.afterTime(data.boredom, data.cycleTicks, data.cycleNumber);
            data.setDirty();
        }
        boolean finalCycleEnabled = server.overworld().getGameRules().getBoolean(SynaGameRules.FINAL_CYCLE);
        if (!finalCycleEnabled && !"dormant".equals(data.phase)) {
            SynaController.get().handle(new BridgeCommand("horror", "forgive", null, null,
                    "gamerule_disabled", null, null, null, null, null, null));
            resolveCycle(server, data, "gamerule_disabled");
        } else if (finalCycleEnabled) {
            advanceFinalCycle(server, data);
        }
        if (server.overworld().getGameRules().getBoolean(SynaGameRules.HORROR_EVENTS)) {
            tryHorrorOpportunity(server, data);
        }
        refreshFragmentPresentation(server, data);
    }

    public void record(ServerPlayer player, SynaBoredomPolicy.Activity activity) {
        if (player == null || activity == null) return;
        SynaBoredomData data = SynaBoredomData.get(player.server);
        if (!"dormant".equals(data.phase)) return;
        int prior = data.repetitions.getOrDefault(activity, 0);
        int gain = SynaBoredomPolicy.entertainmentGain(activity, prior);
        data.boredom = SynaBoredomPolicy.afterEntertainment(data.boredom, data.cycleTicks,
                data.cycleNumber, activity, prior);
        data.repetitions.put(activity, prior + 1);
        data.lastActivity = activity.name().toLowerCase(java.util.Locale.ROOT);
        data.lastGain = gain;
        data.setDirty();
        BridgeState.get().setLastEvent("boredom_entertained:" + data.lastActivity + ":" + gain
                + ":repeat=" + prior + ":boredom=" + data.boredom);
    }

    public void recordMinedBlock(ServerPlayer player) {
        if (player == null) return;
        SynaBoredomData data = SynaBoredomData.get(player.server);
        if (++data.miningProgress < 12) {
            data.setDirty();
            return;
        }
        data.miningProgress = 0;
        data.setDirty();
        record(player, SynaBoredomPolicy.Activity.MINING);
    }

    public void recordPlacedBlock(ServerPlayer player) {
        if (player == null) return;
        SynaBoredomData data = SynaBoredomData.get(player.server);
        if (++data.buildingProgress < 8) {
            data.setDirty();
            return;
        }
        data.buildingProgress = 0;
        data.setDirty();
        record(player, SynaBoredomPolicy.Activity.BUILDING);
    }

    public void recordConversation(ServerPlayer player) {
        if (player == null) return;
        SynaBoredomData data = SynaBoredomData.get(player.server);
        long now = player.serverLevel().getGameTime();
        if (now - data.lastConversationTick < 20L * 60L) return;
        data.lastConversationTick = now;
        data.setDirty();
        record(player, SynaBoredomPolicy.Activity.CONVERSATION);
    }

    public void recordKill(ServerPlayer player, Entity victim) {
        if (player == null || victim == null) return;
        record(player, victim instanceof Enemy
                ? SynaBoredomPolicy.Activity.HOSTILE_KILL
                : SynaBoredomPolicy.Activity.PASSIVE_KILL);
    }

    public void observe(ServerPlayer player, String type, String detail) {
        if (player == null || type == null || type.isBlank()) return;
        SynaBoredomData data = SynaBoredomData.get(player.server);
        data.observationId++;
        data.observationType = type;
        data.observationDetail = detail == null ? "" : detail;
        data.observationTick = data.cycleTicks;
        data.setDirty();
        BridgeState.get().setLastEvent("syna_observed:" + type + ":" + data.observationDetail);
    }

    public String debugSetBoredom(ServerPlayer player, int value) {
        if (player == null) return "no_player";
        SynaBoredomData data = SynaBoredomData.get(player.server);
        data.boredom = Math.max(0, Math.min(SynaBoredomPolicy.MAX_BOREDOM, value));
        data.phase = "dormant";
        data.setDirty();
        BridgeState.get().setLastEvent("debug_boredom:" + data.boredom);
        return "boredom=" + data.boredom + ", phase=" + data.phase;
    }

    public String debugReset(ServerPlayer player) {
        if (player == null) return "no_player";
        SynaBoredomData data = SynaBoredomData.get(player.server);
        data.boredom = SynaBoredomPolicy.STARTING_BOREDOM;
        data.cycleTicks = 0L;
        data.lastPassiveTick = 0L;
        data.observedHorrorEpisode = 0L;
        data.phase = "dormant";
        data.ruleKind = "";
        data.ruleTarget = "";
        data.ruleClue = "";
        data.ruleRequired = 0;
        data.ruleSeconds = 0;
        data.repetitions.clear();
        data.miningProgress = 0;
        data.buildingProgress = 0;
        data.lastConversationTick = 0L;
        data.lastOpportunityTick = 0L;
        data.opportunityDay = -1L;
        data.opportunityWindow = 0;
        data.opportunityScene = "";
        data.opportunityAccepted = false;
        data.lastActivity = "debug_reset";
        data.lastGain = 0;
        data.setDirty();
        BridgeState.get().setLastEvent("debug_boredom_reset");
        return "boredom=" + data.boredom + ", phase=" + data.phase;
    }

    public String debugStartFinalCycle(ServerPlayer player) {
        if (player == null) return "no_player";
        SynaBoredomData data = SynaBoredomData.get(player.server);
        data.phase = "dormant";
        data.boredom = SynaBoredomPolicy.MAX_BOREDOM;
        data.setDirty();
        advanceFinalCycle(player.server, data);
        return "boredom=" + data.boredom + ", phase=" + data.phase + ", rule=" + data.ruleKind;
    }

    public String debugLightEvent(ServerPlayer player, String requestedScene) {
        if (player == null) return "no_player";
        String scene = requestedScene == null ? "phantom_steps" : requestedScene.toLowerCase(java.util.Locale.ROOT);
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 behind = player.position().subtract(horizontal.normalize().scale(4.0D));
        switch (scene) {
            case "phantom_steps" -> player.serverLevel().playSound(null, behind.x, behind.y, behind.z,
                    SoundEvents.WOOD_STEP, SoundSource.AMBIENT, 0.9F, 0.55F);
            case "distant_knock" -> player.serverLevel().playSound(null, behind.x + 3.0D, behind.y, behind.z - 2.0D,
                    SoundEvents.STONE_HIT, SoundSource.AMBIENT, 0.85F, 0.45F);
            case "cave_breath" -> player.serverLevel().playSound(null, behind.x, behind.y, behind.z,
                    SoundEvents.AMBIENT_CAVE.get(), SoundSource.AMBIENT, 0.8F, 0.6F);
            case "brief_darkness" -> player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 0, false, false, false));
            default -> { return "unknown_light_event"; }
        }
        SynaBoredomData data = SynaBoredomData.get(player.server);
        data.opportunityId++;
        data.opportunityScene = scene;
        data.opportunityAccepted = true;
        data.lastOpportunityTick = data.cycleTicks;
        data.setDirty();
        BridgeState.get().setLastEvent("debug_light_event:" + scene);
        return "triggered=" + scene + ", opportunityId=" + data.opportunityId;
    }

    public String debugEntityEvent(ServerPlayer player, String requestedScene) {
        if (player == null) return "no_player";
        String scene = requestedScene == null ? "watcher" : requestedScene.toLowerCase(java.util.Locale.ROOT);
        if (!("watcher".equals(scene) || "stalker".equals(scene)
                || "ambush".equals(scene) || "enforcer".equals(scene))) return "unknown_entity_event";
        SynaStoryDirector.get().command(player.server, "chapter_5", player.getGameProfile().getName(), "manual_test");
        boolean accepted = SynaStoryDirector.get().command(player.server, "force_" + scene,
                player.getGameProfile().getName(), "manual_test");
        SynaBoredomData data = SynaBoredomData.get(player.server);
        data.opportunityId++;
        data.opportunityScene = scene;
        data.opportunityAccepted = accepted;
        data.setDirty();
        return "scene=" + scene + ", accepted=" + accepted + ", opportunityId=" + data.opportunityId;
    }

    public void ensureFragment(ServerPlayer player) {
        if (player == null) return;
        SynaBoredomData data = SynaBoredomData.get(player.server);
        ItemStack existing = findFragment(player);
        if (!existing.isEmpty()) {
            SynaFragmentItem.writePages(existing, player.server);
            data.fragmentCycle = Math.max(data.fragmentCycle, data.cycleNumber);
            data.setDirty();
            return;
        }
        ItemStack fragment = new ItemStack(ModItems.SYNA_FRAGMENT.get());
        SynaFragmentItem.writePages(fragment, player.server);
        if (!player.getInventory().add(fragment)) player.drop(fragment, false);
        data.fragmentCycle = data.cycleNumber;
        data.setDirty();
        player.sendSystemMessage(Component.literal("[Syna] 你身上多了一页不属于这个世界的文字。")
                .withStyle(ChatFormatting.DARK_PURPLE));
        BridgeState.get().setLastEvent("boredom_fragment_given:cycle=" + data.cycleNumber);
    }

    public void showFragment(ServerPlayer player) {
        if (player == null) return;
        SynaBoredomData data = SynaBoredomData.get(player.server);
        player.sendSystemMessage(Component.literal("—— Syna 的残页 ——").withStyle(ChatFormatting.DARK_PURPLE));
        player.sendSystemMessage(Component.literal("同一件事做得太久，就只剩下声音，没有味道。"));
        player.sendSystemMessage(Component.literal("石头、工具、鲜血、远方的门……她喜欢你换一种答案。"));
        player.sendSystemMessage(Component.literal("取悦只能延后她厌倦世界的时刻，不能让那一刻消失。"));
        player.sendSystemMessage(Component.literal("倒计时之后，不要请求宽恕。找到她定下的规则。"));
        int mood = FragmentPresentationPolicy.mood(data.boredom, data.phase,
                SynaController.get().getHorrorStage());
        String omen = FragmentPresentationPolicy.omen(mood);
        player.sendSystemMessage(Component.literal(omen).withStyle(ChatFormatting.DARK_RED));
    }

    public double horrorChance(MinecraftServer server) {
        return server == null ? 0.0D : SynaBoredomPolicy.horrorChance(SynaBoredomData.get(server).boredom);
    }

    public JsonObject toJson(MinecraftServer server) {
        JsonObject json = new JsonObject();
        if (server == null) {
            json.addProperty("available", false);
            return json;
        }
        SynaBoredomData data = SynaBoredomData.get(server);
        json.addProperty("available", true);
        json.addProperty("boredom", data.boredom);
        json.addProperty("cycle", data.cycleNumber);
        json.addProperty("phase", data.phase);
        json.addProperty("pressureFloor", SynaBoredomPolicy.pressureFloor(data.cycleTicks, data.cycleNumber));
        json.addProperty("horrorChance", SynaBoredomPolicy.horrorChance(data.boredom));
        json.addProperty("lastActivity", data.lastActivity);
        json.addProperty("lastGain", data.lastGain);
        json.addProperty("ruleKind", data.ruleKind);
        json.addProperty("ruleTarget", data.ruleTarget);
        json.addProperty("ruleClue", data.ruleClue);
        json.addProperty("ruleRequired", data.ruleRequired);
        json.addProperty("opportunityId", data.opportunityId);
        json.addProperty("opportunityScene", data.opportunityScene);
        json.addProperty("opportunityAccepted", data.opportunityAccepted);
        json.addProperty("observationId", data.observationId);
        json.addProperty("observationType", data.observationType);
        json.addProperty("observationDetail", data.observationDetail);
        json.addProperty("observationAgeTicks", Math.max(0L, data.cycleTicks - data.observationTick));
        return json;
    }

    private void advanceFinalCycle(MinecraftServer server, SynaBoredomData data) {
        SynaController controller = SynaController.get();
        if ("dormant".equals(data.phase) && data.boredom >= SynaBoredomPolicy.MAX_BOREDOM) {
            ServerPlayer target = firstPlayer(server);
            if (target == null) return;
            lockRule(data);
            controller.handle(new BridgeCommand("spawn_syna", null, null, target.getGameProfile().getName(),
                    "boredom_final_cycle", null, null, null, null, null, null));
            controller.handle(new BridgeCommand("horror", "countdown", null, target.getGameProfile().getName(),
                    "boredom_final_cycle", null, null, null, null, null, null));
            data.phase = "countdown";
            data.observedHorrorEpisode = controller.getHorrorEpisodeId();
            data.setDirty();
            BridgeState.get().setLastEvent("boredom_final_countdown:cycle=" + data.cycleNumber);
            return;
        }
        if ("countdown".equals(data.phase) && "hunting".equals(controller.getHorrorStage())) {
            startLockedRule(server, data);
            return;
        }
        if (!("countdown".equals(data.phase) || "game".equals(data.phase))) return;
        long horrorEpisode = controller.getHorrorEpisodeId();
        String outcome = controller.getHorrorLastOutcome();
        if (horrorEpisode <= 0 || horrorEpisode != data.observedHorrorEpisode || "active".equals(outcome)) return;
        resolveCycle(server, data, outcome);
    }

    private void tryHorrorOpportunity(MinecraftServer server, SynaBoredomData data) {
        if (!"dormant".equals(data.phase)) return;
        long dayTime = server.overworld().getDayTime();
        long day = Math.floorDiv(dayTime, 24000L);
        int tickOfDay = Math.floorMod((int) dayTime, 24000);
        if (data.opportunityDay != day) {
            data.opportunityDay = day;
            data.opportunityWindow = 0;
            data.setDirty();
        }
        int[] windows = {3500, 11500, 19500};
        while (data.opportunityWindow + 1 < windows.length
                && tickOfDay >= windows[data.opportunityWindow + 1]) {
            data.opportunityWindow++;
        }
        if (data.opportunityWindow >= windows.length || tickOfDay < windows[data.opportunityWindow]) return;
        if (data.lastOpportunityTick > 0L && data.cycleTicks - data.lastOpportunityTick < 1200L) return;
        data.lastOpportunityTick = data.cycleTicks;
        data.setDirty();
        ServerPlayer player = firstPlayer(server);
        if (player == null) return;
        SynaStoryData story = SynaStoryData.get(server);
        String scene = lightOpportunity(player, data.opportunityWindow, data.boredom);
        boolean accepted = true;
        if (data.boredom >= 70 && story.chapter >= 2 && data.opportunityWindow == 2) {
            scene = data.boredom >= 85 && story.chapter >= 3 ? "stalker" : "watcher";
            accepted = SynaStoryDirector.get().command(server, scene,
                    player.getGameProfile().getName(), "boredom_opportunity");
        }
        if (accepted || tickOfDay + 1200 >= (data.opportunityWindow + 1 < windows.length
                ? windows[data.opportunityWindow + 1] : 24000)) {
            data.opportunityWindow++;
        }
        data.opportunityId++;
        data.opportunityScene = scene;
        data.opportunityAccepted = accepted;
        data.setDirty();
        BridgeState.get().setLastEvent("boredom_opportunity:" + scene + ":accepted=" + accepted
                + ":boredom=" + data.boredom);
    }

    private String lightOpportunity(ServerPlayer player, int window, int boredom) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 behind = player.position().subtract(horizontal.normalize().scale(6.0D));
        int variant = Math.floorMod(window + boredom / 20, 4);
        if (variant == 0) {
            player.serverLevel().playSound(null, behind.x, behind.y, behind.z,
                    SoundEvents.WOOD_STEP, SoundSource.AMBIENT, 0.75F, 0.55F);
            return "phantom_steps";
        }
        if (variant == 1) {
            player.serverLevel().playSound(null, behind.x + 4.0D, behind.y, behind.z - 3.0D,
                    SoundEvents.STONE_HIT, SoundSource.AMBIENT, 0.7F, 0.45F);
            return "distant_knock";
        }
        if (variant == 2) {
            player.serverLevel().playSound(null, behind.x, behind.y, behind.z,
                    SoundEvents.AMBIENT_CAVE.get(), SoundSource.AMBIENT, 0.65F, 0.6F);
            return "cave_breath";
        }
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 0, false, false, false));
        return "brief_darkness";
    }

    private void startLockedRule(MinecraftServer server, SynaBoredomData data) {
        ServerPlayer target = firstPlayer(server);
        if (target == null) return;
        String action = "kill".equals(data.ruleKind) ? "challenge_kill" : "challenge_block";
        SynaController.get().handle(new BridgeCommand("horror", action, data.ruleTarget,
                target.getGameProfile().getName(), data.ruleClue, null, null, null, null,
                data.ruleRequired, data.ruleSeconds));
        data.phase = "game";
        data.setDirty();
        BridgeState.get().setLastEvent("boredom_rule_started:" + data.ruleKind + ":" + data.ruleTarget);
    }

    private void lockRule(SynaBoredomData data) {
        int variant = Math.floorMod(data.cycleNumber - 1, 4);
        if (variant == 0) setRule(data, "block", "minecraft:coal", "把三块会燃烧的黑色记忆丢到我脚边。", 3, 150);
        else if (variant == 1) setRule(data, "kill", "minecraft:zombie", "让三具腐烂的身体重新安静。", 3, 180);
        else if (variant == 2) setRule(data, "block", "minecraft:redstone", "给我五份仍在跳动的红色粉尘。", 5, 150);
        else setRule(data, "kill", "minecraft:skeleton", "折断两副会走路的骨头。", 2, 180);
    }

    private void setRule(SynaBoredomData data, String kind, String target, String clue, int required, int seconds) {
        data.ruleKind = kind;
        data.ruleTarget = target;
        data.ruleClue = clue;
        data.ruleRequired = required;
        data.ruleSeconds = seconds;
    }

    private void resolveCycle(MinecraftServer server, SynaBoredomData data, String outcome) {
        boolean success = "trial_completed".equals(outcome);
        if (success) {
            ServerPlayer player = firstPlayer(server);
            if (player != null) SynaTrueNameDirector.get().awardCycleClue(server, player);
        }
        data.cycleNumber++;
        data.cycleTicks = 0L;
        data.lastPassiveTick = 0L;
        data.boredom = Math.min(45, SynaBoredomPolicy.STARTING_BOREDOM + (data.cycleNumber - 1) * 3
                + (success ? 0 : 8));
        data.phase = "dormant";
        data.ruleKind = "";
        data.ruleTarget = "";
        data.ruleClue = "";
        data.ruleRequired = 0;
        data.ruleSeconds = 0;
        data.repetitions.clear();
        data.miningProgress = 0;
        data.buildingProgress = 0;
        data.lastConversationTick = 0L;
        data.lastOpportunityTick = 0L;
        data.opportunityDay = -1L;
        data.opportunityWindow = 0;
        data.opportunityScene = "";
        data.opportunityAccepted = false;
        data.lastActivity = "cycle_resolved:" + outcome;
        data.lastGain = 0;
        data.setDirty();
        BridgeState.get().setLastEvent("boredom_cycle_resolved:" + outcome + ":next=" + data.cycleNumber);
    }

    private ServerPlayer firstPlayer(MinecraftServer server) {
        ServerPlayer bound = BridgeState.get().getBoundPlayer();
        if (bound != null) return bound;
        return server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
    }

    private ItemStack findFragment(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModItems.SYNA_FRAGMENT.get())) return stack;
        }
        return ItemStack.EMPTY;
    }

    private void refreshFragmentPresentation(MinecraftServer server, SynaBoredomData data) {
        int mood = FragmentPresentationPolicy.mood(data.boredom, data.phase,
                SynaController.get().getHorrorStage());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack fragment = findFragment(player);
            if (fragment.isEmpty()) continue;
            int storedMood = fragment.hasTag() ? fragment.getTag().getInt("SynaFragmentMood") : -1;
            if (storedMood != mood) SynaFragmentItem.writePages(fragment, server);
        }
    }
}
