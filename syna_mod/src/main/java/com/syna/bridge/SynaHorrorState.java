package com.syna.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

final class SynaHorrorState {
    enum Stage {
        CALM(0),
        WARNING(1),
        STORM(2),
        COUNTDOWN(3),
        HUNTING(4);

        final int id;

        Stage(int id) {
            this.id = id;
        }
    }

    private enum ChallengeKind {
        NONE,
        BLOCK,
        KILL
    }

    private static final int ANGER_DECAY_INTERVAL_TICKS = 20 * 30;
    private static final int WARNING_THRESHOLD = 30;
    private static final int STORM_THRESHOLD = 70;
    private static final int COUNTDOWN_THRESHOLD = 130;
    private static final int HUNTING_THRESHOLD = 180;
    private static final int HUNT_DAMAGE_COOLDOWN_TICKS = 12;
    private static final double HUNT_DAMAGE_RANGE_SQR = 3.25D;

    private int anger;
    private Stage stage = Stage.CALM;
    private UUID targetUuid;
    private String targetName = "";
    private String targetKind = "none";
    private int countdownTicks;
    private int huntingTicks;
    private int damageCooldownTicks;
    private int ambientTicks;
    private int weatherRestoreTicks;
    private int stageTicks;
    private int omenLevel;
    private long episodeId;
    private String beat = "dormant";
    private String lastOutcome = "none";
    private boolean changedThisTick;
    private String angerKey = "";
    private boolean awaitingConfession;
    private ChallengeKind challengeKind = ChallengeKind.NONE;
    private String challengeTarget = "";
    private String challengeClue = "";
    private int challengeRequired;
    private int challengeProgress;
    private int challengeTicks;
    private int challengeIntroTicks;

    void tick(AliceEntity syna) {
        changedThisTick = false;
        if (syna == null || syna.level().isClientSide) {
            return;
        }

        if (anger > 0 && syna.tickCount % ANGER_DECAY_INTERVAL_TICKS == 0 && stage != Stage.HUNTING) {
            anger = Math.max(0, anger - 2);
        }

        stageTicks++;
        tickStoryBeat(syna);

        Entity target = resolveTarget(syna);
        if (target == null && (stage == Stage.COUNTDOWN || stage == Stage.HUNTING)) {
            soften("target_lost");
        }

        tickChallenge(syna);
        if (challengeIntroTicks > 0) challengeIntroTicks--;

        if (stage == Stage.COUNTDOWN) {
            countdownTicks = Math.max(0, countdownTicks - 1);
            if (countdownTicks <= 0 || anger >= HUNTING_THRESHOLD) {
                enterStage(Stage.HUNTING, syna, "countdown_finished");
            }
        } else if (stage == Stage.HUNTING) {
            huntingTicks++;
            if (challengeKind == ChallengeKind.NONE && huntingTicks > SynaHorrorConfig.huntTicks()) {
                forgive("hunt_timeout");
            } else {
                tickHunt(syna, target);
            }
        }

        if (damageCooldownTicks > 0) {
            damageCooldownTicks--;
        }
        tickAmbience(syna);
        syncToEntity(syna);
    }

    void onAttacked(AliceEntity syna, DamageSource source, float amount) {
        Entity attacker = source == null ? null : source.getEntity();
        if (attacker == null && source != null) {
            attacker = source.getDirectEntity();
        }

        String attackerName = attacker == null ? "unknown" : attacker.getName().getString();
        boolean playerAttack = attacker instanceof ServerPlayer || attacker instanceof Player;
        boolean livingAttack = attacker instanceof LivingEntity;
        int gain;
        if (playerAttack) {
            gain = 45 + Math.round(amount * 6.0F);
            targetUuid = attacker.getUUID();
            targetName = attackerName;
            targetKind = "player";
        } else if (livingAttack) {
            gain = 8 + Math.round(amount * 2.0F);
            targetUuid = attacker.getUUID();
            targetName = attackerName;
            targetKind = "mob";
        } else {
            gain = 3;
            targetKind = "environment";
            targetName = attackerName;
        }

        anger = Math.min(240, anger + gain);
        BridgeState.get().addDebug("horror_attacked attacker=" + attackerName
                + ",kind=" + targetKind + ",anger=" + anger + ",amount=" + Math.round(amount));
        updateStageFromAnger(syna, playerAttack ? "player_attack" : "non_player_attack");
    }

    void command(AliceEntity syna, String text, String playerName) {
        command(syna, text, playerName, null, null);
    }

    void command(AliceEntity syna, String text, String playerName, Integer requestedAnger) {
        command(syna, text, playerName, requestedAnger, null);
    }

    void command(AliceEntity syna, String text, String playerName, Integer requestedAnger, String reason) {
        String action = text == null ? "status" : text.trim().toLowerCase();
        if (action.isEmpty() || "status".equals(action)) {
            BridgeState.get().setLastEvent("horror_status:" + stage.name().toLowerCase() + ":" + anger + ":" + targetName);
            return;
        }
        Integer inlineAnger = parseInlineAnger(action);
        if (inlineAnger != null) {
            requestedAnger = inlineAnger;
            action = action.split("[ :=]", 2)[0];
        }
        switch (action) {
            case "calm", "forgive", "stop", "mercy" -> {
                if (awaitingConfession && angerKey != null && !angerKey.isBlank()) {
                    BridgeState.get().setLastEvent("horror_ask_wrong:" + safeEvent(angerKey));
                    if (syna != null) {
                        enterStage(Stage.HUNTING, syna, "forgive_requires_key");
                    }
                    return;
                }
                forgive("llm_" + action);
                restoreWeatherNow(syna);
                if (syna != null) {
                    syncToEntity(syna);
                    syna.getNavigation().stop();
                }
            }
            case "key", "reason", "set_key" -> {
                String key = normalizeReason(reason == null || reason.isBlank() ? playerName : reason);
                if (key.isBlank()) {
                    BridgeState.get().setLastEvent("horror_key_failed:empty");
                    return;
                }
                angerKey = key;
                awaitingConfession = true;
                anger = Math.max(anger, clampAnger(requestedAnger == null ? WARNING_THRESHOLD : requestedAnger));
                if (playerName != null && !playerName.isBlank()) {
                    setPlayerTarget(playerName);
                }
                updateStageFromAnger(syna, "llm_key");
                BridgeState.get().setLastEvent("horror_key_set:" + safeEvent(angerKey) + ":" + anger + ":" + targetName);
            }
            case "guess", "confess", "answer" -> {
                String guess = normalizeReason(reason == null || reason.isBlank() ? playerName : reason);
                if (angerKey == null || angerKey.isBlank()) {
                    BridgeState.get().setLastEvent("horror_guess_no_key");
                    return;
                }
                if (matchesKey(guess)) {
                    forgive("key_matched");
                    restoreWeatherNow(syna);
                    if (syna != null) {
                        syncToEntity(syna);
                        syna.getNavigation().stop();
                    }
                    BridgeState.get().setLastEvent("horror_key_matched:" + safeEvent(angerKey));
                } else {
                    anger = Math.min(240, Math.max(anger + 25, HUNTING_THRESHOLD));
                    awaitingConfession = true;
                    if (syna != null) {
                        enterStage(Stage.HUNTING, syna, "wrong_key_guess");
                    }
                    BridgeState.get().setLastEvent("horror_key_wrong:" + safeEvent(guess));
                }
            }
            case "warn" -> {
                anger = Math.max(anger, WARNING_THRESHOLD);
                if (playerName != null && !playerName.isBlank()) {
                    setPlayerTarget(playerName);
                }
                enterStage(Stage.WARNING, syna, "llm_warn");
            }
            case "countdown" -> {
                anger = Math.max(anger, COUNTDOWN_THRESHOLD);
                if (playerName != null && !playerName.isBlank()) {
                    setPlayerTarget(playerName);
                }
                enterStage(Stage.COUNTDOWN, syna, "llm_countdown");
            }
            case "hunt", "chase" -> {
                anger = Math.max(anger, HUNTING_THRESHOLD);
                if (playerName != null && !playerName.isBlank()) {
                    setPlayerTarget(playerName);
                }
                enterStage(Stage.HUNTING, syna, "llm_hunt");
            }
            case "anger", "set_anger", "rage", "annoy", "irritate" -> {
                anger = clampAnger(requestedAnger == null ? anger : requestedAnger);
                if (playerName != null && !playerName.isBlank()) {
                    setPlayerTarget(playerName);
                }
                updateStageFromAnger(syna, "llm_set_anger");
                if (anger < WARNING_THRESHOLD) {
                    stage = Stage.CALM;
                    countdownTicks = 0;
                    huntingTicks = 0;
                    syncToEntity(syna);
                }
                BridgeState.get().setLastEvent("horror_anger:" + anger + ":" + stage.name().toLowerCase() + ":" + targetName);
            }
            case "takeover", "transform", "possess" -> {
                anger = Math.max(clampAnger(requestedAnger == null ? COUNTDOWN_THRESHOLD : requestedAnger), COUNTDOWN_THRESHOLD);
                if (playerName != null && !playerName.isBlank()) {
                    setPlayerTarget(playerName);
                }
                enterStage(anger >= HUNTING_THRESHOLD ? Stage.HUNTING : Stage.COUNTDOWN, syna, "llm_takeover");
            }
            default -> BridgeState.get().setLastEvent("horror_unknown_action:" + action);
        }
    }


    void commandChallenge(AliceEntity syna, String action, String playerName, String target, String clue, Integer count, Integer seconds) {
        String normalized = action == null ? "challenge_block" : action.trim().toLowerCase();
        ChallengeKind kind = normalized.contains("kill") ? ChallengeKind.KILL : ChallengeKind.BLOCK;
        int required = count == null || count <= 0 ? 1 : Math.min(64, count);
        int time = seconds == null || seconds <= 0 ? 120 : Math.max(5, Math.min(600, seconds));
        startChallenge(syna, kind, playerName, target, clue, required, time);
    }

    private void startChallenge(AliceEntity syna, ChallengeKind kind, String playerName, String target, String clue, int required, int seconds) {
        String cleanTarget = normalizeTarget(target);
        if (cleanTarget.isBlank()) {
            BridgeState.get().setLastEvent("horror_challenge_failed:no_target");
            return;
        }
        challengeKind = kind;
        challengeTarget = cleanTarget;
        challengeClue = clue == null || clue.isBlank() ? cleanTarget : clue.trim();
        challengeRequired = Math.max(1, Math.min(64, required));
        challengeProgress = 0;
        challengeTicks = seconds * 20;
        challengeIntroTicks = 20 * 8;
        awaitingConfession = false;
        if (playerName != null && !playerName.isBlank()) {
            setPlayerTarget(playerName);
        }
        anger = Math.max(anger, HUNTING_THRESHOLD);
        if (syna != null) {
            enterStage(Stage.HUNTING, syna, "challenge_" + kind.name().toLowerCase());
        }
        BridgeState.get().setLastEvent("horror_challenge_started:" + kind.name().toLowerCase() + ":" + safeEvent(challengeTarget) + ":" + challengeRequired + ":" + seconds);
    }

    private void tickChallenge(AliceEntity syna) {
        if (challengeKind == ChallengeKind.NONE) {
            return;
        }
        if (challengeTicks > 0) {
            challengeTicks--;
        }
        if (challengeKind == ChallengeKind.BLOCK) {
            checkBlockOffering(syna);
        }
        if (challengeTicks <= 0) {
            BridgeState.get().setLastEvent("horror_challenge_failed:timeout:" + safeEvent(challengeTarget));
            clearChallenge();
            huntingTicks = 0;
            if (syna != null) {
                enterStage(Stage.HUNTING, syna, "challenge_timeout");
            }
        }
    }

    private void checkBlockOffering(AliceEntity syna) {
        if (syna == null || !(syna.level() instanceof ServerLevel level)) {
            return;
        }
        AABB box = syna.getBoundingBox().inflate(3.0D);
        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, box, Entity::isAlive)) {
            if (matchesItemTarget(itemEntity)) {
                int take = Math.min(itemEntity.getItem().getCount(), challengeRequired - challengeProgress);
                if (take <= 0) return;
                challengeProgress += take;
                itemEntity.getItem().shrink(take);
                if (itemEntity.getItem().isEmpty()) itemEntity.discard();
                BridgeState.get().setLastEvent("horror_challenge_progress:block:" + safeEvent(challengeTarget) + ":" + challengeProgress + ":" + challengeRequired);
                if (challengeProgress >= challengeRequired) {
                    completeChallenge(syna, "block_offered");
                }
                return;
            }
        }
    }

    private boolean matchesItemTarget(ItemEntity itemEntity) {
        if (itemEntity == null || itemEntity.getItem().isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(itemEntity.getItem().getItem());
        if (key == null) return false;
        return matchesRegistryName(key, challengeTarget);
    }

    void onEntityKilled(AliceEntity syna, Entity entity) {
        if (challengeKind != ChallengeKind.KILL || entity == null) {
            return;
        }
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (key == null || !matchesRegistryName(key, challengeTarget)) {
            return;
        }
        challengeProgress = Math.min(challengeRequired, challengeProgress + 1);
        BridgeState.get().setLastEvent("horror_challenge_progress:kill:" + safeEvent(challengeTarget) + ":" + challengeProgress + ":" + challengeRequired);
        if (challengeProgress >= challengeRequired) {
            completeChallenge(syna, "kill_completed");
        }
    }

    boolean acceptsKillChallenge(Entity entity) {
        if (challengeKind != ChallengeKind.KILL || entity == null) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key != null && matchesRegistryName(key, challengeTarget);
    }

    boolean isChallengeActor(Entity killer) {
        if (challengeKind == ChallengeKind.NONE || killer == null) {
            return false;
        }
        if (targetUuid == null) {
            return killer instanceof ServerPlayer;
        }
        return targetUuid.equals(killer.getUUID());
    }

    private void completeChallenge(AliceEntity syna, String reason) {
        String target = challengeTarget;
        ChallengeKind kind = challengeKind;
        clearChallenge();
        forgive("challenge_" + reason);
        restoreWeatherNow(syna);
        if (syna != null) {
            syncToEntity(syna);
            syna.getNavigation().stop();
        }
        BridgeState.get().setLastEvent("horror_challenge_completed:" + kind.name().toLowerCase() + ":" + safeEvent(target));
    }

    private void clearChallenge() {
        challengeKind = ChallengeKind.NONE;
        challengeTarget = "";
        challengeClue = "";
        challengeRequired = 0;
        challengeProgress = 0;
        challengeTicks = 0;
    }

    String getChallengeKind() {
        return challengeKind.name().toLowerCase();
    }

    String getChallengeTarget() {
        return challengeTarget == null ? "" : challengeTarget;
    }

    String getChallengeClue() {
        return challengeClue == null ? "" : challengeClue;
    }

    int getChallengeRequired() {
        return challengeRequired;
    }

    int getChallengeProgress() {
        return challengeProgress;
    }

    int getChallengeTicks() {
        return challengeTicks;
    }

    String getChallengeOverlayText() {
        if (challengeKind == ChallengeKind.NONE) return "";
        int seconds = Math.max(0, challengeTicks / 20);
        String task = challengeKind == ChallengeKind.BLOCK ? "谜题" : "狩猎";
        String display = challengeKind == ChallengeKind.BLOCK ? challengeClue : challengeTarget;
        return task + "：" + display + "  进度 " + challengeProgress + "/" + challengeRequired + "  剩余 " + seconds + " 秒";
    }

    int getAnger() {
        return anger;
    }

    String getStageName() {
        return stage.name().toLowerCase();
    }

    String getFormName() {
        return stage == Stage.CALM ? "normal" : "horror";
    }

    boolean isTarget(Entity entity) {
        return entity != null && targetUuid != null && targetUuid.equals(entity.getUUID());
    }

    void onTargetDied(AliceEntity syna, Entity entity) {
        if (!isTarget(entity)) {
            return;
        }
        forgive("target_died");
        restoreWeatherNow(syna);
        syncToEntity(syna);
    }

    String getTargetName() {
        return targetName == null ? "" : targetName;
    }

    String getTargetKind() {
        return targetKind == null ? "none" : targetKind;
    }

    int getCountdownTicks() {
        return countdownTicks;
    }

    boolean isActive() {
        return stage != Stage.CALM;
    }

    boolean isHunting() {
        return stage == Stage.HUNTING;
    }

    void reset(AliceEntity syna) {
        forgive("reset");
        restoreWeatherNow(syna);
        if (syna != null) {
            syncToEntity(syna);
        }
    }

    private Integer parseInlineAnger(String action) {
        if (!(action.startsWith("anger") || action.startsWith("set_anger") || action.startsWith("rage"))) {
            return null;
        }
        String[] parts = action.split("[ :=]", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int clampAnger(int value) {
        return Math.max(0, Math.min(240, value));
    }

    private void updateStageFromAnger(AliceEntity syna, String reason) {
        if (anger >= HUNTING_THRESHOLD) {
            enterStage(Stage.HUNTING, syna, reason);
        } else if (anger >= COUNTDOWN_THRESHOLD) {
            enterStage(Stage.COUNTDOWN, syna, reason);
        } else if (anger >= STORM_THRESHOLD) {
            enterStage(Stage.STORM, syna, reason);
        } else if (anger >= WARNING_THRESHOLD) {
            enterStage(Stage.WARNING, syna, reason);
        }
    }

    private void enterStage(Stage next, AliceEntity syna, String reason) {
        if (next == Stage.COUNTDOWN && stage != Stage.COUNTDOWN) {
            countdownTicks = SynaHorrorConfig.countdownTicks();
        }
        if (next == Stage.HUNTING && stage != Stage.HUNTING) {
            huntingTicks = 0;
            countdownTicks = 0;
        }
        if (stage != next) {
            if (stage == Stage.CALM && next != Stage.CALM) {
                episodeId++;
                omenLevel = 0;
                lastOutcome = "active";
            }
            stage = next;
            stageTicks = 0;
            beat = beatForStage(next);
            changedThisTick = true;
            BridgeState.get().setLastEvent("horror_stage:" + stage.name().toLowerCase() + ":" + reason + ":" + targetName);
            sendStageMessage(syna, reason);
        }
        syncToEntity(syna);
    }

    private void tickHunt(AliceEntity syna, Entity target) {
        if (!(target instanceof LivingEntity living) || !living.isAlive()) {
            return;
        }
        if (target.level() != syna.level()) {
            return;
        }
        syna.setTarget(living);
        double speed = 1.55D;
        if (syna.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
            syna.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.62D);
        }
        syna.getNavigation().moveTo(living, speed);
        lookAt(syna, living.position().add(0.0D, living.getEyeHeight() * 0.7D, 0.0D));
        if (damageCooldownTicks <= 0 && syna.distanceToSqr(living) <= HUNT_DAMAGE_RANGE_SQR) {
            living.hurt(syna.damageSources().mobAttack(syna), SynaHorrorConfig.huntDamage());
            syna.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            damageCooldownTicks = HUNT_DAMAGE_COOLDOWN_TICKS;
            BridgeState.get().setLastEvent("horror_hit:" + targetName);
        }
    }

    void restoreWeatherNow(AliceEntity syna) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        weatherRestoreTicks = 0;
        for (ServerLevel level : server.getAllLevels()) {
            level.setWeatherParameters(20 * 90, 0, false, false);
        }
        BridgeState.get().addDebug("horror_weather_restored");
    }

    private void tickAmbience(AliceEntity syna) {
        if (!(syna.level() instanceof ServerLevel level)) {
            return;
        }
        ambientTicks++;
        if (stage == Stage.WARNING && changedThisTick) {
            level.setDayTime(13000L);
        }
        if ((stage == Stage.STORM || stage == Stage.COUNTDOWN || stage == Stage.HUNTING) && (changedThisTick || ambientTicks % 200 == 0)) {
            level.setWeatherParameters(0, 20 * 90, true, stage == Stage.HUNTING);
            weatherRestoreTicks = 20 * 120;
        }
        if (SynaHorrorConfig.worldScarring()
                && (stage == Stage.COUNTDOWN || stage == Stage.HUNTING)
                && ambientTicks % 60 == 0) {
            scarWorld(level, syna.blockPosition());
        }
        if (stage == Stage.CALM && weatherRestoreTicks > 0) {
            weatherRestoreTicks--;
            if (weatherRestoreTicks == 0) {
                level.setWeatherParameters(20 * 90, 0, false, false);
            }
        }
    }

    private void scarWorld(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 1, 2))) {
            if (level.random.nextInt(24) != 0) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.GRASS_BLOCK)) {
                level.setBlock(pos.immutable(), Blocks.DIRT.defaultBlockState(), 3);
                return;
            }
            if (state.is(Blocks.TALL_GRASS) || state.is(Blocks.GRASS) || state.is(Blocks.FERN)) {
                level.destroyBlock(pos.immutable(), true, null);
                return;
            }
        }
    }

    private Entity resolveTarget(AliceEntity syna) {
        if (targetUuid == null) {
            return null;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(targetUuid);
            if (entity != null && entity.isAlive()) {
                return entity;
            }
        }
        return null;
    }

    private void setPlayerTarget(String playerName) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || playerName == null || playerName.isBlank()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(playerName)) {
                targetUuid = player.getUUID();
                targetName = player.getGameProfile().getName();
                targetKind = "player";
                return;
            }
        }
    }

    private void soften(String reason) {
        if (stage == Stage.HUNTING) {
            anger = Math.min(anger, STORM_THRESHOLD);
            stage = Stage.STORM;
        } else {
            anger = Math.min(anger, WARNING_THRESHOLD);
            stage = anger > 0 ? Stage.WARNING : Stage.CALM;
        }
        countdownTicks = 0;
        huntingTicks = 0;
        BridgeState.get().setLastEvent("horror_softened:" + reason);
    }

    private void forgive(String reason) {
        lastOutcome = outcomeForReason(reason);
        anger = 0;
        stage = Stage.CALM;
        beat = "aftermath";
        stageTicks = 0;
        targetUuid = null;
        targetName = "";
        targetKind = "none";
        countdownTicks = 0;
        huntingTicks = 0;
        damageCooldownTicks = 0;
        awaitingConfession = false;
        clearChallenge();
        BridgeState.get().setLastEvent("horror_calm:" + reason);
    }

    void sealByTrueName(AliceEntity syna) {
        forgive("true_name_sealed");
        restoreWeatherNow(syna);
        if (syna != null) {
            syncToEntity(syna);
            syna.getNavigation().stop();
        }
    }

    String getAngerKey() {
        return angerKey == null ? "" : angerKey;
    }

    boolean isAwaitingConfession() {
        return awaitingConfession;
    }
    String getBeat() {
        return beat;
    }

    int getOmenLevel() {
        return omenLevel;
    }

    long getEpisodeId() {
        return episodeId;
    }

    String getLastOutcome() {
        return lastOutcome;
    }

    private void tickStoryBeat(AliceEntity syna) {
        if (!(syna.level() instanceof ServerLevel level)) {
            return;
        }
        if (stage == Stage.CALM) {
            if ("aftermath".equals(beat) && stageTicks >= 20 * 15) {
                beat = "dormant";
            }
            return;
        }

        if (stage == Stage.WARNING && stageTicks == 20 * 4) {
            emitOmen(level, syna, 1, "silence");
        } else if (stage == Stage.STORM && stageTicks == 20 * 3) {
            emitOmen(level, syna, 2, "wrong_footsteps");
        } else if (stage == Stage.STORM && stageTicks == 20 * 9) {
            beat = "confrontation";
            emitOmen(level, syna, 3, "confrontation");
        } else if (stage == Stage.COUNTDOWN && stageTicks == 20) {
            beat = "countdown";
            emitOmen(level, syna, 4, "rule_given");
        } else if (stage == Stage.HUNTING && challengeKind != ChallengeKind.NONE) {
            beat = "bargain";
        }
    }

    private void emitOmen(ServerLevel level, AliceEntity syna, int levelNumber, String kind) {
        omenLevel = Math.max(omenLevel, levelNumber);
        Entity target = resolveTarget(syna);
        Vec3 origin = target == null ? syna.position() : target.position();
        float pitch = switch (levelNumber) {
            case 1 -> 0.55F;
            case 2 -> 0.72F;
            default -> 0.42F;
        };
        level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.AMBIENT_CAVE.value(),
                SoundSource.AMBIENT, 0.65F, pitch);
        if (levelNumber >= 2) {
            level.playSound(null, origin.x + 5.0D, origin.y, origin.z - 4.0D,
                    SoundEvents.WOOD_STEP, SoundSource.AMBIENT, 0.5F, 0.6F);
        }
        if (levelNumber >= 3) {
            Vec3 center = syna.position().add(0.0D, 1.0D, 0.0D);
            level.sendParticles(ParticleTypes.SOUL, center.x, center.y, center.z,
                    10, 0.35D, 0.7D, 0.35D, 0.01D);
        }
        BridgeState.get().setLastEvent("horror_omen:" + kind + ":" + omenLevel + ":" + targetName);
    }

    private String beatForStage(Stage value) {
        return switch (value) {
            case CALM -> "dormant";
            case WARNING -> "withdrawal";
            case STORM -> "omens";
            case COUNTDOWN -> "countdown";
            case HUNTING -> challengeKind == ChallengeKind.NONE ? "pursuit" : "bargain";
        };
    }

    private String outcomeForReason(String reason) {
        if (reason == null) return "ended";
        if (reason.contains("key_matched")) return "confession_accepted";
        if (reason.contains("true_name_sealed")) return "true_name_sealed";
        if (reason.contains("challenge_")) return "trial_completed";
        if (reason.contains("target_died")) return "target_died";
        if (reason.contains("hunt_timeout")) return "syna_withdrew";
        if (reason.contains("mercy") || reason.contains("forgive")) return "forgiven";
        return "ended";
    }

    private boolean matchesKey(String guess) {
        String expected = normalizeReason(angerKey);
        String actual = normalizeReason(guess);
        return !expected.isBlank() && (actual.contains(expected) || expected.contains(actual));
    }

    private String normalizeTarget(String value) {
        String raw = String.valueOf(value == null ? "" : value).toLowerCase(java.util.Locale.ROOT).trim();
        if (raw.startsWith("minecraft:")) raw = raw.substring("minecraft:".length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        }
        return sb.toString();
    }

    private boolean matchesRegistryName(ResourceLocation key, String target) {
        String clean = normalizeTarget(target);
        if (key == null || clean.isBlank()) return false;
        String path = normalizeTarget(key.getPath());
        String full = normalizeTarget(key.toString());
        return clean.equals(path) || clean.equals(full);
    }

    private String normalizeReason(String value) {
        String raw = String.valueOf(value == null ? "" : value).toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private String safeEvent(String value) {
        String v = String.valueOf(value == null ? "" : value).replace(":", "_").replace("|", "_").trim();
        return v.length() > 80 ? v.substring(0, 80) : v;
    }

    private void syncToEntity(AliceEntity syna) {
        if (syna == null) {
            return;
        }
        int visibleCountdown = stage == Stage.COUNTDOWN ? countdownTicks : 0;
        syna.setHorrorState(stage.id, visibleCountdown, getTargetName(), getChallengeOverlayText(), challengeIntroTicks);
    }

    private void sendStageMessage(AliceEntity syna, String reason) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        String msg = switch (stage) {
            case WARNING -> "[Syna] 天色暗下来了。";
            case STORM -> "[Syna] 雨开始变冷。";
            case COUNTDOWN -> "[Syna] " + (targetName == null || targetName.isBlank() ? "有人" : targetName) + "，倒计时开始。";
            case HUNTING -> "[Syna] " + (targetName == null || targetName.isBlank() ? "别跑。" : targetName + "，别跑。");
            case CALM -> "[Syna] 没事了。";
        };
        server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        if (syna != null) {
            syncToEntity(syna);
        }
    }

    private void lookAt(AliceEntity syna, Vec3 target) {
        Vec3 delta = target.subtract(syna.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F);
        float pitch = (float) (-Math.toDegrees(Math.atan2(delta.y, horizontal)));
        syna.setYRot(yaw);
        syna.setYHeadRot(yaw);
        syna.setXRot(pitch);
    }
}
