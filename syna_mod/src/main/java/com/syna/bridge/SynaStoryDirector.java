package com.syna.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;

import java.util.Locale;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class SynaStoryDirector {
    private static final SynaStoryDirector INSTANCE = new SynaStoryDirector();
    private static final int SCENE_COOLDOWN_TICKS = 20 * 30;
    private final Map<Long, PendingEntityScene> pendingEntityScenes = new HashMap<>();

    private SynaStoryDirector() {}

    public static SynaStoryDirector get() {
        return INSTANCE;
    }

    public void tick(MinecraftServer server) {
        if (server == null) return;
        SynaStoryData data = SynaStoryData.get(server);
        if (data.trueNameSealed) return;
        data.storyTicks++;
        reconcileEntityScenes(data);
        if (data.storyTicks % 200 == 0) {
            data.fearBudget = StoryPacingPolicy.recover(data.fearBudget);
            observeHorrorOutcome(data);
            advanceNaturally(server, data);
            triggerAutomaticScene(server, data);
            data.setDirty();
        }
    }

    public void onPlayerChat(ServerPlayer player, String text) {
        if (player == null || text == null || text.isBlank()) return;
        SynaStoryData data = SynaStoryData.get(player.server);
        String normalized = text.toLowerCase(Locale.ROOT);
        data.interactions++;
        if (containsAny(normalized, "\u5bf9\u4e0d\u8d77", "\u62b1\u6b49", "\u8c22\u8c22", "\u8f9b\u82e6", "please", "sorry", "thank")) {
            data.trust = clamp(data.trust + 3);
            data.pressure = clamp(data.pressure - 2);
        }
        if (containsAny(normalized, "\u5feb\u70b9", "\u95ed\u5634", "\u6ca1\u7528", "\u5e9f\u7269", "\u6eda", "\u547d\u4ee4\u4f60", "hurry", "useless", "shut up")) {
            data.pressure = clamp(data.pressure + 5);
            data.trust = clamp(data.trust - 2);
        }
        if (normalized.contains("\u4f60\u8fd8\u597d\u5417") || normalized.contains("\u600e\u4e48\u4e86") || normalized.contains("\u5bb3\u6015")) {
            discover(data, "noticed_syna");
        }
        data.lastReason = "player_chat";
        advanceNaturally(player.server, data);
        data.setDirty();
    }

    public boolean command(MinecraftServer server, String action, String playerName, String reason) {
        if (server == null) return false;
        SynaStoryData data = SynaStoryData.get(server);
        String normalized = action == null ? "status" : action.trim().toLowerCase(Locale.ROOT);
        boolean force = normalized.startsWith("force_");
        if (force) normalized = normalized.substring("force_".length());
        if (normalized.startsWith("chapter_")) {
            try {
                setChapter(data, Integer.parseInt(normalized.substring("chapter_".length())), "debug");
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if ("reset".equals(normalized)) {
            reset(data);
            return true;
        }
        if (normalized.startsWith("trust_")) {
            data.trust = parseDebugValue(normalized, "trust_", data.trust);
            data.setDirty();
            return true;
        }
        if (normalized.startsWith("pressure_")) {
            data.pressure = parseDebugValue(normalized, "pressure_", data.pressure);
            data.setDirty();
            return true;
        }
        if ("resolve".equals(normalized)) {
            resolve(server, data, reason);
            return true;
        }
        if ("status".equals(normalized)) return true;
        if (!force && data.storyTicks < data.nextSceneTick) {
            BridgeState.get().setLastEvent("story_scene_blocked:cooldown:" + normalized);
            return false;
        }
        ServerPlayer player = findPlayer(server, playerName);
        boolean accepted = triggerScene(server, player, data, normalized, reason, force);
        if (accepted) {
            data.nextSceneTick = data.storyTicks + SCENE_COOLDOWN_TICKS;
            data.setDirty();
        }
        return accepted;
    }

    public JsonObject toJson(MinecraftServer server) {
        JsonObject json = new JsonObject();
        if (server == null) {
            json.addProperty("available", false);
            return json;
        }
        SynaStoryData data = SynaStoryData.get(server);
        json.addProperty("available", true);
        json.addProperty("chapter", data.chapter);
        json.addProperty("chapterName", chapterName(data.chapter));
        json.addProperty("scene", data.scene);
        json.addProperty("trust", data.trust);
        json.addProperty("pressure", data.pressure);
        json.addProperty("interactions", data.interactions);
        json.addProperty("dependency", data.dependency);
        json.addProperty("episodeId", data.episodeId);
        json.addProperty("proactiveHelpCount", data.proactiveHelpCount);
        json.addProperty("proactiveHelpRemaining", Math.max(0, 2 - data.proactiveHelpCount));
        json.addProperty("blocksMinedThisEpisode", data.blocksMinedThisEpisode);
        json.addProperty("stoneRevealThreshold", data.stoneRevealThreshold);
        json.addProperty("outcome", data.outcome);
        json.addProperty("lastReason", data.lastReason);
        JsonObject entityEvents = HorrorEntityEventDirector.get().toJson();
        json.addProperty("enforcerActive", entityEvents.has("active") && !entityEvents.get("active").isJsonNull()
                && "enforcer".equals(entityEvents.getAsJsonObject("active").get("template").getAsString()));
        json.addProperty("cooldownTicks", Math.max(0L, data.nextSceneTick - data.storyTicks));
        json.addProperty("fearBudget", data.fearBudget);
        json.addProperty("quietTicks", Math.max(0L, data.quietUntilTick - data.storyTicks));
        json.addProperty("presenceProofCount", data.presenceProofCount);
        json.addProperty("presenceProofCooldownTicks", data.lastPresenceProofTick <= 0L ? 0L : Math.max(0L,
                PresenceProofPolicy.COOLDOWN_TICKS - Math.max(0L, data.storyTicks - data.lastPresenceProofTick)));
        json.addProperty("dangerousSilencePending", data.dangerousSilenceDueTick > data.storyTicks);
        json.addProperty("dangerousSilenceTicks", Math.max(0L, data.dangerousSilenceDueTick - data.storyTicks));
        JsonArray clues = new JsonArray();
        for (String clue : data.clues) clues.add(clue);
        json.add("clues", clues);
        JsonArray episodeEvents = new JsonArray();
        for (String event : data.episodeEvents) episodeEvents.add(event);
        json.add("episodeEvents", episodeEvents);
        json.add("identityLore", SynaIdentityLoreDirector.state(data));
        JsonArray identityDisclosures = new JsonArray();
        for (String disclosure : data.identityDisclosures) identityDisclosures.add(disclosure);
        json.add("identityDisclosures", identityDisclosures);
        return json;
    }

    public String statusLine(MinecraftServer server) {
        SynaStoryData data = SynaStoryData.get(server);
        return "chapter=" + data.chapter + "/" + chapterName(data.chapter)
                + ", scene=" + data.scene + ", trust=" + data.trust + ", pressure=" + data.pressure
                + ", dependency=" + data.dependency + ", episode=" + data.episodeId
                + ", help=" + data.proactiveHelpCount + "/2, clues=" + data.clues.size() + ", outcome=" + data.outcome;
    }

    private void advanceNaturally(MinecraftServer server, SynaStoryData data) {
        if (data.chapter == 1 && data.interactions >= 3) setChapter(data, 2, "relationship_established");
        if (data.chapter == 2 && ((data.clues.contains("wrong_footsteps") && data.clues.contains("red_trace"))
                || data.interactions >= 8)) setChapter(data, 3, "anomalies_noticed");
        if (data.chapter == 3 && (data.pressure >= 20 || data.trust >= 15)) setChapter(data, 4, "boundary_defined");
        if (data.chapter == 4 && data.clues.contains("identity_fragment")
                && (data.pressure >= 20 || data.trust >= 20)) setChapter(data, 5, "truth_revealed");
        if (data.chapter == 5 && !"unresolved".equals(data.outcome)) data.scene = "aftermath";
    }

    private void triggerAutomaticScene(MinecraftServer server, SynaStoryData data) {
        if (server.getPlayerList().getPlayers().isEmpty() || data.storyTicks < data.nextSceneTick
                || !"unresolved".equals(data.outcome)) return;
        String next = switch (data.chapter) {
            case 1 -> data.interactions >= 1 && !data.clues.contains("first_presence") ? "observe" : null;
            case 2 -> !data.clues.contains("wrong_footsteps") ? "footsteps"
                    : !data.clues.contains("red_trace") ? "trace"
                    : !data.episodeEvents.contains("scene:watcher") ? "watcher"
                    : !data.clues.contains("vanishing") ? "disappear" : null;
            case 3 -> !data.clues.contains("boundary_warning") ? "boundary"
                    : data.pressure >= 8 && !data.episodeEvents.contains("scene:stalker") ? "stalker"
                    : data.pressure >= 12 && !data.episodeEvents.contains("scene:touch") ? "touch" : null;
            case 4 -> !data.clues.contains("identity_fragment") ? "reveal"
                    : data.pressure >= 14 && !data.episodeEvents.contains("scene:ambush") ? "ambush"
                    : data.pressure >= 18 && !data.episodeEvents.contains("scene:enforcer") ? "enforcer"
                    : data.pressure >= 20 && !"pursuit".equals(data.scene) ? "pursuit" : null;
            case 5 -> data.pressure >= 30 && !"judgment".equals(data.scene) ? "judgment" : null;
            default -> null;
        };
        if (next != null && triggerScene(server, findPlayer(server, null), data, next, "automatic_director", false)) {
            data.nextSceneTick = data.storyTicks + SCENE_COOLDOWN_TICKS;
        }
    }

    private boolean triggerScene(MinecraftServer server, ServerPlayer player, SynaStoryData data, String scene,
                                 String reason, boolean bypassPacing) {
        if (!bypassPacing && !StoryPacingPolicy.canStart(data.fearBudget, data.quietUntilTick,
                data.storyTicks, scene)) {
            BridgeState.get().setLastEvent("story_scene_blocked:pacing:" + scene);
            return false;
        }
        String episodeKey = "scene:" + scene;
        if (!StoryEntityLedgerPolicy.canSchedule(scene, data.episodeId, data.storyTicks,
                data.episodeEvents, data.persistentEntityEvents, data.lastEntityTemplateTicks)) {
            BridgeState.get().setLastEvent("story_scene_blocked:entity_ledger:" + scene);
            return false;
        }
        int requiredChapter = switch (scene) {
            case "observe", "arrival" -> 1;
            case "footsteps", "trace", "watcher", "disappear" -> 2;
            case "boundary", "silence", "warning", "stalker", "touch" -> 3;
            case "reveal", "ambush", "enforcer", "pursuit", "locked_door" -> 4;
            case "judgment", "mercy", "ending" -> 5;
            default -> 99;
        };
        if (data.chapter < requiredChapter) {
            BridgeState.get().setLastEvent("story_scene_blocked:chapter:" + scene);
            return false;
        }
        String entityTemplate = switch (scene) {
            case "watcher" -> "watcher";
            case "stalker" -> "stalker";
            case "ambush" -> "ambush";
            case "enforcer" -> "enforcer";
            default -> null;
        };
        if (entityTemplate != null) {
            HorrorEntityEventDirector.ScheduleResult scheduled = HorrorEntityEventDirector.get().schedule(
                    player, entityTemplate, entityForScene(scene, data), reason);
            if (!scheduled.accepted()) {
                BridgeState.get().setLastEvent("story_scene_blocked:schedule:" + scene + ":" + scheduled.reason());
                return false;
            }
            pendingEntityScenes.put(scheduled.eventId(), new PendingEntityScene(scene, data.scene, data.episodeId,
                    reason == null || reason.isBlank() ? "director" : reason));
            data.episodeEvents.add(episodeKey);
            data.persistentEntityEvents.add(StoryEntityLedgerPolicy.persistentKey(
                    scene, data.episodeId, data.storyTicks));
            data.lastEntityTemplateTicks.put(scene, data.storyTicks);
            data.lastReason = "entity_scene_scheduled:" + scene;
            data.setDirty();
            BridgeState.get().setLastEvent("story_scene_scheduled:" + scene + ":event=" + scheduled.eventId());
            return true;
        }
        data.scene = scene;
        data.lastReason = reason == null || reason.isBlank() ? "director" : reason;
        ServerLevel level = player == null ? server.overworld() : player.serverLevel();
        BlockPos origin = player == null ? level.getSharedSpawnPos() : player.blockPosition();
        switch (scene) {
            case "observe", "arrival" -> {
                if (player != null) SynaController.get().summonSilently(player, "story_observe", true);
                discover(data, "first_presence");
            }
            case "footsteps" -> {
                level.playSound(null, origin.offset(6, 0, -5), SoundEvents.WOOD_STEP, SoundSource.AMBIENT, 0.8F, 0.55F);
                discover(data, "wrong_footsteps");
            }
            case "trace" -> {
                placeRedstoneMark(level, origin.offset(4, 0, 3));
                discover(data, "red_trace");
            }
            case "disappear" -> {
                if (!SynaManifestationDirector.get().beginLookBehindPrank(player)) {
                    SynaController.get().handle(new BridgeCommand("despawn_syna", null, null, null, null, null,
                            null, null, null, null, null));
                }
                discover(data, "vanishing");
            }
            case "boundary", "silence", "warning" -> {
                data.pressure = clamp(data.pressure + 6);
                level.setWeatherParameters(0, 20 * 45, true, false);
                discover(data, "boundary_warning");
            }
            case "touch" -> {
                SynaController.get().lightHit(player == null ? null : player.getGameProfile().getName());
                discover(data, "unseen_touch");
            }
            case "reveal" -> {
                if (player != null) SynaController.get().summonSilently(player, "identity_reveal", false);
                if (player != null) SynaTrueNameDirector.get().recordRitualSite(player);
                discover(data, "identity_fragment");
            }
            case "pursuit", "locked_door" -> {
                ensureSynaBody();
                SynaController.get().handle(new BridgeCommand("horror", "countdown", null,
                        player == null ? null : player.getGameProfile().getName(), data.lastReason, null, null, null, null, 140, 15));
            }
            case "judgment" -> {
                ensureSynaBody();
                startJudgmentGame(player, data);
            }
            case "mercy", "ending" -> resolve(server, data, reason);
        }
        data.episodeEvents.add(episodeKey);
        applyPacing(data, scene);
        data.setDirty();
        BridgeState.get().setLastEvent("story_scene:" + scene + ":chapter_" + data.chapter);
        return true;
    }

    private void reconcileEntityScenes(SynaStoryData data) {
        Iterator<Map.Entry<Long, PendingEntityScene>> iterator = pendingEntityScenes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, PendingEntityScene> entry = iterator.next();
            long eventId = entry.getKey();
            PendingEntityScene pending = entry.getValue();
            if (pending.episodeId != data.episodeId) {
                iterator.remove();
                continue;
            }
            HorrorEntityEventDirector.EventStatus status = HorrorEntityEventDirector.get().status(eventId);
            switch (status.state()) {
                case SCHEDULED -> { }
                case ACTIVE, EXPOSED -> {
                    if (!pending.committed) commitEntityScene(data, pending, eventId);
                }
                case COMPLETED -> {
                    if (!pending.committed) commitEntityScene(data, pending, eventId);
                    discover(data, clueForEntityScene(pending.scene));
                    applyPacing(data, pending.scene);
                    data.lastReason = "entity_scene_completed:" + pending.scene + ":" + status.reason();
                    data.setDirty();
                    iterator.remove();
                }
                case ABORTED, UNKNOWN -> {
                    if (pending.committed) {
                        data.episodeEvents.remove("scene:" + pending.scene);
                        if (pending.scene.equals(data.scene)) data.scene = pending.previousScene;
                        data.pressure = clamp(data.pressure - pending.pressureDelta);
                    }
                    data.nextSceneTick = data.storyTicks + 20L * 5L;
                    data.lastReason = "entity_scene_aborted:" + pending.scene + ":" + status.reason();
                    data.setDirty();
                    BridgeState.get().setLastEvent(data.lastReason);
                    iterator.remove();
                }
            }
        }
    }

    private void commitEntityScene(SynaStoryData data, PendingEntityScene pending, long eventId) {
        pending.committed = true;
        data.scene = pending.scene;
        data.lastReason = pending.reason;
        data.episodeEvents.add("scene:" + pending.scene);
        if ("enforcer".equals(pending.scene)) {
            pending.pressureDelta = 4;
            data.pressure = clamp(data.pressure + pending.pressureDelta);
        }
        data.setDirty();
        BridgeState.get().setLastEvent("story_scene_active:" + pending.scene + ":event=" + eventId);
    }

    private String clueForEntityScene(String scene) {
        return switch (scene) {
            case "watcher" -> "silent_watcher";
            case "stalker" -> "unseen_stalker";
            case "ambush" -> "entity_ambush";
            case "enforcer" -> "summoned_enforcer";
            default -> "directed_entity";
        };
    }

    private void applyPacing(SynaStoryData data, String scene) {
        data.fearBudget = StoryPacingPolicy.spend(data.fearBudget, scene);
        data.quietUntilTick = StoryPacingPolicy.quietUntil(data.quietUntilTick, data.storyTicks, scene);
    }

    private void startJudgmentGame(ServerPlayer player, SynaStoryData data) {
        String playerName = player == null ? null : player.getGameProfile().getName();
        if (data.episodeId % 2 == 1) {
            String clue = "\u6211\u751f\u5728\u9ed1\u6697\u91cc\uff0c\u70e7\u6389\u4ee5\u540e\u80fd\u8ba9\u7194\u7089\u7ee7\u7eed\u547c\u5438\u3002\u628a\u7b54\u6848\u4e22\u5230\u6211\u811a\u8fb9\u3002";
            SynaController.get().handle(new BridgeCommand("horror", "challenge_block", "minecraft:coal",
                    playerName, clue, null, null, null, null, 1, 120));
            if (player != null) player.sendSystemMessage(Component.literal("[Syna] \u8c1c\u9898\uff1a" + clue));
        } else {
            String clue = "\u72e9\u730e\u6e05\u5355\uff1a\u4e09\u53ea\u50f5\u5c38\u3002\u4f60\u7684\u6bcf\u4e00\u6b21\u51fb\u6740\uff0c\u6211\u90fd\u4f1a\u6570\u3002";
            SynaController.get().handle(new BridgeCommand("horror", "challenge_kill", "minecraft:zombie",
                    playerName, clue, null, null, null, null, 3, 180));
            if (player != null) player.sendSystemMessage(Component.literal("[Syna] " + clue));
        }
    }

    private void resolve(MinecraftServer server, SynaStoryData data, String reason) {
        if (data.trust >= 25 && data.pressure < 25) data.outcome = "reconciled";
        else if (data.trust >= 10) data.outcome = "unstable_companionship";
        else if (data.pressure >= 45) data.outcome = "syna_took_over";
        else data.outcome = "syna_left";
        data.chapter = 5;
        data.scene = "aftermath";
        data.lastReason = reason == null || reason.isBlank() ? "judgment_resolved" : reason;
        data.setDirty();
        server.getPlayerList().broadcastSystemMessage(Component.literal("[Syna] \u8fd9\u4e00\u8f6e\u5df2\u7ecf\u7ed3\u675f\uff1a" + data.outcome), false);
        SynaController.get().handle(new BridgeCommand("horror", "forgive", null, null, "story_resolved", null, null, null, null, null, null));
    }

    private void setChapter(SynaStoryData data, int chapter, String reason) {
        data.chapter = Math.max(1, Math.min(5, chapter));
        data.scene = switch (data.chapter) {
            case 1 -> "arrival";
            case 2 -> "anomaly";
            case 3 -> "boundary";
            case 4 -> "revelation";
            default -> "judgment";
        };
        data.lastReason = reason;
        data.setDirty();
        BridgeState.get().setLastEvent("story_chapter:" + data.chapter + ":" + reason);
    }

    private void reset(SynaStoryData data) {
        pendingEntityScenes.clear();
        data.chapter = 1;
        data.trust = 0;
        data.pressure = 0;
        data.interactions = 0;
        data.dependency = 0;
        data.episodeId = 1;
        data.proactiveHelpCount = 0;
        data.lastToolGiftCycle = 0;
        data.lastEligibleToolBreakTick = -1L;
        data.blocksMinedThisEpisode = 0;
        data.stoneRevealThreshold = 6;
        data.storyTicks = 0;
        data.nextSceneTick = 0;
        data.quietUntilTick = 0;
        data.observedHorrorEpisode = 0;
        data.lastPresenceProofTick = 0;
        data.presenceProofCount = 0;
        data.dangerousSilenceDueTick = 0;
        data.lastDangerousSilenceTick = 0;
        data.dangerousSilenceSequence = 0;
        data.dangerousSilencePlayer = "";
        data.fearBudget = StoryPacingPolicy.INITIAL_BUDGET;
        data.scene = "arrival";
        data.outcome = "unresolved";
        data.lastReason = "reset";
        data.clues.clear();
        data.episodeEvents.clear();
        data.persistentEntityEvents.clear();
        data.lastEntityTemplateTicks.clear();
        data.identityDisclosures.clear();
        data.trueNameClues = 0;
        data.ritualSiteKnown = false;
        data.ritualDimension = "";
        data.ritualX = 0;
        data.ritualY = 0;
        data.ritualZ = 0;
        data.lastTrueNameAttemptTick = 0;
        data.trueNameSealed = false;
        data.setDirty();
    }

    private static final class PendingEntityScene {
        private final String scene;
        private final String previousScene;
        private final int episodeId;
        private final String reason;
        private boolean committed;
        private int pressureDelta;

        private PendingEntityScene(String scene, String previousScene, int episodeId, String reason) {
            this.scene = scene;
            this.previousScene = previousScene;
            this.episodeId = episodeId;
            this.reason = reason;
        }
    }

    private void discover(SynaStoryData data, String clue) {
        if (data.clues.add(clue)) data.setDirty();
    }

    private void placeRedstoneMark(ServerLevel level, BlockPos center) {
        int[][] mark = {{-1, 0}, {0, -1}, {0, 0}, {0, 1}, {1, 0}};
        for (int[] offset : mark) {
            BlockPos pos = center.offset(offset[0], 0, offset[1]);
            BlockPos surface = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
            if (level.getBlockState(surface).isAir() && level.getBlockState(surface.below()).isSolid()) {
                level.setBlock(surface, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
            }
        }
    }

    private String entityForScene(String scene, SynaStoryData data) {
        return switch (scene) {
            case "watcher" -> "minecraft:skeleton";
            case "stalker" -> "minecraft:husk";
            case "ambush" -> "minecraft:spider";
            case "enforcer" -> switch (Math.floorMod(data.episodeId, 4)) {
                case 0 -> "minecraft:iron_golem";
                case 1 -> "minecraft:husk";
                case 2 -> "minecraft:spider";
                default -> "minecraft:wolf";
            };
            default -> "minecraft:zombie";
        };
    }

    private void observeHorrorOutcome(SynaStoryData data) {
        SynaController controller = SynaController.get();
        long episode = controller.getHorrorEpisodeId();
        String outcome = controller.getHorrorLastOutcome();
        if (episode <= 0 || episode == data.observedHorrorEpisode || outcome == null || "active".equals(outcome)) return;
        data.observedHorrorEpisode = episode;
        SynaEpisodeDirector.get().onHorrorResolved(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer(), outcome);
        if ("confession_accepted".equals(outcome) || "trial_completed".equals(outcome) || "forgiven".equals(outcome)) {
            data.trust = clamp(data.trust + 8);
            data.pressure = clamp(data.pressure - 8);
            discover(data, "mercy_remembered");
        } else if ("target_died".equals(outcome)) {
            data.pressure = clamp(data.pressure + 15);
            data.trust = clamp(data.trust - 8);
            discover(data, "death_remembered");
        } else if ("syna_withdrew".equals(outcome)) {
            data.pressure = clamp(data.pressure + 4);
            discover(data, "unfinished_hunt");
        }
        data.lastReason = "horror_outcome:" + outcome;
        data.setDirty();
    }

    private int parseDebugValue(String action, String prefix, int fallback) {
        try {
            return clamp(Integer.parseInt(action.substring(prefix.length())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private ServerPlayer findPlayer(MinecraftServer server, String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getGameProfile().getName().equalsIgnoreCase(playerName)) return player;
            }
        }
        ServerPlayer bound = BridgeState.get().getBoundPlayer();
        if (bound != null) return bound;
        return server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
    }

    private void ensureSynaBody() {
        if (SynaController.get().getSyna() == null) {
            SynaController.get().handle(new BridgeCommand("spawn_syna", null, null, null, null, null,
                    null, null, null, null, null));
        }
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private int clamp(int value) {
        return Math.max(-100, Math.min(100, value));
    }

    private String chapterName(int chapter) {
        return switch (chapter) {
            case 1 -> "presence";
            case 2 -> "anomaly";
            case 3 -> "boundary";
            case 4 -> "revelation";
            default -> "judgment";
        };
    }
}
