package com.syna.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.syna.bridge.mobility.MobilitySnapshot;
import com.syna.bridge.mobility.MobilityRequest;
import com.syna.bridge.mobility.MobilitySystem;
import com.syna.bridge.mobility.MobilityTickResult;
import com.syna.bridge.mobility.MobilityTickResultType;
import com.syna.bridge.mobility.MovementPermission;
import com.syna.bridge.mobility.ResourceSubtaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.mojang.datafixers.util.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class SynaController {
    private static final Set<String> FORBIDDEN_GIFT_IDS = Set.of(
            "synabridge:syna_fragment",
            "minecraft:air", "minecraft:bedrock", "minecraft:barrier",
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw",
            "minecraft:light", "minecraft:debug_stick", "minecraft:knowledge_book",
            "minecraft:end_portal_frame", "minecraft:spawner"
    );
    private enum MobilityMode {
        IDLE,
        PATHING_TO_ANCHOR,
        ASCENDING_STAIR,
        PLACING_SUPPORT,
        HOLDING_POSITION,
        COMPLETED,
        FAILED
    }

    private enum MobilityPlanType {
        NONE,
        ESCAPE_TO_ANCHOR,
        FOLLOW_ENTITY,
        MOVE_TO_BLOCK,
        MOVE_TO_ENTITY,
        COLLECT_APPROACH
    }

    private enum MobilityPlannerStage {
        IDLE,
        EVALUATING,
        DIRECT_PATH,
        PREPARING_STAIR,
        MOVING_STEP,
        PLACING_SUPPORT,
        WAITING_FOR_SUPPORT,
        BLOCKED_BY_PROTECTION,
        COMPLETED,
        FAILED
    }

    private enum MobilityAction {
        NONE,
        WALK,
        DIG_STAIR,
        PLACE_SUPPORT
    }

    private enum MobilityFallback {
        NONE,
        DIRECT_WALK,
        DIG_UP_STAIR,
        SUPPORT_BLOCK
    }

    private enum BuildingProtectionMode {
        STRICT,
        NORMAL,
        OFF
    }

    private enum MobilityTickResult {
        RUNNING,
        COMPLETED,
        FAILED
    }

    private enum SupportPlacementResult {
        PLACED,
        APPROACHING,
        WAITING_FOR_SUPPORT,
        BLOCKED
    }

    private enum WoodTaskStage {
        IDLE,
        SEARCHING,
        MOVING_TO_LOG,
        ALIGNING,
        BREAKING,
        COLLECTING_DROPS,
        COMPLETED,
        FAILED
    }

    private enum StoneTaskStage {
        IDLE,
        SEARCHING,
        MOVING_TO_BLOCK,
        ALIGNING,
        BREAKING,
        COLLECTING_DROPS,
        COMPLETED,
        FAILED
    }

    private enum CraftTaskStage {
        IDLE,
        CHECKING,
        EXECUTING,
        COMPLETED,
        FAILED
    }

    private record IngredientSpec(String label, int count, Predicate<ItemStack> matcher) {}

    private record CraftRecipe(String targetItemId, Item outputItem, int outputCount, List<IngredientSpec> ingredients) {}

    private static final SynaController INSTANCE = new SynaController();
    private final MobilitySystem globalPathingMobility = new MobilitySystem();
    private final ResourceSubtaskManager resourceSubtaskManager = new ResourceSubtaskManager();
    private final SynaHorrorState horrorState = new SynaHorrorState();
    private static final int WOOD_SEARCH_RADIUS = 16;
    private static final int WOOD_SEARCH_RADIUS_MAX = 64;
    private static final double WOOD_INTERACT_RANGE = 4.25D;
    private static final double WOOD_PICKUP_RANGE = 1.5D;
    private static final int BREAK_TICKS_REQUIRED = 30;
    private static final int WOOD_STAGE_TIMEOUT_TICKS = 80;
    private static final int WOOD_ROAM_TIMEOUT_TICKS = 120;
    private static final int STONE_SEARCH_RADIUS = 18;
    private static final int STONE_SEARCH_RADIUS_MAX = 72;
    private static final double STONE_INTERACT_RANGE = 4.25D;
    private static final double STONE_PICKUP_RANGE = 1.5D;
    private static final int STONE_STAGE_TIMEOUT_TICKS = 90;
    private static final int STONE_ROAM_TIMEOUT_TICKS = 140;
    private static final int STONE_MAX_DEPTH_BELOW_ORIGIN = 5;
    private static final int MOBILITY_STAGE_TIMEOUT_TICKS = 50;
    private static final int MOBILITY_VERTICAL_ASCEND_TRIGGER = 2;
    private static final double MOBILITY_STEP_CENTER_TOLERANCE_SQR = 0.1225D;
    private static final int MOBILITY_SUPPORT_LOCK_TICKS = 24;
    private static final int MOBILITY_SUPPORT_SCAVENGE_RADIUS = 3;
    private static final double MOBILITY_SUPPORT_SCAVENGE_RANGE_SQR = 18.0D;
    private static final int MOBILITY_HUD_INTERVAL_TICKS = 10;
    private static final int MOBILITY_STUCK_WARN_TICKS = 20;

    private UUID synaUuid;
    private UUID hiddenSynaPlayerUuid;
    private String hiddenSynaPlayerName = "";
    private boolean hiddenSynaHadInvisibility;
    private boolean hiddenSynaWasInvulnerable;
    private boolean hiddenSynaWasNoPhysics;
    private boolean hiddenSynaWasCustomNameVisible;
    private String currentTask = "idle";
    private boolean horrorFxEnabled = true;
    private BlockPos moveTarget;
    private UUID followPlayerUuid;
    private UUID mobilityTargetPlayerUuid;
    private MobilityMode mobilityMode = MobilityMode.IDLE;
    private MobilityPlanType mobilityPlanType = MobilityPlanType.NONE;
    private MobilityPlannerStage mobilityPlannerStage = MobilityPlannerStage.IDLE;
    private MobilityAction mobilityAction = MobilityAction.NONE;
    private MobilityFallback mobilityFallback = MobilityFallback.NONE;
    private String mobilityDetail = "idle";
    private String mobilityOwnerTask = "idle";
    private String mobilityReason = "idle";
    private String mobilityLastFailure = "";
    private BlockPos mobilityAnchor;
    private BlockPos mobilityGoal;
    private BlockPos mobilityTarget;
    private BlockPos mobilityTargetStandPos;
    private BlockPos mobilityDigTarget;
    private BlockPos mobilityDigHeadTarget;
    private BlockPos mobilitySupportTarget;
    private BlockPos mobilityProtectedTarget;
    private BlockPos mobilityBlockedBy;
    private int mobilitySupportBlocksUsed;
    private int mobilitySupportPlacePhase;
    private int mobilityStageTicks;
    private int mobilityPlanTicks;
    private int mobilityStuckTicks;
    private int mobilityJumpCooldownTicks;
    private int mobilityVerticalPlaceDelayTicks;
    private int mobilityBlocksBroken;
    private int mobilityReplanCount;
    private int mobilityLockedSupportUntilPlanTick;
    private Vec3 lastMobilityProgressPos;
    private String mobilityBlockedReason = "";
    private String mobilityReplanReason = "";
    private String mobilityLastActionResult = "idle";
    private String mobilityHudText = "";
    private boolean mobilityDirectPathAvailable;
    private BuildingProtectionMode mobilityProtectionMode = BuildingProtectionMode.OFF;
    private BlockPos mobilityLockedSupportPos;
    private int mobilityHudTicks;
    private BlockPos woodTarget;
    private UUID woodDropTargetUuid;
    private WoodTaskStage woodTaskStage = WoodTaskStage.IDLE;
    private int woodGoalCount;
    private int woodCollectedCount;
    private int woodBrokenCount;
    private int woodBreakTicks;
    private int woodStageTicks;
    private int woodSearchRadius;
    private Vec3 lastWoodProgressPos;
    private int woodStuckTicks;
    private String taskDetail = "idle";
    private BlockPos woodSearchAnchor;
    private BlockPos woodExploreTarget;
    private CraftTaskStage craftTaskStage = CraftTaskStage.IDLE;
    private String craftTargetItem;
    private int craftTargetCount;
    private int craftCraftedCount;
    private String craftLastResult;
    private final List<String> craftMissingItems = new ArrayList<>();
    private boolean craftResumeAfterWood;
    private int craftRequestedWoodCount;
    private BlockPos stoneTarget;
    private UUID stoneDropTargetUuid;
    private StoneTaskStage stoneTaskStage = StoneTaskStage.IDLE;
    private int stoneGoalCount;
    private int stoneCollectedCount;
    private int stoneBrokenCount;
    private int stoneBreakTicks;
    private int stoneStageTicks;
    private int stoneSearchRadius;
    private Vec3 lastStoneProgressPos;
    private int stoneStuckTicks;
    private BlockPos stoneSearchAnchor;
    private BlockPos stoneExploreTarget;

    private SynaController() {
    }

    public static SynaController get() {
        return INSTANCE;
    }

    public void tick() {
        cleanupInvalidSynaReference();

        AliceEntity syna = getSyna();
        if (syna == null) {
            restoreOriginalSynaPlayer("no_horror_entity");
            resetAllTasks();
            return;
        }

        horrorState.tick(syna);
        syna.setHorrorFxEnabled(horrorFxEnabled);
        if (horrorState.isActive()) {
            keepOriginalSynaPlayerHidden();
        } else {
            restoreOriginalSynaPlayer("horror_inactive");
            syna.setCustomName(null);
            syna.setCustomNameVisible(false);
            if (syna.getHorrorStage() != 0) {
                syna.setHorrorState(0, 0, "", "");
            }
        }
        if (horrorState.isHunting()) {
            return;
        }

        if (tickGlobalPathingResourceSubtask(syna)) {
            return;
        }

        if (isMobilityTaskActive()) {
            if ("follow".equals(mobilityOwnerTask)) {
                tickFollowMobility(syna);
                return;
            }
            if ("go_to".equals(mobilityOwnerTask)) {
                tickGoToMobility(syna);
                return;
            }
            if ("escape_to_anchor".equals(mobilityOwnerTask)) {
                tickEscapeToAnchor(syna);
                return;
            }
            if ("collect_stone".equals(mobilityOwnerTask)) {
                tickCollectStoneMobility(syna);
                return;
            }
            if ("collect_wood".equals(mobilityOwnerTask)) {
                tickCollectWoodMobility(syna);
                return;
            }
        }

        if ("collect_wood".equals(currentTask)) {
            tickCollectWood(syna);
            return;
        }

        if ("collect_stone".equals(currentTask)) {
            tickCollectStone(syna);
            return;
        }

        if ("craft_item".equals(currentTask)) {
            tickCraftItem(syna);
            return;
        }

        if (followPlayerUuid != null) {
            ServerPlayer player = getPlayer(followPlayerUuid);
            if (player != null) {
                beginFollowMobility(syna, player, "follow_refresh");
                return;
            }
            followPlayerUuid = null;
            clearMobilityTask(false);
            currentTask = "idle";
            taskDetail = "idle";
        }

        if (moveTarget != null) {
            if (syna.blockPosition().closerThan(moveTarget, 2.0D)) {
                syna.getNavigation().stop();
                moveTarget = null;
                currentTask = "idle";
                taskDetail = "idle";
                BridgeState.get().setLastEvent("syna_arrived");
            } else {
                syna.getNavigation().moveTo(moveTarget.getX() + 0.5D, moveTarget.getY(), moveTarget.getZ() + 0.5D, 1.05D);
                currentTask = "go_to";
                taskDetail = "moving:" + formatPos(moveTarget);
            }
        }
    }

    public void tickMobilityDebugHud() {
        mobilityHudTicks++;
        if (mobilityHudTicks < MOBILITY_HUD_INTERVAL_TICKS) {
            return;
        }
        mobilityHudTicks = 0;

        ServerPlayer player = BridgeState.get().getBoundPlayer();
        if (player == null) {
            return;
        }

        mobilityHudText = buildMobilityHudText();
        if (mobilityHudText == null || mobilityHudText.isBlank()) {
            return;
        }
        player.displayClientMessage(Component.literal(mobilityHudText), true);
    }

    private String buildMobilityHudText() {
        if (!isMobilityTaskActive()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Syna ").append(mobilityOwnerTask);
        sb.append(" | ").append(mobilityPlannerStage.name().toLowerCase());
        sb.append("/").append(mobilityAction.name().toLowerCase());
        if (mobilityFallback != MobilityFallback.NONE) {
            sb.append(" -> ").append(mobilityFallback.name().toLowerCase());
        }
        double distance = getMobilityDistanceToGoal();
        if (distance >= 0.0D) {
            sb.append(" | d=").append(String.format(java.util.Locale.ROOT, "%.1f", distance));
        }
        if (mobilityStuckTicks >= MOBILITY_STUCK_WARN_TICKS) {
            sb.append(" | stuck=").append(mobilityStuckTicks);
        }
        String blocked = mobilityBlockedReason == null ? "" : mobilityBlockedReason;
        if (!blocked.isBlank()) {
            sb.append(" | block=").append(blocked);
        }
        int support = getMobilityAvailableSupportBlocks();
        if (mobilityAction == MobilityAction.PLACE_SUPPORT || mobilityFallback == MobilityFallback.SUPPORT_BLOCK || support <= 0) {
            sb.append(" | sup=").append(support);
        }
        return sb.toString();
    }

    private void tickGoToMobility(AliceEntity syna) {
        if (moveTarget == null) {
            failMobilityTask("go_to_failed:no_target");
            clearMobilityTask(false);
            taskDetail = "go_to_failed:no_target";
            return;
        }

        mobilityGoal = moveTarget.immutable();
        mobilityAnchor = moveTarget.immutable();
        mobilityReason = "tracking_goal:" + formatPos(moveTarget);

        MobilityTickResult result = tickMobilityPlan(syna);
        if (result == MobilityTickResult.COMPLETED) {
            BlockPos arrived = moveTarget.immutable();
            moveTarget = null;
            finishMobilityTask("syna_arrived");
            clearMobilityTask(false);
            taskDetail = "arrived:" + formatPos(arrived);
        } else if (result == MobilityTickResult.FAILED) {
            String failure = mobilityLastFailure == null || mobilityLastFailure.isBlank()
                    ? "go_to_failed:planner_failed"
                    : mobilityLastFailure;
            failMobilityTask(failure);
            clearMobilityTask(false);
            taskDetail = failure;
        }
    }

    private void tickCollectStoneMobility(AliceEntity syna) {
        ServerLevel level = (ServerLevel) syna.level();
        if (!isValidStoneTarget(level, stoneTarget)) {
            clearMobilityTask(false);
            stoneTaskStage = StoneTaskStage.SEARCHING;
            currentTask = "collect_stone";
            taskDetail = "stone_target_lost_researching";
            stoneStageTicks = 0;
            BridgeState.get().setLastEvent("collect_stone_target_lost:" + formatPos(stoneTarget));
            return;
        }

        mobilityGoal = stoneTarget.immutable();
        mobilityAnchor = stoneTarget.immutable();
        mobilityReason = "approaching_stone:" + formatPos(stoneTarget);

        if (isWithinStoneBreakRange(syna, stoneTarget)) {
            syna.getNavigation().stop();
            clearMobilityTask(false);
            stoneTaskStage = StoneTaskStage.ALIGNING;
            currentTask = "collect_stone";
            taskDetail = "aligning_to_stone:" + formatPos(stoneTarget);
            stoneStageTicks = 0;
            return;
        }

        MobilityTickResult result = tickMobilityPlan(syna);
        if (result == MobilityTickResult.COMPLETED) {
            clearMobilityTask(false);
            stoneTaskStage = StoneTaskStage.ALIGNING;
            currentTask = "collect_stone";
            taskDetail = "aligning_to_stone:" + formatPos(stoneTarget);
            stoneStageTicks = 0;
            BridgeState.get().setLastEvent("collect_stone_in_range:" + formatPos(stoneTarget));
        } else if (result == MobilityTickResult.FAILED) {
            String failure = mobilityLastFailure == null || mobilityLastFailure.isBlank()
                    ? "collect_stone_failed:planner_failed"
                    : toCollectStoneFailure(mobilityLastFailure);
            clearMobilityTask(false);
            stoneTaskStage = StoneTaskStage.SEARCHING;
            currentTask = "collect_stone";
            taskDetail = failure + " -> stone_path_retry_searching";
            stoneStageTicks = 0;
            BridgeState.get().addDebug("stone_mobility_failed target=" + formatPos(stoneTarget)
                    + ",failure=" + failure);
            BridgeState.get().setLastEvent(failure);
        }
    }

    private void tickCollectWoodMobility(AliceEntity syna) {
        ServerLevel level = (ServerLevel) syna.level();
        if (!isValidLog(level, woodTarget)) {
            clearMobilityTask(false);
            woodTaskStage = WoodTaskStage.SEARCHING;
            currentTask = "collect_wood";
            taskDetail = "wood_target_lost_researching";
            woodStageTicks = 0;
            BridgeState.get().setLastEvent("collect_wood_target_lost:" + formatPos(woodTarget));
            return;
        }

        mobilityGoal = woodTarget.immutable();
        mobilityAnchor = woodTarget.immutable();
        mobilityReason = "approaching_log:" + formatPos(woodTarget);

        if (isWithinBreakRange(syna, woodTarget)) {
            syna.getNavigation().stop();
            clearMobilityTask(false);
            woodTaskStage = WoodTaskStage.ALIGNING;
            currentTask = "collect_wood";
            taskDetail = "aligning_to_log:" + formatPos(woodTarget);
            woodStageTicks = 0;
            BridgeState.get().setLastEvent("collect_wood_in_range:" + formatPos(woodTarget));
            return;
        }

        MobilityTickResult result = tickMobilityPlan(syna);
        if (result == MobilityTickResult.COMPLETED) {
            clearMobilityTask(false);
            woodTaskStage = WoodTaskStage.ALIGNING;
            currentTask = "collect_wood";
            taskDetail = "aligning_to_log:" + formatPos(woodTarget);
            woodStageTicks = 0;
            BridgeState.get().setLastEvent("collect_wood_in_range:" + formatPos(woodTarget));
        } else if (result == MobilityTickResult.FAILED) {
            String failure = mobilityLastFailure == null || mobilityLastFailure.isBlank()
                    ? "collect_wood_failed:planner_failed"
                    : toCollectWoodFailure(mobilityLastFailure);
            clearMobilityTask(false);
            woodTaskStage = WoodTaskStage.SEARCHING;
            currentTask = "collect_wood";
            taskDetail = failure + " -> wood_path_retry_searching";
            woodStageTicks = 0;
            BridgeState.get().addDebug("wood_mobility_failed target=" + formatPos(woodTarget)
                    + ",failure=" + failure);
            BridgeState.get().setLastEvent(failure);
        }
    }

    public void handle(BridgeCommand command) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || command == null || command.type() == null) {
            return;
        }

        switch (command.type()) {
            case "say" -> say(server, command.text());
            case "announce_join" -> BridgeState.get().setLastEvent("announce_join_ignored");
            case "announce_leave" -> announce(server, "[系统] Syna 暂时离开了世界", "announce_leave");
            case "bind_first_player" -> bindFirstPlayer(server);
            case "spawn_syna" -> spawnNearBoundPlayer();
            case "spawn_syna_at" -> spawnAt(command.x(), command.y(), command.z());
            case "despawn_syna" -> despawn();
            case "go_to" -> goTo(command.x(), command.y(), command.z());
            case "path_test" -> pathTest(command.x(), command.y(), command.z());
            case "follow" -> follow(command.player());
            case "collect_wood" -> collectWood(command.count());
            case "collect_stone" -> collectStone(command.count());
            case "craft_item" -> craftItem(command.item(), command.count());
            case "give_item" -> giveItem(command.player(), command.item(), command.count());
            case "escape_to_anchor" -> escapeToAnchor();
            case "horror" -> horror(command.text(), command.player(), command.count(), command.seconds(), command.item(), command.reason(), command.owner());
            case "story" -> SynaStoryDirector.get().command(server, command.text(), command.player(), command.reason());
            case "focus_voice" -> focusVoice(command.player());
            case "stop" -> stop();
            default -> BridgeState.get().setLastEvent("unknown_command:" + command.type());
        }
    }

    public JsonObject executeIntent(JsonObject request) {
        String intent = jsonText(request, "intent");
        String playerName = jsonText(request, "player");
        String requestReason = jsonText(request, "reason");
        JsonObject args = request.has("args") && request.get("args").isJsonObject()
                ? request.getAsJsonObject("args") : new JsonObject();
        String result;
        boolean accepted = true;

        switch (intent == null ? "" : intent) {
            case "none" -> result = "no_action";
            case "manifest" -> {
                ServerPlayer player = findPlayerByName(playerName);
                if (player != null) BridgeState.get().bind(player);
                accepted = spawnNearBoundPlayer();
                result = accepted ? "manifested" : "manifest_failed";
            }
            case "leave" -> {
                despawn();
                result = getSyna() == null ? "left" : "leave_failed";
                accepted = getSyna() == null;
            }
            case "give_item" -> {
                giveItem(playerName, jsonText(args, "item"), jsonInt(args, "count", 1));
                result = BridgeState.get().getLastEvent();
                accepted = result.startsWith("give_item:");
            }
            case "disable_horror_fx" -> {
                setHorrorFxEnabled(false);
                result = "horror_fx_disabled";
            }
            case "enable_horror_fx" -> {
                setHorrorFxEnabled(true);
                result = "horror_fx_enabled";
            }
            case "set_horror_stage" -> {
                String stage = jsonText(args, "stage");
                if (!"explicit_calm_request".equals(requestReason) || !"calm".equalsIgnoreCase(stage)) {
                    accepted = false;
                    result = "director_only_horror_stage";
                    break;
                }
                String action = switch (stage == null ? "" : stage.toLowerCase(java.util.Locale.ROOT)) {
                    case "calm" -> "forgive";
                    case "warning" -> "warn";
                    case "countdown" -> "countdown";
                    case "hunting" -> "hunt";
                    default -> null;
                };
                if (action == null) {
                    accepted = false;
                    result = "unsupported_horror_stage";
                } else if (!"forgive".equals(action) && getSyna() == null) {
                    spawnNearBoundPlayer();
                    horror(action, playerName, null, null, null, "llm_authorized_stage_change", null);
                    result = BridgeState.get().getLastEvent();
                    accepted = getSyna() != null;
                } else {
                    horror(action, playerName, null, null, null, "llm_authorized_stage_change", null);
                    result = BridgeState.get().getLastEvent();
                }
            }
            case "start_game" -> {
                String kind = jsonText(args, "kind");
                if (!"explicit_game_request".equals(requestReason)) {
                    accepted = false;
                    result = "explicit_game_request_required";
                    break;
                }
                if (getSyna() == null) spawnNearBoundPlayer();
                if ("riddle".equals(kind)) {
                    horror("challenge_block", playerName, 1, 120, "minecraft:coal", "我藏在黑暗里，也把火焰留给你。把我丢给 Syna。", null);
                } else if ("hunt".equals(kind)) {
                    horror("challenge_kill", playerName, 3, 180, "minecraft:zombie", "猎杀 3 只僵尸。", null);
                } else {
                    accepted = false;
                }
                result = accepted ? BridgeState.get().getLastEvent() : "unsupported_game";
            }
            case "light_hit" -> {
                accepted = false;
                result = "director_only_light_hit";
            }
            case "prove_presence" -> {
                if (!"explicit_power_dare".equals(requestReason)) {
                    accepted = false;
                    result = "explicit_power_dare_required";
                    break;
                }
                JsonObject proofReceipt = SynaPresenceProofDirector.execute(findPlayerByName(playerName));
                proofReceipt.addProperty("intent", "prove_presence");
                BridgeState.get().setLastAction("prove_presence:"
                        + proofReceipt.get("result").getAsString()
                        + ":accepted=" + proofReceipt.get("accepted").getAsBoolean());
                return proofReceipt;
            }
            case "schedule_dangerous_silence" -> {
                if (!"repeated_true_name_probe".equals(requestReason)) {
                    accepted = false;
                    result = "repeated_true_name_probe_required";
                    break;
                }
                JsonObject silenceReceipt = SynaDangerousSilenceDirector.get()
                        .schedule(findPlayerByName(playerName));
                silenceReceipt.addProperty("intent", "schedule_dangerous_silence");
                BridgeState.get().setLastAction("schedule_dangerous_silence:"
                        + silenceReceipt.get("result").getAsString()
                        + ":accepted=" + silenceReceipt.get("accepted").getAsBoolean());
                return silenceReceipt;
            }
            case "record_identity_disclosure" -> {
                if (!"validated_identity_reply".equals(requestReason)) {
                    accepted = false;
                    result = "validated_identity_reply_required";
                    break;
                }
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                JsonObject loreReceipt = SynaIdentityLoreDirector.record(server,
                        jsonText(args, "topic"), jsonInt(args, "version", 0));
                loreReceipt.addProperty("intent", "record_identity_disclosure");
                BridgeState.get().setLastAction("record_identity_disclosure:"
                        + loreReceipt.get("result").getAsString()
                        + ":accepted=" + loreReceipt.get("accepted").getAsBoolean());
                return loreReceipt;
            }
            case "record_first_contact" -> {
                if (!"validated_first_contact_reply".equals(requestReason)) {
                    accepted = false;
                    result = "validated_first_contact_reply_required";
                    break;
                }
                JsonObject firstContactReceipt = SynaFirstContactDirector.get()
                        .recordCompleted(findPlayerByName(playerName));
                firstContactReceipt.addProperty("intent", "record_first_contact");
                return firstContactReceipt;
            }
            default -> {
                accepted = false;
                result = "unsupported_intent";
            }
        }

        JsonObject receipt = new JsonObject();
        receipt.addProperty("accepted", accepted);
        receipt.addProperty("completed", accepted);
        receipt.addProperty("result", result);
        receipt.addProperty("intent", intent == null ? "" : intent);
        BridgeState.get().setLastAction((intent == null ? "" : intent) + ":" + result
                + ":accepted=" + accepted);
        return receipt;
    }

    private static String jsonText(JsonObject object, String key) {
        if (object == null) return null;
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int jsonInt(JsonObject object, String key, int fallback) {
        try {
            JsonElement value = object == null ? null : object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public AliceEntity getSyna() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || synaUuid == null) {
            return null;
        }

        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(synaUuid);
            if (entity instanceof AliceEntity alice && entity.isAlive()) {
                return alice;
            }
        }
        return null;
    }

    private void giveItem(String playerName, String itemName, Integer requestedCount) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer player = findPlayerByName(playerName);
        if (player == null && server != null && !server.getPlayerList().getPlayers().isEmpty()) {
            player = server.getPlayerList().getPlayers().get(0);
        }
        if (player == null || itemName == null) {
            BridgeState.get().setLastEvent("give_item_failed:invalid_request");
            return;
        }
        String normalizedId = itemName.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalizedId.contains(":")) normalizedId = "minecraft:" + normalizedId;
        ResourceLocation itemId = ResourceLocation.tryParse(normalizedId);
        Item item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR || isForbiddenGift(itemId)) {
            BridgeState.get().setLastEvent("give_item_failed:not_allowed:" + safeEventToken(itemName));
            return;
        }
        int max = Math.max(1, Math.min(16, item.getMaxStackSize()));
        int count = Math.max(1, Math.min(max, requestedCount == null ? 1 : requestedCount));
        BridgeState.get().bind(player);
        spawnNearBoundPlayer();
        AliceEntity syna = getSyna();
        if (syna == null || syna.level() != player.level()) {
            BridgeState.get().setLastEvent("give_item_failed:manifest_failed");
            return;
        }
        double x = syna.getX();
        double y = syna.getY() + 0.5D;
        double z = syna.getZ();
        ItemEntity drop = new ItemEntity(player.serverLevel(), x, y, z, new ItemStack(item, count));
        drop.getPersistentData().putBoolean("SynaGift", true);
        drop.getPersistentData().putUUID("SynaGiftTarget", player.getUUID());
        drop.setPickUpDelay(10);
        player.serverLevel().addFreshEntity(drop);
        SynaManifestationDirector.get().onManifested("requested_gift", true);
        SynaStoryData data = SynaStoryData.get(player.server);
        data.dependency = Math.min(100, data.dependency + 4);
        data.setDirty();
        BridgeState.get().setLastEvent("give_item:" + itemId + ":" + count);
    }

    private boolean isForbiddenGift(ResourceLocation itemId) {
        if (itemId == null) return true;
        String id = itemId.toString();
        String path = itemId.getPath();
        return FORBIDDEN_GIFT_IDS.contains(id)
                || path.endsWith("_spawn_egg")
                || path.contains("command_block")
                || path.contains("structure_block")
                || path.contains("debug_stick");
    }

    public boolean isSyna(Entity entity) {
        return entity != null && synaUuid != null && synaUuid.equals(entity.getUUID());
    }

    public String getCurrentTask() {
        return currentTask;
    }

    public String getTaskDetail() {
        return taskDetail;
    }

    public int getHorrorAnger() {
        return horrorState.getAnger();
    }

    public String getHorrorStage() {
        return horrorState.getStageName();
    }

    public String getHorrorForm() {
        return horrorState.getFormName();
    }

    public String getHorrorTargetName() {
        return horrorState.getTargetName();
    }

    public String getHorrorTargetKind() {
        return horrorState.getTargetKind();
    }

    public int getHorrorCountdownTicks() {
        return horrorState.getCountdownTicks();
    }

    
    public String getHorrorAngerKey() {
        return horrorState.getAngerKey();
    }

    public boolean isHorrorAwaitingConfession() {
        return horrorState.isAwaitingConfession();
    }

    public String getHorrorChallengeKind() {
        return horrorState.getChallengeKind();
    }

    public String getHorrorChallengeTarget() {
        return horrorState.getChallengeTarget();
    }

    public String getHorrorChallengeClue() {
        return horrorState.getChallengeClue();
    }

    public int getHorrorChallengeRequired() {
        return horrorState.getChallengeRequired();
    }

    public int getHorrorChallengeProgress() {
        return horrorState.getChallengeProgress();
    }

    public int getHorrorChallengeTicks() {
        return horrorState.getChallengeTicks();
    }
    public String getHorrorBeat() {
        return horrorState.getBeat();
    }

    public int getHorrorOmenLevel() {
        return horrorState.getOmenLevel();
    }

    public long getHorrorEpisodeId() {
        return horrorState.getEpisodeId();
    }

    public String getHorrorLastOutcome() {
        return horrorState.getLastOutcome();
    }

    public String getWoodTaskStage() {
        return woodTaskStage.name().toLowerCase();
    }

    public String getMobilityMode() {
        return mobilityMode.name().toLowerCase();
    }

    public String getMobilityPlanType() {
        return mobilityPlanType.name().toLowerCase();
    }

    public String getMobilityPlannerStage() {
        return mobilityPlannerStage.name().toLowerCase();
    }

    public String getMobilityAction() {
        return mobilityAction.name().toLowerCase();
    }

    public String getMobilityFallback() {
        return mobilityFallback.name().toLowerCase();
    }

    public String getMobilityDetail() {
        return mobilityDetail;
    }

    public String getMobilityOwnerTask() {
        return mobilityOwnerTask;
    }

    public String getMobilityReason() {
        return mobilityReason;
    }

    public String getMobilityLastFailure() {
        return mobilityLastFailure;
    }

    public BlockPos getMobilityAnchor() {
        return mobilityAnchor;
    }

    public BlockPos getMobilityGoal() {
        return mobilityGoal;
    }

    public BlockPos getMobilityTarget() {
        return mobilityTarget;
    }

    public BlockPos getMobilityTargetStandPos() {
        return mobilityTargetStandPos;
    }

    public BlockPos getMobilityDigTarget() {
        return mobilityDigTarget;
    }

    public BlockPos getMobilityDigHeadTarget() {
        return mobilityDigHeadTarget;
    }

    public BlockPos getMobilitySupportTarget() {
        return mobilitySupportTarget;
    }

    public BlockPos getMobilityProtectedTarget() {
        return mobilityProtectedTarget;
    }

    public BlockPos getMobilityBlockedBy() {
        return mobilityBlockedBy;
    }

    public int getMobilitySupportBlocksUsed() {
        return mobilitySupportBlocksUsed;
    }

    public int getMobilityStageTicks() {
        return mobilityStageTicks;
    }

    public int getMobilityPlanTicks() {
        return mobilityPlanTicks;
    }

    public int getMobilityStuckTicks() {
        return mobilityStuckTicks;
    }

    public int getMobilityBlocksBroken() {
        return mobilityBlocksBroken;
    }

    public int getMobilityPlacePhase() {
        return mobilitySupportPlacePhase;
    }

    public int getMobilityReplanCount() {
        return mobilityReplanCount;
    }

    public String getMobilityBlockedReason() {
        return mobilityBlockedReason;
    }

    public String getMobilityReplanReason() {
        return mobilityReplanReason;
    }

    public String getMobilityLastActionResult() {
        return mobilityLastActionResult;
    }

    public String getMobilityHudText() {
        return mobilityHudText == null ? "" : mobilityHudText;
    }

    public String getMobilityStuckLevel() {
        if (!isMobilityTaskActive()) {
            return "idle";
        }
        if (mobilityStuckTicks >= MOBILITY_STAGE_TIMEOUT_TICKS) {
            return "timeout_risk";
        }
        if (mobilityStuckTicks >= MOBILITY_STUCK_WARN_TICKS) {
            return "stuck_warn";
        }
        return "ok";
    }

    public double getMobilityDistanceToGoal() {
        AliceEntity syna = getSyna();
        if (syna == null || mobilityGoal == null) {
            return -1.0D;
        }
        return Math.sqrt(syna.blockPosition().distSqr(mobilityGoal));
    }

    public boolean isMobilityDirectPathAvailable() {
        return mobilityDirectPathAvailable;
    }

    public String getMobilityProtectionMode() {
        return mobilityProtectionMode.name().toLowerCase();
    }

    public int getMobilityAvailableSupportBlocks() {
        AliceEntity syna = getSyna();
        return syna == null ? 0 : countSupportBlocks(syna.getInventory());
    }

    public boolean isMobilityTaskActive() {
        return mobilityPlanType != MobilityPlanType.NONE;
    }

    public MobilitySnapshot getGlobalPathingMobilitySnapshot() {
        return globalPathingMobility.snapshot();
    }

    private void pathTest(Double x, Double y, Double z) {
        AliceEntity syna = getSyna();
        if (syna == null || x == null || y == null || z == null) {
            BridgeState.get().setLastEvent("path_test_failed:invalid_request");
            return;
        }
        BlockPos goal = BlockPos.containing(x, y, z);
        globalPathingMobility.submit(MobilityRequest.goTo(goal, "path_test", MovementPermission.DEFAULT_SURVIVAL));
        com.syna.bridge.mobility.MobilityTickResult result = globalPathingMobility.tick(syna);
        if (result.type() == MobilityTickResultType.NEED_RESOURCE) {
            resourceSubtaskManager.startSubtask(syna, result.resourceRequirement());
        }
        BridgeState.get().setLastEvent("path_test:" + result.type().name().toLowerCase() + ":" + formatPos(goal));
        BridgeState.get().addDebug("path_test goal=" + formatPos(goal)
                + ",status=" + globalPathingMobility.snapshot().status()
                + ",waypoints=" + globalPathingMobility.snapshot().waypointCount());
    }

    private boolean tickGlobalPathingResourceSubtask(AliceEntity syna) {
        if (!resourceSubtaskManager.isActive()) {
            return false;
        }
        ResourceSubtaskManager.TickResult result = resourceSubtaskManager.tick(syna);
        currentTask = "collect_support_blocks";
        taskDetail = result.detail();
        if (result.type() == ResourceSubtaskManager.Type.COMPLETED) {
            currentTask = "path_test";
            globalPathingMobility.resumeAfterResource("support_ready");
            BridgeState.get().setLastEvent("path_test_resume_after_support");
            return false;
        }
        if (result.type() == ResourceSubtaskManager.Type.FAILED) {
            globalPathingMobility.cancel("resource_subtask_failed");
            currentTask = "idle";
            BridgeState.get().setLastEvent("path_test_failed:resource_subtask_failed");
            return false;
        }
        return true;
    }

    public String getMobilityTargetPlayerUuid() {
        return mobilityTargetPlayerUuid == null ? "" : mobilityTargetPlayerUuid.toString();
    }

    public int getWoodGoalCount() {
        return woodGoalCount;
    }

    public int getWoodCollectedCount() {
        return woodCollectedCount;
    }

    public int getWoodBrokenCount() {
        return woodBrokenCount;
    }

    public int getWoodBreakTicks() {
        return woodBreakTicks;
    }

    public BlockPos getWoodTarget() {
        return woodTarget;
    }

    public UUID getWoodDropTargetUuid() {
        return woodDropTargetUuid;
    }

    public boolean isWoodTaskActive() {
        return "collect_wood".equals(currentTask) || craftResumeAfterWood;
    }

    public String getStoneTaskStage() {
        return stoneTaskStage.name().toLowerCase();
    }

    public int getStoneGoalCount() {
        return stoneGoalCount;
    }

    public int getStoneCollectedCount() {
        return stoneCollectedCount;
    }

    public int getStoneBrokenCount() {
        return stoneBrokenCount;
    }

    public int getStoneBreakTicks() {
        return stoneBreakTicks;
    }

    public BlockPos getStoneTarget() {
        return stoneTarget;
    }

    public UUID getStoneDropTargetUuid() {
        return stoneDropTargetUuid;
    }

    public boolean isStoneTaskActive() {
        return "collect_stone".equals(currentTask);
    }

    public String getCraftTaskStage() {
        return craftTaskStage.name().toLowerCase();
    }

    public String getCraftTargetItem() {
        return craftTargetItem;
    }

    public int getCraftTargetCount() {
        return craftTargetCount;
    }

    public int getCraftCraftedCount() {
        return craftCraftedCount;
    }

    public String getCraftLastResult() {
        return craftLastResult;
    }

    public List<String> getCraftMissingItems() {
        return List.copyOf(craftMissingItems);
    }

    public boolean isCraftTaskActive() {
        return "craft_item".equals(currentTask)
                || craftTaskStage == CraftTaskStage.CHECKING
                || craftTaskStage == CraftTaskStage.EXECUTING
                || craftResumeAfterWood;
    }

    public boolean isCraftWaitingForWood() {
        return craftResumeAfterWood;
    }

    public int getCraftRequestedWoodCount() {
        return craftRequestedWoodCount;
    }

    private void spawnAt(Double x, Double y, Double z) {
        if (x == null || y == null || z == null) {
            BridgeState.get().setLastEvent("spawn_at_failed:invalid_pos");
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            BridgeState.get().setLastEvent("spawn_at_failed:no_server");
            return;
        }
        ServerPlayer player = BridgeState.get().getBoundPlayer();
        ServerLevel level = player == null ? server.overworld() : player.serverLevel();
        cleanupGhostSynas(level, true);
        cleanupInvalidSynaReference();
        AliceEntity syna = getSyna();
        if (syna == null) {
            syna = ModEntities.ALICE.get().create(level);
            if (syna == null) {
                BridgeState.get().setLastEvent("spawn_at_failed:create_entity_failed");
                return;
            }
            syna.setCustomName(null);
            syna.setCustomNameVisible(false);
            syna.setPersistenceRequired();
            syna.setNoAi(false);
            syna.moveTo(x, y, z, 0.0F, 0.0F);
            level.addFreshEntity(syna);
            synaUuid = syna.getUUID();
        } else {
            syna.teleportTo(x, y, z);
        }
        currentTask = "idle";
        taskDetail = "spawn_at:" + formatPos(BlockPos.containing(x, y, z));
        BridgeState.get().setLastEvent("syna_spawned_at:" + formatPos(BlockPos.containing(x, y, z)));
    }
    private boolean spawnNearBoundPlayer() {
        ServerPlayer player = BridgeState.get().getBoundPlayer();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (player == null && server != null && !server.getPlayerList().getPlayers().isEmpty()) {
            player = server.getPlayerList().getPlayers().get(0);
        }
        if (player == null) {
            BridgeState.get().setLastEvent("spawn_failed:no_player");
            return false;
        }

        ServerLevel ritualLevel = player.serverLevel();
        Vec3 ritualPos = findSummonRitualPosition(ritualLevel, player);
        if (ritualPos == null) {
            BridgeState.get().setLastEvent("spawn_failed:no_safe_nearby_space");
            return false;
        }
        cleanupGhostSynas(player.serverLevel(), true);
        cleanupInvalidSynaReference();

        AliceEntity existing = getSyna();
        if (existing != null && existing.level() != ritualLevel) {
            existing.discard();
            synaUuid = null;
            existing = null;
        }
        if (existing != null) {
            existing.teleportTo(ritualPos.x, ritualPos.y, ritualPos.z);
            existing.setCustomName(null);
            existing.setCustomNameVisible(false);
            existing.getNavigation().stop();
            existing.setHorrorFxEnabled(horrorFxEnabled);
            SynaManifestationDirector.get().onManifested("summon", false);
            SynaManifestationDirector.get().playArrival(existing);
            BridgeState.get().setLastEvent("syna_teleported_to_player");
            return true;
        }

        ServerLevel level = player.serverLevel();
        AliceEntity syna = ModEntities.ALICE.get().create(level);
        if (syna == null) {
            BridgeState.get().setLastEvent("spawn_failed:create_entity_failed");
            return false;
        }
        syna.setCustomName(null);
        syna.setCustomNameVisible(false);
        syna.setPersistenceRequired();
        syna.setNoAi(false);
        syna.setHorrorFxEnabled(horrorFxEnabled);
        syna.moveTo(ritualPos.x, ritualPos.y, ritualPos.z, player.getYRot(), 0.0F);
        level.addFreshEntity(syna);
        synaUuid = syna.getUUID();
        syna.getNavigation().stop();
        SynaManifestationDirector.get().onManifested("summon", false);
        SynaManifestationDirector.get().playArrival(syna);
        currentTask = "idle";
        BridgeState.get().setLastEvent("syna_manifested_silently");
        return true;
    }

    private Vec3 findSummonRitualPosition(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        horizontal = horizontal.normalize();
        Vec3 side = new Vec3(-horizontal.z, 0.0D, horizontal.x);
        Vec3[] candidates = new Vec3[] {
                player.position().subtract(horizontal.scale(3.0D)),
                player.position().subtract(horizontal.scale(3.0D)).add(side.scale(1.5D)),
                player.position().subtract(horizontal.scale(3.0D)).subtract(side.scale(1.5D)),
                player.position().add(side.scale(2.5D)),
                player.position().subtract(side.scale(2.5D)),
                player.position().add(horizontal.scale(2.5D))
        };
        for (Vec3 candidate : candidates) {
            BlockPos base = BlockPos.containing(candidate);
            for (int yOffset : new int[]{0, 1, -1, 2, -2}) {
                BlockPos pos = base.offset(0, yOffset, 0);
                if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).isSolid()) {
                    return Vec3.atBottomCenterOf(pos);
                }
            }
        }
        BlockPos origin = player.blockPosition();
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy : new int[]{0, 1, -1, 2, -2}) {
                        BlockPos pos = origin.offset(dx, dy, dz);
                        if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                                && level.getBlockState(pos.below()).isSolid()) {
                            return Vec3.atBottomCenterOf(pos);
                        }
                    }
                }
            }
        }
        return null;
    }

    public void summonFromChat(ServerPlayer player) {
        if (player != null) {
            BridgeState.get().bind(player);
        }
        spawnNearBoundPlayer();
    }

    public void summonSilently(ServerPlayer player, String reason, boolean shortVisit) {
        if (player != null) BridgeState.get().bind(player);
        spawnNearBoundPlayer();
        AliceEntity syna = getSyna();
        if (syna != null) {
            syna.setCustomName(null);
            syna.setCustomNameVisible(false);
            syna.getNavigation().stop();
            SynaManifestationDirector.get().onManifested(reason, shortVisit);
        }
    }

    public AliceEntity summonSilentlyAt(ServerPlayer player, BlockPos pos, String reason, boolean shortVisit) {
        if (player == null || pos == null) return null;
        BridgeState.get().bind(player);
        cleanupGhostSynas(player.serverLevel(), true);
        cleanupInvalidSynaReference();
        AliceEntity syna = getSyna();
        if (syna == null) {
            syna = ModEntities.ALICE.get().create(player.serverLevel());
            if (syna == null) return null;
            syna.setPersistenceRequired();
            syna.setNoAi(false);
            syna.setHorrorFxEnabled(horrorFxEnabled);
            syna.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, player.getYRot() + 180.0F, 0.0F);
            player.serverLevel().addFreshEntity(syna);
            synaUuid = syna.getUUID();
        } else {
            syna.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        }
        syna.setCustomName(null);
        syna.setCustomNameVisible(false);
        syna.getNavigation().stop();
        currentTask = "idle";
        onSilentManifest(reason, shortVisit);
        SynaManifestationDirector.get().playArrival(syna);
        return syna;
    }

    private void onSilentManifest(String reason, boolean shortVisit) {
        SynaManifestationDirector.get().onManifested(reason, shortVisit);
        BridgeState.get().setLastEvent("syna_manifested_silently:" + safeEventToken(reason));
    }

    public void clearSessionPresence(MinecraftServer server) {
        restoreOriginalSynaPlayer("session_login_cleanup");
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) cleanupGhostSynas(level, false);
        }
        synaUuid = null;
        resetAllTasks();
        BridgeState.get().setLastEvent("session_presence_cleared");
        BridgeState.get().setLastAction("session_login:body_absent");
    }

    private void despawn() {
        restoreOriginalSynaPlayer("despawn");
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        AliceEntity syna = getSyna();
        if (syna != null) {
            SynaManifestationDirector.get().playDeparture(syna);
            syna.discard();
        }
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                cleanupGhostSynas(level, false);
            }
        }
        synaUuid = null;
        resetAllTasks();
        BridgeState.get().setLastEvent("syna_despawned");
    }

    public void setHorrorFxEnabled(boolean enabled) {
        horrorFxEnabled = enabled;
        AliceEntity syna = getSyna();
        if (syna != null) syna.setHorrorFxEnabled(enabled);
        BridgeState.get().setLastEvent("horror_fx:" + (enabled ? "enabled" : "disabled"));
    }

    public boolean isHorrorFxEnabled() {
        return horrorFxEnabled;
    }

    public void lightHit(String playerName) {
        ServerPlayer player = findPlayerByName(playerName);
        if (player == null) player = BridgeState.get().getBoundPlayer();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (player == null || server == null) {
            BridgeState.get().setLastEvent("manifest_light_hit_failed:no_player");
            return;
        }
        SynaStoryData data = SynaStoryData.get(server);
        if (!data.episodeEvents.add("manifest:light_hit")) {
            BridgeState.get().setLastEvent("manifest_light_hit_failed:episode_repeat");
            return;
        }
        data.setDirty();
        player.hurt(player.damageSources().magic(), 1.0F);
        BridgeState.get().setLastEvent("manifest_light_hit:" + player.getGameProfile().getName());
    }

    private void goTo(Double x, Double y, Double z) {
        AliceEntity syna = getSyna();
        if (syna == null || x == null || y == null || z == null) {
            BridgeState.get().setLastEvent("go_to_failed");
            return;
        }
        moveTarget = BlockPos.containing(x, y, z);
        followPlayerUuid = null;
        clearMobilityTask(false);
        clearWoodTask(false);
        clearStoneTask(false);
        clearCraftTask(false);
        beginGoToMobility(syna, moveTarget, "go_to_command");
        BridgeState.get().setLastEvent("syna_go_to");
    }

    private void follow(String playerName) {
        AliceEntity syna = getSyna();
        if (syna == null) {
            BridgeState.get().setLastEvent("follow_failed:no_syna");
            return;
        }
        ServerPlayer player = findPlayerByName(playerName);
        if (player == null) {
            BridgeState.get().setLastEvent("follow_failed:no_player");
            return;
        }
        followPlayerUuid = player.getUUID();
        moveTarget = null;
        clearWoodTask(false);
        clearStoneTask(false);
        clearCraftTask(false);
        beginFollowMobility(syna, player, "follow_command");
        BridgeState.get().setLastEvent("syna_follow:" + player.getGameProfile().getName());
    }

    private void collectWood(Integer count) {
        collectWood(count, false);
    }

    private void collectWood(Integer count, boolean preserveCraftState) {
        AliceEntity syna = getSyna();
        if (syna == null) {
            BridgeState.get().setLastEvent("collect_wood_failed:no_syna");
            return;
        }

        moveTarget = null;
        followPlayerUuid = null;
        clearMobilityTask(false);
        clearStoneTask(false);
        if (!preserveCraftState) {
            clearCraftTask(false);
        }
        woodGoalCount = count == null || count <= 0 ? 1 : count;
        woodCollectedCount = 0;
        woodBrokenCount = 0;
        woodBreakTicks = 0;
        woodStageTicks = 0;
        woodSearchRadius = WOOD_SEARCH_RADIUS;
        woodStuckTicks = 0;
        lastWoodProgressPos = null;
        woodTarget = null;
        woodSearchAnchor = syna.blockPosition();
        woodExploreTarget = null;
        woodDropTargetUuid = null;
        woodTaskStage = WoodTaskStage.SEARCHING;
        currentTask = "collect_wood";
        taskDetail = "searching_for_logs";
        syna.setMiningSwing(false);
        syna.getNavigation().stop();
        BridgeState.get().addDebug("collect_wood_start goal=" + woodGoalCount + ",pos=" + formatPos(syna.blockPosition()));
        BridgeState.get().setLastEvent("collect_wood_started:" + woodGoalCount);
        if (preserveCraftState) {
            announceTaskMessage("准备补充材料：先去采集木头 x" + woodGoalCount, false);
        }
    }

    private void collectStone(Integer count) {
        AliceEntity syna = getSyna();
        if (syna == null) {
            BridgeState.get().setLastEvent("collect_stone_failed:no_syna");
            return;
        }

        moveTarget = null;
        followPlayerUuid = null;
        clearMobilityTask(false);
        clearWoodTask(false);
        clearCraftTask(false);
        stoneGoalCount = count == null || count <= 0 ? 1 : count;
        stoneCollectedCount = 0;
        stoneBrokenCount = 0;
        stoneBreakTicks = 0;
        stoneStageTicks = 0;
        stoneSearchRadius = STONE_SEARCH_RADIUS;
        stoneStuckTicks = 0;
        lastStoneProgressPos = null;
        stoneTarget = null;
        stoneSearchAnchor = syna.blockPosition();
        stoneExploreTarget = null;
        stoneDropTargetUuid = null;
        stoneTaskStage = StoneTaskStage.SEARCHING;
        currentTask = "collect_stone";
        taskDetail = "searching_for_stone";
        syna.setMiningSwing(false);
        syna.getNavigation().stop();
        BridgeState.get().addDebug("collect_stone_start goal=" + stoneGoalCount + ",pos=" + formatPos(syna.blockPosition()));
        BridgeState.get().setLastEvent("collect_stone_started:" + stoneGoalCount);
    }

    private void craftItem(String itemId, Integer count) {
        AliceEntity syna = getSyna();
        if (syna == null) {
            BridgeState.get().setLastEvent("craft_item_failed:no_syna");
            return;
        }
        if (itemId == null || itemId.isBlank()) {
            BridgeState.get().setLastEvent("craft_item_failed:no_item");
            return;
        }

        moveTarget = null;
        followPlayerUuid = null;
        clearMobilityTask(false);
        clearWoodTask(false);
        clearStoneTask(false);
        clearCraftTask(true);
        craftTargetItem = itemId.trim().toLowerCase();
        craftTargetCount = count == null || count <= 0 ? 1 : count;
        craftTaskStage = CraftTaskStage.CHECKING;
        currentTask = "craft_item";
        taskDetail = "craft_checking:" + craftTargetItem;
        syna.getNavigation().stop();
        BridgeState.get().addDebug("craft_item_start item=" + craftTargetItem + ",count=" + craftTargetCount);
        BridgeState.get().setLastEvent("craft_item_started:" + craftTargetItem + ":" + craftTargetCount);
    }

    private void horror(String action, String playerName, Integer anger, Integer seconds, String itemName, String reason, String ownerName) {
        AliceEntity syna = getSyna();
        if (syna == null) {
            BridgeState.get().setLastEvent("horror_failed:no_syna");
            return;
        }
        String normalized = action == null ? "status" : action.trim().toLowerCase();
        boolean wasHorrorActive = horrorState.isActive();
        boolean isChallengeAction = isHorrorChallengeAction(normalized);
        if (isHorrorTakeoverAction(normalized)) {
            moveTarget = null;
            followPlayerUuid = null;
            clearMobilityTask(false);
            clearWoodTask(false);
            clearStoneTask(false);
            clearCraftTask(false);
            syna.getNavigation().stop();
            if (syna.level() instanceof ServerLevel level) {
                cleanupGhostSynas(level, true);
            }
            syna.setInvisible(false);
            syna.removeEffect(MobEffects.INVISIBILITY);
            syna.setCustomName(null);
            syna.setCustomNameVisible(false);
            hideOriginalSynaPlayer(ownerName, syna);
            currentTask = "horror_takeover";
            taskDetail = "second_form_takeover:" + (playerName == null || playerName.isBlank() ? "none" : playerName);
        }
        if (isChallengeAction) {
            horrorState.commandChallenge(syna, normalized, playerName, itemName, reason, anger, seconds);
        } else {
            horrorState.command(syna, action, playerName, anger, reason);
        }
        if (!wasHorrorActive && horrorState.isActive()) {
            playHorrorEntranceEffect(syna);
        }
        if (isHorrorRecoverAction(normalized)) {
            recoverFromHorror(syna, "llm_" + normalized);
        }
    }

    private boolean isHorrorTakeoverAction(String action) {
        return "takeover".equals(action) || "transform".equals(action) || "possess".equals(action)
                || "countdown".equals(action) || "hunt".equals(action) || "chase".equals(action)
                || isHorrorChallengeAction(action);
    }

    private boolean isHorrorChallengeAction(String action) {
        return "challenge_block".equals(action) || "trial_block".equals(action) || "riddle_block".equals(action)
                || "challenge_kill".equals(action) || "trial_kill".equals(action) || "hunt_task".equals(action);
    }

    private boolean isHorrorRecoverAction(String action) {
        return "calm".equals(action) || "forgive".equals(action) || "stop".equals(action) || "mercy".equals(action);
    }

    public void sealByTrueName(String playerName) {
        AliceEntity syna = getSyna();
        horrorState.sealByTrueName(syna);
        if (syna != null) recoverFromHorror(syna, "true_name_sealed");
        BridgeState.get().setLastEvent("true_name_sealed:" + safeEventToken(playerName));
    }

    private void recoverFromHorror(AliceEntity syna, String reason) {
        horrorState.restoreWeatherNow(syna);
        restoreOriginalSynaPlayer(reason);
        if (syna != null) {
            syna.getNavigation().stop();
            syna.setTarget(null);
            syna.setHorrorState(0, 0, "");
            syna.discard();
            if (synaUuid != null && synaUuid.equals(syna.getUUID())) {
                synaUuid = null;
            }
        }
    }

    private void focusVoice(String playerName) {
        ServerPlayer player = findPlayerByName(playerName);
        if (player != null) {
            BridgeState.get().bind(player);
            player.displayClientMessage(Component.literal("[Syna] voice focus armed"), true);
            BridgeState.get().setLastEvent("voice_focus:" + player.getGameProfile().getName());
            return;
        }
        BridgeState.get().setLastEvent("voice_focus:" + (playerName == null || playerName.isBlank() ? "unknown" : playerName));
    }

    private void escapeToAnchor() {
        AliceEntity syna = getSyna();
        if (syna == null) {
            BridgeState.get().setLastEvent("escape_to_anchor_failed:no_syna");
            return;
        }

        moveTarget = null;
        followPlayerUuid = null;
        clearWoodTask(false);
        clearStoneTask(false);
        clearCraftTask(false);
        beginEscapeToAnchor(syna, null, "manual_request");
    }

    private void stop() {
        AliceEntity syna = getSyna();
        if (syna != null) {
            PathNavigation navigation = syna.getNavigation();
            navigation.stop();
            syna.setMiningSwing(false);
            clearBreakingAnimation((ServerLevel) syna.level());
        }
        recoverFromHorror(syna, "stop");
        resetAllTasks();
        BridgeState.get().setLastEvent("syna_stop");
    }

    public void onSynaAttacked(AliceEntity syna, DamageSource source, float amount) {
        if (!isSyna(syna)) {
            return;
        }
        syna.getNavigation().stop();
        moveTarget = null;
        followPlayerUuid = null;
        clearMobilityTask(false);
        String attacker = source.getEntity() == null ? "unknown" : source.getEntity().getName().getString();
        String causeName = describeDamageCause(source);
        String sourceKind = describeDamageSourceKind(source);
        horrorState.onAttacked(syna, source, amount);
        taskDetail = "under_attack:" + attacker;
        BridgeState.get().setLastEvent("syna_attacked:" + attacker + ":" + Math.round(amount) + ":mod_entity:" + syna.getName().getString() + ":" + causeName + ":" + sourceKind);
    }

    public void onSynaDied(AliceEntity syna, DamageSource source) {
        if (!isSyna(syna)) {
            return;
        }
        Entity killerEntity = source == null ? null : source.getEntity();
        String killer = killerEntity == null ? "unknown" : killerEntity.getName().getString();
        boolean horrorActive = horrorState.isActive();
        if (horrorActive && killerEntity instanceof ServerPlayer killerPlayer) {
            handleHorrorEntityKilled(syna, killerPlayer);
            return;
        }
        recoverFromHorror(null, "syna_died");
        synaUuid = null;
        resetAllTasks();
        BridgeState.get().setLastEvent("syna_died:" + killer);
    }

    private void handleHorrorEntityKilled(AliceEntity deadSyna, ServerPlayer killerPlayer) {
        String killer = killerPlayer.getGameProfile().getName();
        boolean wasCreative = killerPlayer.gameMode.getGameModeForPlayer() == GameType.CREATIVE;
        if (wasCreative) {
            killerPlayer.setGameMode(GameType.SURVIVAL);
        }
        restoreOriginalSynaPlayer("horror_killed_respawn");
        resetAllTasks();
        ServerLevel level = (ServerLevel) deadSyna.level();
        Vec3 pos = deadSyna.position();
        AliceEntity revived = ModEntities.ALICE.get().create(level);
        if (revived == null) {
            synaUuid = null;
            BridgeState.get().setLastEvent("horror_killed_respawn_failed:" + killer + ":creative=" + wasCreative);
            return;
        }
        revived.setCustomName(null);
        revived.setCustomNameVisible(false);
        revived.setPersistenceRequired();
        revived.setNoAi(false);
        revived.moveTo(pos.x, pos.y, pos.z, deadSyna.getYRot(), deadSyna.getXRot());
        level.addFreshEntity(revived);
        synaUuid = revived.getUUID();
        hideOriginalSynaPlayer(killer, revived);
        horrorState.command(revived, "hunt", killer, 240, "killed_horror_entity");
        playHorrorEntranceEffect(revived);
        BridgeState.get().setLastEvent("horror_killed:" + killer + ":creative=" + wasCreative + ":respawned");
    }


    public void onLivingEntityDeath(Entity entity) {
        AliceEntity syna = getSyna();
        if (horrorState.acceptsKillChallenge(entity)) {
            Entity killer = entity instanceof LivingEntity living ? living.getKillCredit() : null;
            if (horrorState.isChallengeActor(killer)) {
                horrorState.onEntityKilled(syna, entity);
                if (!horrorState.isActive()) {
                    recoverFromHorror(syna, "challenge_completed");
                    resetAllTasks();
                }
            }
        }
        if (!horrorState.isTarget(entity)) {
            return;
        }
        if (syna != null) {
            syna.getNavigation().stop();
            syna.setTarget(null);
            syna.setCustomName(null);
            syna.setCustomNameVisible(false);
        }
        horrorState.onTargetDied(syna, entity);
        recoverFromHorror(syna, "target_died");
        resetAllTasks();
        BridgeState.get().setLastEvent("horror_target_died:" + entity.getName().getString());
    }
    public boolean handleChatCommand(ServerPlayer player, String rawMessage) {
        if (rawMessage == null) {
            return false;
        }

        String message = rawMessage.trim();
        if (message.isEmpty()) {
            return false;
        }

        String normalized = message.startsWith("!") ? message.substring(1).trim() : message;
        if (!normalized.toLowerCase().startsWith("syna")) {
            return false;
        }

        String[] parts = normalized.split("\\s+");
        if (parts.length == 1) {
            summonFromChat(player);
            replyToPlayer(player, "已召唤 Syna。可用：syna follow / syna wood 3 / syna stop / syna goto x y z / syna status");
            return true;
        }

        String action = parts[1].toLowerCase();
        switch (action) {
            case "test" -> {
                handleTestCommand(player, parts);
                return true;
            }
            case "spawn", "summon", "come" -> {
                summonFromChat(player);
                replyToPlayer(player, "Syna 已召唤到你身边。");
                return true;
            }
            case "follow" -> {
                BridgeState.get().bind(player);
                follow(player.getGameProfile().getName());
                replyToPlayer(player, "Syna 开始跟随你。");
                return true;
            }
            case "stop" -> {
                stop();
                replyToPlayer(player, "Syna 已停止当前任务。");
                return true;
            }
            case "status" -> {
                replyToPlayer(player, "name=Alice, task=" + currentTask + ", detail=" + taskDetail + ", horror=" + getHorrorStage() + ", anger=" + getHorrorAnger() + ", target=" + getHorrorTargetName() + ", woodStage=" + getWoodTaskStage() + ", stoneStage=" + getStoneTaskStage() + ", craftStage=" + getCraftTaskStage() + ", mobility=" + getMobilityMode());
                return true;
            }
            case "wood", "collect_wood" -> {
                int count = 1;
                if (parts.length >= 3) {
                    try {
                        count = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                BridgeState.get().bind(player);
                collectWood(count);
                replyToPlayer(player, "Syna 开始伐木，目标数量：" + count);
                return true;
            }
            case "stone", "collect_stone" -> {
                int count = 1;
                if (parts.length >= 3) {
                    try {
                        count = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                BridgeState.get().bind(player);
                collectStone(count);
                replyToPlayer(player, "Syna 开始采石，目标数量：" + count);
                return true;
            }
            case "craft", "craft_item" -> {
                if (parts.length < 3) {
                    replyToPlayer(player, "用法: syna craft <item_id> [count]，例如 syna craft minecraft:oak_planks 4");
                    return true;
                }
                int count = 1;
                if (parts.length >= 4) {
                    try {
                        count = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                BridgeState.get().bind(player);
                craftItem(parts[2], count);
                replyToPlayer(player, "Syna 开始合成：" + parts[2] + " x" + count);
                return true;
            }
            case "goto", "go" -> {
                if (parts.length >= 5) {
                    try {
                        double x = Double.parseDouble(parts[2]);
                        double y = Double.parseDouble(parts[3]);
                        double z = Double.parseDouble(parts[4]);
                        goTo(x, y, z);
                        replyToPlayer(player, "Syna 正在前往: " + parts[2] + ", " + parts[3] + ", " + parts[4]);
                    } catch (NumberFormatException e) {
                        replyToPlayer(player, "坐标格式错误。用法: syna goto <x> <y> <z>");
                    }
                } else {
                    replyToPlayer(player, "用法: syna goto <x> <y> <z>");
                }
                return true;
            }
            case "path_test", "pathtest" -> {
                if (parts.length >= 5) {
                    try {
                        double x = Double.parseDouble(parts[2]);
                        double y = Double.parseDouble(parts[3]);
                        double z = Double.parseDouble(parts[4]);
                        pathTest(x, y, z);
                        replyToPlayer(player, "A* path_test 已计算: " + parts[2] + ", " + parts[3] + ", " + parts[4] + "；查看 /state.syna.mobility.globalPathing。");
                    } catch (NumberFormatException e) {
                        replyToPlayer(player, "坐标格式错误。用法: syna path_test <x> <y> <z>");
                    }
                } else {
                    replyToPlayer(player, "用法: syna path_test <x> <y> <z>");
                }
                return true;
            }
            case "escape", "stairs", "up" -> {
                BridgeState.get().bind(player);
                escapeToAnchor();
                replyToPlayer(player, "Syna 开始执行楼梯上返/脱困。");
                return true;
            }
            case "focus", "listen", "voice" -> {
                focusVoice(player.getGameProfile().getName());
                replyToPlayer(player, "Syna 正在听你说话。");
                return true;
            }
            case "horror" -> {
                String horrorAction = parts.length >= 3 ? parts[2] : "status";
                String horrorTarget = parts.length >= 4 ? parts[3] : player.getGameProfile().getName();
                Integer horrorAnger = parts.length >= 5 ? parseInteger(parts[4]) : null;
                horror(horrorAction, horrorTarget, horrorAnger, null, null, null, null);
                replyToPlayer(player, "horror=" + getHorrorStage() + ", anger=" + getHorrorAnger() + ", target=" + getHorrorTargetName());
                return true;
            }
            case "story" -> {
                String storyAction = parts.length >= 3 ? parts[2] : "status";
                if ("status".equalsIgnoreCase(storyAction)) {
                    replyToPlayer(player, SynaStoryDirector.get().statusLine(player.getServer()));
                } else {
                    boolean accepted = SynaStoryDirector.get().command(player.getServer(), storyAction,
                            player.getGameProfile().getName(), "chat_debug");
                    replyToPlayer(player, (accepted ? "story accepted: " : "story rejected: ")
                            + storyAction + "; " + SynaStoryDirector.get().statusLine(player.getServer()));
                }
                return true;
            }
            case "despawn", "dismiss" -> {
                despawn();
                replyToPlayer(player, "Alice 已消失。旧实体也已清理。");
                return true;
            }
            case "cleanup" -> {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                int removed = 0;
                if (server != null) {
                    for (ServerLevel level : server.getAllLevels()) {
                        removed += cleanupGhostSynas(level, false);
                    }
                }
                cleanupInvalidSynaReference();
                replyToPlayer(player, "已清理旧 Syna/Alice 实体数量: " + removed);
                return true;
            }
            default -> {
                replyToPlayer(player, "未知命令。可用: spawn, follow, wood <n>, stone <n>, craft <item> [n], goto x y z, stop, status, cleanup, despawn");
                return true;
            }
        }
    }

    private void handleTestCommand(ServerPlayer player, String[] parts) {
        ServerPlayer bound = BridgeState.get().getBoundPlayer();
        if (bound != null && !bound.getUUID().equals(player.getUUID())) {
            replyToPlayer(player, "测试权限只属于当前绑定玩家: " + bound.getGameProfile().getName());
            return;
        }
        BridgeState.get().bind(player);
        String feature = parts.length >= 3 ? parts[2].toLowerCase(java.util.Locale.ROOT) : "list";
        if ("list".equals(feature) || "help".equals(feature)) {
            replyToPlayer(player, "测试1: spawn, vanish, gift <item> [count], steps, knock, breath, darkness");
            replyToPlayer(player, "测试2: watcher, stalker, ambush, enforcer, omen, lookbehind, tunnel, hit");
            replyToPlayer(player, "测试3: warning, countdown, hunt, calm, game_block, game_kill, final, boredom <0-100>");
            replyToPlayer(player, "测试4: observe <diamond|debris|emerald|nether|end|dragon|wither|warden>, story <scene>, seal_prepare, intro_reset, fx <on|off>, rules <on|off>, status, cleanup, reset");
            return;
        }
        switch (feature) {
            case "spawn" -> summonFromChat(player);
            case "vanish" -> despawn();
            case "gift" -> {
                String item = parts.length >= 4 ? parts[3] : "minecraft:iron_ingot";
                int count = parts.length >= 5 && parseInteger(parts[4]) != null ? parseInteger(parts[4]) : 1;
                giveItem(player.getGameProfile().getName(), item, Math.max(1, Math.min(16, count)));
            }
            case "steps" -> replyToPlayer(player, SynaBoredomDirector.get().debugLightEvent(player, "phantom_steps"));
            case "knock" -> replyToPlayer(player, SynaBoredomDirector.get().debugLightEvent(player, "distant_knock"));
            case "breath" -> replyToPlayer(player, SynaBoredomDirector.get().debugLightEvent(player, "cave_breath"));
            case "darkness" -> replyToPlayer(player, SynaBoredomDirector.get().debugLightEvent(player, "brief_darkness"));
            case "watcher", "stalker", "ambush", "enforcer" ->
                    replyToPlayer(player, SynaBoredomDirector.get().debugEntityEvent(player, feature));
            case "lookbehind" -> replyToPlayer(player, "accepted=" + SynaManifestationDirector.get().beginLookBehindPrank(player));
            case "omen" -> {
                SynaOpeningOmenDirector.get().debugTrigger(player);
                replyToPlayer(player, "开场预兆已触发");
            }
            case "tunnel" -> replyToPlayer(player, "accepted=" + SynaManifestationDirector.get().debugRevealNearby(player));
            case "hit" -> lightHit(player.getGameProfile().getName());
            case "warning" -> { if (getSyna() == null) summonFromChat(player); horror("warn", player.getGameProfile().getName(), 45, null, null, "manual_test", null); }
            case "countdown" -> { if (getSyna() == null) summonFromChat(player); horror("countdown", player.getGameProfile().getName(), 70, null, null, "manual_test", null); }
            case "hunt" -> { if (getSyna() == null) summonFromChat(player); horror("hunt", player.getGameProfile().getName(), 90, null, null, "manual_test", null); }
            case "calm" -> { if (getSyna() != null) horror("forgive", player.getGameProfile().getName(), 0, null, null, "manual_test", null); }
            case "game_block" -> { if (getSyna() == null) summonFromChat(player); horror("challenge_block", player.getGameProfile().getName(), 1, 120, "minecraft:coal", "投入 1 个煤。", null); }
            case "game_kill" -> { if (getSyna() == null) summonFromChat(player); horror("challenge_kill", player.getGameProfile().getName(), 1, 180, "minecraft:zombie", "击杀 1 只僵尸。", null); }
            case "final" -> replyToPlayer(player, SynaBoredomDirector.get().debugStartFinalCycle(player));
            case "boredom" -> {
                int value = parts.length >= 4 && parseInteger(parts[3]) != null ? parseInteger(parts[3]) : 100;
                replyToPlayer(player, SynaBoredomDirector.get().debugSetBoredom(player, value));
            }
            case "observe" -> testObservation(player, parts.length >= 4 ? parts[3] : "diamond");
            case "story" -> {
                String scene = parts.length >= 4 ? parts[3] : "observe";
                SynaStoryDirector.get().command(player.server, "chapter_5", player.getGameProfile().getName(), "manual_test");
                boolean accepted = SynaStoryDirector.get().command(player.server, "force_" + scene,
                        player.getGameProfile().getName(), "manual_test");
                replyToPlayer(player, "story=" + scene + ", accepted=" + accepted);
            }
            case "seal_prepare" -> replyToPlayer(player, SynaTrueNameDirector.get().debugPrepare(player));
            case "intro_reset" -> replyToPlayer(player, SynaFirstContactDirector.get().debugReset(player));
            case "fx" -> {
                boolean enabled = parts.length < 4 || !"off".equalsIgnoreCase(parts[3]);
                setHorrorFxEnabled(enabled);
                replyToPlayer(player, "horrorFx=" + enabled);
            }
            case "rules" -> {
                boolean enabled = parts.length < 4 || !"off".equalsIgnoreCase(parts[3]);
                player.server.overworld().getGameRules().getRule(SynaGameRules.FINAL_CYCLE).set(enabled, player.server);
                player.server.overworld().getGameRules().getRule(SynaGameRules.HORROR_EVENTS).set(enabled, player.server);
                replyToPlayer(player, "synaFinalCycle=" + enabled + ", synaHorrorEvents=" + enabled);
            }
            case "cleanup" -> { HorrorEntityEventDirector.get().reset(player.server); despawn(); replyToPlayer(player, "测试实体与 Syna 已清理"); }
            case "reset" -> {
                HorrorEntityEventDirector.get().reset(player.server);
                SynaStoryDirector.get().command(player.server, "reset", player.getGameProfile().getName(), "manual_test");
                if (getSyna() != null) horror("forgive", player.getGameProfile().getName(), 0,
                        null, null, "manual_test_reset", null);
                if (getSyna() != null) despawn();
                SynaBoredomDirector.get().debugReset(player);
                SynaFirstContactDirector.get().debugReset(player);
                replyToPlayer(player, "故事、恐怖实体、无聊值和 Syna 已重置");
            }
            case "status" -> replyToPlayer(player, "lastEvent=" + BridgeState.get().getLastEvent() + "; "
                    + SynaStoryDirector.get().statusLine(player.server) + "; horror=" + getHorrorStage()
                    + "; " + HorrorEntityEventDirector.get().statusLine()
                    + "; boredom=" + SynaBoredomDirector.get().toJson(player.server));
            default -> replyToPlayer(player, "未知测试功能。输入 syna test list");
        }
        if (!("steps".equals(feature) || "knock".equals(feature) || "breath".equals(feature)
                || "darkness".equals(feature) || "watcher".equals(feature) || "stalker".equals(feature)
                || "ambush".equals(feature) || "enforcer".equals(feature) || "status".equals(feature)
                || "boredom".equals(feature) || "final".equals(feature))) {
            replyToPlayer(player, "test=" + feature + ", lastEvent=" + BridgeState.get().getLastEvent());
        }
    }

    private void testObservation(ServerPlayer player, String requested) {
        String key = requested == null ? "diamond" : requested.toLowerCase(java.util.Locale.ROOT);
        String[] fact = switch (key) {
            case "debris" -> new String[]{"rare_block", "ancient_debris"};
            case "emerald" -> new String[]{"rare_block", "emerald"};
            case "nether" -> new String[]{"dimension", "minecraft:the_nether"};
            case "end" -> new String[]{"dimension", "minecraft:the_end"};
            case "dragon" -> new String[]{"major_kill", "entity.minecraft.ender_dragon"};
            case "wither" -> new String[]{"major_kill", "entity.minecraft.wither"};
            case "warden" -> new String[]{"major_kill", "entity.minecraft.warden"};
            default -> new String[]{"rare_block", "diamond"};
        };
        SynaBoredomDirector.get().observe(player, fact[0], fact[1]);
    }

    private void replyToPlayer(ServerPlayer player, String text) {
        if (player != null && text != null) {
            player.sendSystemMessage(Component.literal("[Syna] " + text));
        }
    }

    private void tickCollectWood(AliceEntity syna) {
        woodStageTicks++;
        updateWoodStuckState(syna);

        if (woodCollectedCount >= woodGoalCount) {
            finishWoodTask("collect_wood_completed");
            return;
        }

        ServerLevel level = (ServerLevel) syna.level();
        switch (woodTaskStage) {
            case SEARCHING -> {
                woodTarget = findNearestLog(level, syna.blockPosition());
                woodDropTargetUuid = null;
                woodBreakTicks = 0;
                woodStageTicks = 0;
                if (woodTarget == null) {
                    BridgeState.get().addDebug("searching:no_log radius=" + woodSearchRadius + ",anchor=" + formatPos(woodSearchAnchor));
                    if (!tickExploreForLogs(syna, level)) {
                        failWoodTask("collect_wood_failed:no_logs_found");
                    }
                    return;
                }
                woodExploreTarget = null;
                woodTaskStage = WoodTaskStage.MOVING_TO_LOG;
                taskDetail = "moving_to_log:" + formatPos(woodTarget);
                BridgeState.get().addDebug("target_locked pos=" + formatPos(woodTarget));
                BridgeState.get().setLastEvent("collect_wood_target_locked:" + formatPos(woodTarget));
            }
            case MOVING_TO_LOG -> {
                if (!isValidLog(level, woodTarget)) {
                    BridgeState.get().addDebug("moving_to_log:target_invalid pos=" + formatPos(woodTarget));
                    if ("collect_wood".equals(mobilityOwnerTask)) {
                        clearMobilityTask(false);
                    }
                    woodTaskStage = WoodTaskStage.SEARCHING;
                    taskDetail = "target_lost_researching";
                    woodStageTicks = 0;
                    return;
                }
                if (isWithinBreakRange(syna, woodTarget)) {
                    syna.getNavigation().stop();
                    if ("collect_wood".equals(mobilityOwnerTask)) {
                        clearMobilityTask(false);
                    }
                    woodTaskStage = WoodTaskStage.ALIGNING;
                    taskDetail = "aligning_to_log:" + formatPos(woodTarget);
                    BridgeState.get().addDebug("moving_to_log:within_range pos=" + formatPos(woodTarget));
                    woodStageTicks = 0;
                    return;
                }
                if (!"collect_wood".equals(mobilityOwnerTask)) {
                    beginCollectWoodMobility(syna, woodTarget, "wood_target_locked");
                }
                taskDetail = "moving_to_log:" + formatPos(woodTarget);
                if (woodStageTicks > WOOD_STAGE_TIMEOUT_TICKS || woodStuckTicks > 40) {
                    BridgeState.get().addDebug("moving_to_log:timeout stuck=" + woodStuckTicks + ",ticks=" + woodStageTicks + ",target=" + formatPos(woodTarget));
                    if ("collect_wood".equals(mobilityOwnerTask)) {
                        clearMobilityTask(false);
                    }
                    woodTaskStage = WoodTaskStage.SEARCHING;
                    taskDetail = "wood_path_retry_searching";
                    woodStageTicks = 0;
                }
            }
            case ALIGNING -> {
                if (!isValidLog(level, woodTarget)) {
                    BridgeState.get().addDebug("aligning:target_invalid pos=" + formatPos(woodTarget));
                    woodTaskStage = WoodTaskStage.SEARCHING;
                    taskDetail = "target_lost_researching";
                    woodStageTicks = 0;
                    return;
                }
                lookAtBlock(syna, woodTarget);
                woodBreakTicks = 0;
                woodTaskStage = WoodTaskStage.BREAKING;
                taskDetail = "breaking_log:" + formatPos(woodTarget);
                woodStageTicks = 0;
                BridgeState.get().addDebug("aligning:begin_break pos=" + formatPos(woodTarget) + ",eyeDist=" + round3(syna.getEyePosition().distanceTo(Vec3.atCenterOf(woodTarget))));
                BridgeState.get().setLastEvent("collect_wood_breaking:" + formatPos(woodTarget));
            }
            case BREAKING -> {
                if (!isValidLog(level, woodTarget)) {
                    BridgeState.get().addDebug("breaking:target_gone pos=" + formatPos(woodTarget));
                    clearBreakingAnimation(level);
                    woodTaskStage = WoodTaskStage.COLLECTING_DROPS;
                    taskDetail = "log_gone_collecting_drops";
                    woodStageTicks = 0;
                    return;
                }
                if (!isWithinBreakRange(syna, woodTarget)) {
                    BridgeState.get().addDebug("breaking:out_of_range pos=" + formatPos(woodTarget) + ",eyeDist=" + round3(syna.getEyePosition().distanceTo(Vec3.atCenterOf(woodTarget))));
                    clearBreakingAnimation(level);
                    woodTaskStage = WoodTaskStage.MOVING_TO_LOG;
                    taskDetail = "repositioning_for_log";
                    woodStageTicks = 0;
                    return;
                }
                lookAtBlock(syna, woodTarget);
                woodBreakTicks++;
                syna.setMiningSwing(true);
                level.destroyBlockProgress(syna.getId(), woodTarget, Math.min(9, (woodBreakTicks * 10) / BREAK_TICKS_REQUIRED));
                if (woodBreakTicks == 1 || woodBreakTicks % 10 == 0 || woodBreakTicks >= BREAK_TICKS_REQUIRED) {
                    BridgeState.get().addDebug("breaking:tick=" + woodBreakTicks + ",target=" + formatPos(woodTarget) + ",entityId=" + syna.getId());
                }
                if (woodBreakTicks >= BREAK_TICKS_REQUIRED) {
                    BlockPos brokenLogPos = woodTarget;
                    clearBreakingAnimation(level);
                    syna.setMiningSwing(false);
                    if (level.destroyBlock(brokenLogPos, true, syna)) {
                        woodBrokenCount++;
                        woodDropTargetUuid = null;
                        woodTarget = brokenLogPos;
                        woodTaskStage = WoodTaskStage.COLLECTING_DROPS;
                        taskDetail = "collecting_drops:" + formatPos(brokenLogPos);
                        woodStageTicks = 0;
                        BridgeState.get().addDebug("breaking:destroy_success pos=" + formatPos(brokenLogPos) + ",brokenCount=" + woodBrokenCount);
                        BridgeState.get().setLastEvent("collect_wood_log_broken:" + formatPos(brokenLogPos));
                    } else {
                        failWoodTask("collect_wood_failed:break_failed");
                    }
                    woodBreakTicks = 0;
                }
            }
            case COLLECTING_DROPS -> {
                ItemEntity drop = findNearestWoodDrop(level, syna.position(), woodTarget);
                if (drop == null) {
                    BridgeState.get().addDebug("collecting_drops:none near=" + formatPos(woodTarget));
                    BlockPos nextLog = woodTarget == null ? null : findNextLogInTree(level, woodTarget);
                    if (nextLog != null) {
                        woodTarget = nextLog;
                        woodTaskStage = WoodTaskStage.MOVING_TO_LOG;
                        taskDetail = "continuing_trunk:" + formatPos(nextLog);
                        woodStageTicks = 0;
                        return;
                    }
                    woodTarget = null;
                    woodDropTargetUuid = null;
                    woodTaskStage = woodCollectedCount >= woodGoalCount ? WoodTaskStage.COMPLETED : WoodTaskStage.SEARCHING;
                    taskDetail = woodTaskStage == WoodTaskStage.COMPLETED ? "wood_goal_reached" : "searching_next_log";
                    woodStageTicks = 0;
                    return;
                }
                woodDropTargetUuid = drop.getUUID();
                if (syna.distanceToSqr(drop) <= WOOD_PICKUP_RANGE * WOOD_PICKUP_RANGE) {
                    syna.getNavigation().stop();
                    var stack = drop.getItem().copy();
                    var remaining = syna.addToInventory(stack);
                    int accepted = stack.getCount() - remaining.getCount();
                    if (accepted > 0) {
                        syna.take(drop, accepted);
                        woodCollectedCount += accepted;
                        BridgeState.get().addDebug("pickup:accepted=" + accepted + ",item=" + stack.getItem() + ",remaining=" + remaining.getCount() + ",occupied=" + syna.getInventoryOccupiedSlots());
                        if (remaining.isEmpty()) {
                            drop.discard();
                        } else {
                            drop.setItem(remaining);
                        }
                        BridgeState.get().setLastEvent("collect_wood_item_picked:" + woodCollectedCount);
                        if (woodCollectedCount >= woodGoalCount) {
                            finishWoodTask("collect_wood_completed");
                        }
                    } else {
                        BridgeState.get().addDebug("pickup:inventory_full item=" + stack.getItem() + ",count=" + stack.getCount());
                        failWoodTask("collect_wood_failed:inventory_full");
                    }
                } else {
                    syna.getNavigation().moveTo(drop.getX(), drop.getY(), drop.getZ(), 1.0D);
                    taskDetail = "collecting_drop:" + formatPos(drop.blockPosition());
                    BridgeState.get().addDebug("pickup:moving_to_drop pos=" + formatPos(drop.blockPosition()) + ",dist=" + round3(Math.sqrt(syna.distanceToSqr(drop))));
                    if (woodStageTicks > WOOD_STAGE_TIMEOUT_TICKS || woodStuckTicks > 40) {
                        woodTaskStage = woodCollectedCount >= woodGoalCount ? WoodTaskStage.COMPLETED : WoodTaskStage.SEARCHING;
                        taskDetail = "drop_timeout_researching";
                        woodStageTicks = 0;
                    }
                }
            }
            case COMPLETED -> finishWoodTask("collect_wood_completed");
            case FAILED, IDLE -> {
                currentTask = "idle";
                taskDetail = "idle";
            }
        }
    }

    private void tickCollectStone(AliceEntity syna) {
        stoneStageTicks++;
        updateStoneStuckState(syna);

        if (shouldEscapeFromStoneTask(syna)) {
            taskDetail = "stone_safety_escape";
            BridgeState.get().setLastEvent("collect_stone_paused:stone_safety_escape");
            beginEscapeToAnchor(syna, stoneSearchAnchor, "stone_safety_escape");
            return;
        }

        if (stoneCollectedCount >= stoneGoalCount) {
            finishStoneTask("collect_stone_completed");
            return;
        }

        ServerLevel level = (ServerLevel) syna.level();
        switch (stoneTaskStage) {
            case SEARCHING -> {
                stoneTarget = findNearestStoneBlock(level, syna.blockPosition());
                stoneDropTargetUuid = null;
                stoneBreakTicks = 0;
                stoneStageTicks = 0;
                if (stoneTarget == null) {
                    BridgeState.get().addDebug("stone_searching:none radius=" + stoneSearchRadius + ",anchor=" + formatPos(stoneSearchAnchor));
                    if (!tickExploreForStone(syna, level)) {
                        failStoneTask("collect_stone_failed:no_stone_found");
                    }
                    return;
                }
                stoneExploreTarget = null;
                stoneTaskStage = StoneTaskStage.MOVING_TO_BLOCK;
                taskDetail = "moving_to_stone:" + formatPos(stoneTarget);
                BridgeState.get().addDebug("stone_target_locked pos=" + formatPos(stoneTarget));
                BridgeState.get().setLastEvent("collect_stone_target_locked:" + formatPos(stoneTarget));
            }
            case MOVING_TO_BLOCK -> {
                if (!isValidStoneTarget(level, stoneTarget)) {
                    if ("collect_stone".equals(mobilityOwnerTask)) {
                        clearMobilityTask(false);
                    }
                    stoneTaskStage = StoneTaskStage.SEARCHING;
                    taskDetail = "stone_target_lost_researching";
                    stoneStageTicks = 0;
                    return;
                }
                if (isWithinStoneBreakRange(syna, stoneTarget)) {
                    syna.getNavigation().stop();
                    if ("collect_stone".equals(mobilityOwnerTask)) {
                        clearMobilityTask(false);
                    }
                    stoneTaskStage = StoneTaskStage.ALIGNING;
                    taskDetail = "aligning_to_stone:" + formatPos(stoneTarget);
                    stoneStageTicks = 0;
                    return;
                }
                if (!"collect_stone".equals(mobilityOwnerTask)) {
                    beginCollectStoneMobility(syna, stoneTarget, "stone_target_locked");
                }
                taskDetail = "moving_to_stone:" + formatPos(stoneTarget);
                if (stoneStageTicks > STONE_STAGE_TIMEOUT_TICKS || stoneStuckTicks > 40) {
                    if ("collect_stone".equals(mobilityOwnerTask)) {
                        clearMobilityTask(false);
                    }
                    stoneTaskStage = StoneTaskStage.SEARCHING;
                    taskDetail = "stone_path_retry_searching";
                    stoneStageTicks = 0;
                }
            }
            case ALIGNING -> {
                if (!isValidStoneTarget(level, stoneTarget)) {
                    stoneTaskStage = StoneTaskStage.SEARCHING;
                    taskDetail = "stone_target_lost_researching";
                    stoneStageTicks = 0;
                    return;
                }
                lookAtBlock(syna, stoneTarget);
                stoneBreakTicks = 0;
                stoneTaskStage = StoneTaskStage.BREAKING;
                taskDetail = "breaking_stone:" + formatPos(stoneTarget);
                stoneStageTicks = 0;
                BridgeState.get().setLastEvent("collect_stone_breaking:" + formatPos(stoneTarget));
            }
            case BREAKING -> {
                if (!isValidStoneTarget(level, stoneTarget)) {
                    clearBreakingAnimation(level, stoneTarget);
                    stoneTaskStage = StoneTaskStage.COLLECTING_DROPS;
                    taskDetail = "stone_gone_collecting_drops";
                    stoneStageTicks = 0;
                    return;
                }
                if (!isWithinStoneBreakRange(syna, stoneTarget)) {
                    clearBreakingAnimation(level, stoneTarget);
                    stoneTaskStage = StoneTaskStage.MOVING_TO_BLOCK;
                    taskDetail = "repositioning_for_stone";
                    stoneStageTicks = 0;
                    return;
                }
                lookAtBlock(syna, stoneTarget);
                stoneBreakTicks++;
                syna.setMiningSwing(true);
                level.destroyBlockProgress(syna.getId(), stoneTarget, Math.min(9, (stoneBreakTicks * 10) / BREAK_TICKS_REQUIRED));
                if (stoneBreakTicks >= BREAK_TICKS_REQUIRED) {
                    BlockPos brokenStonePos = stoneTarget;
                    clearBreakingAnimation(level, brokenStonePos);
                    syna.setMiningSwing(false);
                    if (level.destroyBlock(brokenStonePos, true, syna)) {
                        stoneBrokenCount++;
                        stoneDropTargetUuid = null;
                        stoneTarget = brokenStonePos;
                        stoneTaskStage = StoneTaskStage.COLLECTING_DROPS;
                        taskDetail = "collecting_stone_drops:" + formatPos(brokenStonePos);
                        stoneStageTicks = 0;
                        BridgeState.get().setLastEvent("collect_stone_block_broken:" + formatPos(brokenStonePos));
                    } else {
                        failStoneTask("collect_stone_failed:break_failed");
                    }
                    stoneBreakTicks = 0;
                }
            }
            case COLLECTING_DROPS -> {
                ItemEntity drop = findNearestStoneDrop(level, syna.position(), stoneTarget);
                if (drop == null) {
                    stoneTarget = null;
                    stoneDropTargetUuid = null;
                    stoneTaskStage = stoneCollectedCount >= stoneGoalCount ? StoneTaskStage.COMPLETED : StoneTaskStage.SEARCHING;
                    taskDetail = stoneTaskStage == StoneTaskStage.COMPLETED ? "stone_goal_reached" : "searching_next_stone";
                    stoneStageTicks = 0;
                    return;
                }
                stoneDropTargetUuid = drop.getUUID();
                if (syna.distanceToSqr(drop) <= STONE_PICKUP_RANGE * STONE_PICKUP_RANGE) {
                    syna.getNavigation().stop();
                    var stack = drop.getItem().copy();
                    var remaining = syna.addToInventory(stack);
                    int accepted = stack.getCount() - remaining.getCount();
                    if (accepted > 0) {
                        syna.take(drop, accepted);
                        stoneCollectedCount += accepted;
                        if (remaining.isEmpty()) {
                            drop.discard();
                        } else {
                            drop.setItem(remaining);
                        }
                        BridgeState.get().setLastEvent("collect_stone_item_picked:" + stoneCollectedCount);
                        if (stoneCollectedCount >= stoneGoalCount) {
                            finishStoneTask("collect_stone_completed");
                        }
                    } else {
                        failStoneTask("collect_stone_failed:inventory_full");
                    }
                } else {
                    syna.getNavigation().moveTo(drop.getX(), drop.getY(), drop.getZ(), 1.0D);
                    taskDetail = "collecting_stone_drop:" + formatPos(drop.blockPosition());
                    if (stoneStageTicks > STONE_STAGE_TIMEOUT_TICKS || stoneStuckTicks > 40) {
                        stoneTaskStage = stoneCollectedCount >= stoneGoalCount ? StoneTaskStage.COMPLETED : StoneTaskStage.SEARCHING;
                        taskDetail = "stone_drop_timeout_researching";
                        stoneStageTicks = 0;
                    }
                }
            }
            case COMPLETED -> finishStoneTask("collect_stone_completed");
            case FAILED, IDLE -> {
                currentTask = "idle";
                taskDetail = "idle";
            }
        }
    }

    private void tickEscapeToAnchor(AliceEntity syna) {
        MobilityTickResult result = tickMobilityPlan(syna);
        if (result == MobilityTickResult.COMPLETED) {
            finishMobilityTask("escape_to_anchor_completed");
        } else if (result == MobilityTickResult.FAILED) {
            failMobilityTask(mobilityLastFailure == null || mobilityLastFailure.isBlank()
                    ? "escape_to_anchor_failed:planner_failed"
                    : mobilityLastFailure);
        }
    }

    private void tickFollowMobility(AliceEntity syna) {
        ServerPlayer player = mobilityTargetPlayerUuid == null ? null : getPlayer(mobilityTargetPlayerUuid);
        if (player == null) {
            followPlayerUuid = null;
            failMobilityTask("follow_failed:target_lost");
            return;
        }

        BlockPos standPos = findBestFollowStandPos((ServerLevel) syna.level(), syna, player);
        mobilityGoal = (standPos != null ? standPos : player.blockPosition()).immutable();
        mobilityAnchor = player.blockPosition().immutable();
        mobilityTargetStandPos = standPos == null ? null : standPos.immutable();
        mobilityTargetPlayerUuid = player.getUUID();
        mobilityReason = "tracking:" + player.getGameProfile().getName();

        MobilityTickResult result = tickMobilityPlan(syna);
        if (result == MobilityTickResult.FAILED) {
            followPlayerUuid = player.getUUID();
            markMobilityReplan("follow_failed_replan");
            taskDetail = mobilityLastFailure == null || mobilityLastFailure.isBlank()
                    ? "follow_repath_pending"
                    : mobilityLastFailure;
        }
    }

    private void finishWoodTask(String event) {
        AliceEntity syna = getSyna();
        if (syna != null) {
            syna.getNavigation().stop();
            syna.setMiningSwing(false);
            clearBreakingAnimation((ServerLevel) syna.level());
        }
        woodTaskStage = WoodTaskStage.COMPLETED;
        if (craftResumeAfterWood) {
            currentTask = "craft_item";
            craftTaskStage = CraftTaskStage.CHECKING;
            taskDetail = "craft_resume_after_collect_wood:" + woodCollectedCount;
            announceTaskMessage("材料已补齐一部分，继续尝试合成 " + simplifyItemId(craftTargetItem) + "。", false);
            craftResumeAfterWood = false;
            craftRequestedWoodCount = 0;
        } else {
            currentTask = "idle";
            taskDetail = "wood_collected:" + woodCollectedCount;
            announceTaskMessage("任务完成：已采集木头 x" + woodCollectedCount, false);
        }
        BridgeState.get().setLastEvent(event + ":" + woodCollectedCount);
    }

    private void finishStoneTask(String event) {
        AliceEntity syna = getSyna();
        if (syna != null) {
            syna.getNavigation().stop();
            syna.setMiningSwing(false);
            clearBreakingAnimation((ServerLevel) syna.level(), stoneTarget);
        }
        stoneTaskStage = StoneTaskStage.COMPLETED;
        currentTask = "idle";
        taskDetail = "stone_collected:" + stoneCollectedCount;
        announceTaskMessage("任务完成：已采集石材 x" + stoneCollectedCount, false);
        BridgeState.get().setLastEvent(event + ":" + stoneCollectedCount);
    }

    private void tickCraftItem(AliceEntity syna) {
        SynaInventory inventory = syna.getInventory();
        CraftRecipe recipe = resolveCraftRecipe(craftTargetItem);
        if (recipe == null) {
            failCraftTask("craft_item_failed:unsupported_recipe");
            return;
        }

        if (craftCraftedCount >= craftTargetCount) {
            finishCraftTask("craft_item_completed");
            return;
        }

        switch (craftTaskStage) {
            case CHECKING -> {
                craftMissingItems.clear();
                collectMissingIngredients(inventory, recipe, craftMissingItems);
                if (!craftMissingItems.isEmpty()) {
                    if (tryAutoResolveCraftMaterials(syna, inventory, recipe)) {
                        return;
                    }
                    craftLastResult = "missing_materials";
                    taskDetail = "craft_missing:" + String.join("|", craftMissingItems);
                    failCraftTask("craft_item_failed:missing_materials");
                    return;
                }

                ItemStack output = new ItemStack(recipe.outputItem(), recipe.outputCount());
                if (!inventory.canAccept(output)) {
                    craftLastResult = "inventory_full";
                    taskDetail = "craft_inventory_full";
                    failCraftTask("craft_item_failed:inventory_full");
                    return;
                }

                craftTaskStage = CraftTaskStage.EXECUTING;
                taskDetail = "crafting:" + recipe.targetItemId();
                BridgeState.get().setLastEvent("craft_item_executing:" + recipe.targetItemId());
            }
            case EXECUTING -> {
                craftMissingItems.clear();
                collectMissingIngredients(inventory, recipe, craftMissingItems);
                if (!craftMissingItems.isEmpty()) {
                    craftLastResult = "partial_missing_materials";
                    failCraftTask("craft_item_failed:materials_changed");
                    return;
                }

                consumeIngredients(inventory, recipe);
                ItemStack remaining = inventory.insert(new ItemStack(recipe.outputItem(), recipe.outputCount()));
                if (!remaining.isEmpty()) {
                    failCraftTask("craft_item_failed:inventory_insert_failed");
                    return;
                }

                craftCraftedCount += recipe.outputCount();
                craftLastResult = "crafted:" + recipe.targetItemId();
                taskDetail = "crafted:" + recipe.targetItemId() + ":" + craftCraftedCount + "/" + craftTargetCount;
                BridgeState.get().addDebug("craft_item_success item=" + recipe.targetItemId() + ",crafted=" + craftCraftedCount + ",target=" + craftTargetCount);
                BridgeState.get().setLastEvent("craft_item_progress:" + recipe.targetItemId() + ":" + craftCraftedCount);

                if (craftCraftedCount >= craftTargetCount) {
                    finishCraftTask("craft_item_completed");
                } else {
                    craftTaskStage = CraftTaskStage.CHECKING;
                }
            }
            case COMPLETED -> finishCraftTask("craft_item_completed");
            case FAILED, IDLE -> {
                currentTask = "idle";
                taskDetail = "idle";
            }
        }
    }

    private void finishCraftTask(String event) {
        craftTaskStage = CraftTaskStage.COMPLETED;
        currentTask = "idle";
        taskDetail = "craft_completed:" + craftTargetItem + ":" + craftCraftedCount;
        craftLastResult = "completed";
        announceTaskMessage("任务完成：已合成 " + simplifyItemId(craftTargetItem) + " x" + craftCraftedCount, false);
        BridgeState.get().setLastEvent(event + ":" + craftTargetItem + ":" + craftCraftedCount);
    }

    private void failCraftTask(String event) {
        craftTaskStage = CraftTaskStage.FAILED;
        currentTask = "idle";
        taskDetail = event;
        if ("craft_item_failed:missing_materials".equals(event)) {
            announceTaskMessage("合成失败：缺少材料 " + humanizeMissingList(craftMissingItems), true);
        } else if ("craft_item_failed:unsupported_recipe".equals(event)) {
            announceTaskMessage("合成失败：暂不支持 " + simplifyItemId(craftTargetItem) + " 的配方。", true);
        } else if ("craft_item_failed:inventory_full".equals(event)) {
            announceTaskMessage("合成失败：背包空间不足。", true);
        } else if ("craft_item_failed:auto_collect_failed".equals(event)) {
            announceTaskMessage("合成失败：自动补材料失败，无法获取所需常见材料。", true);
        } else {
            announceTaskMessage("合成失败：" + event, true);
        }
        BridgeState.get().setLastEvent(event);
    }

    private void failWoodTask(String event) {
        AliceEntity syna = getSyna();
        if (syna != null) {
            syna.getNavigation().stop();
            syna.setMiningSwing(false);
            clearBreakingAnimation((ServerLevel) syna.level());
        }
        woodTaskStage = WoodTaskStage.FAILED;
        currentTask = "idle";
        taskDetail = event;
        if (craftResumeAfterWood) {
            craftResumeAfterWood = false;
            craftRequestedWoodCount = 0;
            craftLastResult = "auto_collect_failed";
            failCraftTask("craft_item_failed:auto_collect_failed");
            return;
        }
        announceTaskMessage("任务失败：" + event, true);
        BridgeState.get().setLastEvent(event);
    }

    private void failStoneTask(String event) {
        AliceEntity syna = getSyna();
        if (syna != null) {
            syna.getNavigation().stop();
            syna.setMiningSwing(false);
            clearBreakingAnimation((ServerLevel) syna.level(), stoneTarget);
        }
        stoneTaskStage = StoneTaskStage.FAILED;
        currentTask = "idle";
        taskDetail = event;
        announceTaskMessage("任务失败：" + event, true);
        BridgeState.get().setLastEvent(event);
    }

    private void beginEscapeToAnchor(AliceEntity syna, BlockPos preferredAnchor, String reason) {
        beginMobilityTask(syna,
                MobilityPlanType.ESCAPE_TO_ANCHOR,
                preferredAnchor != null ? preferredAnchor.immutable() : resolveMobilityAnchor(syna),
                "escape_to_anchor",
                reason,
                null);
    }

    private void beginFollowMobility(AliceEntity syna, ServerPlayer player, String reason) {
        if (syna == null || player == null) {
            return;
        }
        beginMobilityTask(syna,
                MobilityPlanType.FOLLOW_ENTITY,
                player.blockPosition().immutable(),
                "follow",
                reason,
                player.getUUID());
        taskDetail = "following:" + player.getGameProfile().getName();
        currentTask = "follow";
    }

    private void beginGoToMobility(AliceEntity syna, BlockPos goal, String reason) {
        if (syna == null || goal == null) {
            return;
        }
        beginMobilityTask(syna,
                MobilityPlanType.MOVE_TO_BLOCK,
                goal.immutable(),
                "go_to",
                reason,
                null);
        taskDetail = "moving:" + formatPos(goal);
        currentTask = "go_to";
    }

    private void beginCollectStoneMobility(AliceEntity syna, BlockPos goal, String reason) {
        if (syna == null || goal == null) {
            return;
        }
        beginMobilityTask(syna,
                MobilityPlanType.MOVE_TO_BLOCK,
                goal.immutable(),
                "collect_stone",
                reason,
                null);
        taskDetail = "moving_to_stone:" + formatPos(goal);
        currentTask = "collect_stone";
    }

    private void beginCollectWoodMobility(AliceEntity syna, BlockPos goal, String reason) {
        if (syna == null || goal == null) {
            return;
        }
        beginMobilityTask(syna,
                MobilityPlanType.MOVE_TO_BLOCK,
                goal.immutable(),
                "collect_wood",
                reason,
                null);
        taskDetail = "moving_to_log:" + formatPos(goal);
        currentTask = "collect_wood";
    }

    private void beginMobilityTask(AliceEntity syna,
                                   MobilityPlanType planType,
                                   BlockPos goal,
                                   String ownerTask,
                                   String reason,
                                   UUID targetPlayerUuid) {
        if (syna == null) {
            return;
        }
        mobilityAnchor = goal;
        mobilityGoal = goal;
        mobilityTarget = null;
        mobilityTargetStandPos = null;
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        mobilitySupportTarget = null;
        mobilityProtectedTarget = null;
        mobilityBlockedBy = null;
        mobilityTargetPlayerUuid = targetPlayerUuid;
        mobilitySupportBlocksUsed = 0;
        mobilitySupportPlacePhase = 0;
        mobilityStageTicks = 0;
        mobilityPlanTicks = 0;
        mobilityStuckTicks = 0;
        mobilityJumpCooldownTicks = 0;
        mobilityVerticalPlaceDelayTicks = 0;
        mobilityBlocksBroken = 0;
        mobilityReplanCount = 0;
        mobilityLockedSupportUntilPlanTick = 0;
        mobilityLockedSupportPos = null;
        lastMobilityProgressPos = null;
        mobilityBlockedReason = "";
        mobilityReplanReason = "";
        mobilityLastActionResult = "started";
        mobilityDirectPathAvailable = false;
        mobilityProtectionMode = BuildingProtectionMode.OFF;
        mobilityPlanType = planType;
        mobilityPlannerStage = MobilityPlannerStage.EVALUATING;
        mobilityAction = MobilityAction.NONE;
        mobilityFallback = MobilityFallback.DIRECT_WALK;
        mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
        mobilityOwnerTask = ownerTask == null ? "idle" : ownerTask;
        mobilityReason = reason == null ? "unknown" : reason;
        mobilityLastFailure = "";
        mobilityDetail = "mobility_init:" + mobilityOwnerTask + ":" + mobilityReason + ":goal=" + formatPos(mobilityGoal);
        currentTask = mobilityOwnerTask;
        taskDetail = mobilityDetail;
        syna.setMiningSwing(false);
        syna.getNavigation().stop();
        BridgeState.get().setLastEvent("mobility_started:" + mobilityOwnerTask + ":" + mobilityReason + ":" + formatPos(mobilityGoal));
        BridgeState.get().addDebug("mobility_start owner=" + mobilityOwnerTask + ",reason=" + mobilityReason + ",goal=" + formatPos(mobilityGoal));
    }

    private void finishMobilityTask(String event) {
        AliceEntity syna = getSyna();
        if (syna != null) {
            syna.getNavigation().stop();
            syna.setMiningSwing(false);
        }
        mobilityPlannerStage = MobilityPlannerStage.COMPLETED;
        mobilityAction = MobilityAction.NONE;
        mobilityFallback = MobilityFallback.NONE;
        mobilityMode = MobilityMode.COMPLETED;
        mobilityDetail = "goal_reached:" + formatPos(mobilityGoal);
        currentTask = "idle";
        taskDetail = mobilityDetail;
        BridgeState.get().setLastEvent(event + ":" + formatPos(mobilityGoal));
    }

    private void failMobilityTask(String event) {
        AliceEntity syna = getSyna();
        if (syna != null) {
            syna.getNavigation().stop();
            syna.setMiningSwing(false);
        }
        mobilityPlannerStage = MobilityPlannerStage.FAILED;
        mobilityAction = MobilityAction.NONE;
        mobilityMode = MobilityMode.FAILED;
        mobilityLastFailure = event;
        mobilityDetail = event;
        currentTask = "idle";
        taskDetail = event;
        BridgeState.get().setLastEvent(event);
    }

    private void resetAllTasks() {
        resourceSubtaskManager.cancel("reset");
        moveTarget = null;
        followPlayerUuid = null;
        clearMobilityTask(true);
        clearWoodTask(true);
        clearStoneTask(true);
        clearCraftTask(true);
        currentTask = "idle";
        taskDetail = "idle";
    }

    private void clearMobilityTask(boolean resetCounters) {
        mobilityPlanType = MobilityPlanType.NONE;
        mobilityPlannerStage = MobilityPlannerStage.IDLE;
        mobilityAction = MobilityAction.NONE;
        mobilityFallback = MobilityFallback.NONE;
        mobilityMode = MobilityMode.IDLE;
        mobilityDetail = "idle";
        mobilityOwnerTask = "idle";
        mobilityReason = "idle";
        mobilityLastFailure = "";
        mobilityGoal = null;
        mobilityTargetPlayerUuid = null;
        mobilityTarget = null;
        mobilityTargetStandPos = null;
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        mobilitySupportTarget = null;
        mobilityProtectedTarget = null;
        mobilityBlockedBy = null;
        mobilitySupportPlacePhase = 0;
        mobilityStageTicks = 0;
        mobilityPlanTicks = 0;
        mobilityStuckTicks = 0;
        mobilityJumpCooldownTicks = 0;
        mobilityVerticalPlaceDelayTicks = 0;
        mobilityBlocksBroken = 0;
        mobilityReplanCount = 0;
        mobilityLockedSupportUntilPlanTick = 0;
        mobilityLockedSupportPos = null;
        lastMobilityProgressPos = null;
        mobilityBlockedReason = "";
        mobilityReplanReason = "";
        mobilityLastActionResult = "idle";
        mobilityHudText = "";
        mobilityHudTicks = 0;
        mobilityDirectPathAvailable = false;
        mobilityProtectionMode = BuildingProtectionMode.OFF;
        if (resetCounters) {
            mobilityAnchor = null;
            mobilitySupportBlocksUsed = 0;
        }
    }

    private void clearWoodTask(boolean resetCounters) {
        woodTarget = null;
        woodDropTargetUuid = null;
        woodBreakTicks = 0;
        woodStageTicks = 0;
        woodSearchRadius = WOOD_SEARCH_RADIUS;
        woodStuckTicks = 0;
        lastWoodProgressPos = null;
        woodSearchAnchor = null;
        woodExploreTarget = null;
        woodTaskStage = WoodTaskStage.IDLE;
        if (resetCounters) {
            woodGoalCount = 0;
            woodCollectedCount = 0;
            woodBrokenCount = 0;
        }
    }

    private void clearCraftTask(boolean resetCounters) {
        craftTaskStage = CraftTaskStage.IDLE;
        craftMissingItems.clear();
        craftLastResult = null;
        craftResumeAfterWood = false;
        craftRequestedWoodCount = 0;
        if (resetCounters) {
            craftTargetItem = null;
            craftTargetCount = 0;
            craftCraftedCount = 0;
        }
    }

    private void clearStoneTask(boolean resetCounters) {
        stoneTarget = null;
        stoneDropTargetUuid = null;
        stoneBreakTicks = 0;
        stoneStageTicks = 0;
        stoneSearchRadius = STONE_SEARCH_RADIUS;
        stoneStuckTicks = 0;
        lastStoneProgressPos = null;
        stoneSearchAnchor = null;
        stoneExploreTarget = null;
        stoneTaskStage = StoneTaskStage.IDLE;
        if (resetCounters) {
            stoneGoalCount = 0;
            stoneCollectedCount = 0;
            stoneBrokenCount = 0;
        }
    }

    private CraftRecipe resolveCraftRecipe(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }

        return switch (itemId) {
            case "minecraft:oak_planks" -> new CraftRecipe(
                    itemId,
                    Items.OAK_PLANKS,
                    4,
                    List.of(new IngredientSpec("minecraft:oak_log", 1,
                            stack -> isAnyOf(stack, Items.OAK_LOG, Items.STRIPPED_OAK_LOG, Items.OAK_WOOD, Items.STRIPPED_OAK_WOOD))));
            case "minecraft:stick" -> new CraftRecipe(
                    itemId,
                    Items.STICK,
                    4,
                    List.of(new IngredientSpec("minecraft:planks", 2, stack -> stack.is(ItemTags.PLANKS))));
            case "minecraft:crafting_table" -> new CraftRecipe(
                    itemId,
                    Items.CRAFTING_TABLE,
                    1,
                    List.of(new IngredientSpec("minecraft:planks", 4, stack -> stack.is(ItemTags.PLANKS))));
            default -> {
                ResourceLocation id = ResourceLocation.tryParse(itemId);
                Item target = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
                if (target == Items.STICK) {
                    yield new CraftRecipe(itemId, Items.STICK, 4,
                            List.of(new IngredientSpec("minecraft:planks", 2, stack -> stack.is(ItemTags.PLANKS))));
                }
                if (target == Items.CRAFTING_TABLE) {
                    yield new CraftRecipe(itemId, Items.CRAFTING_TABLE, 1,
                            List.of(new IngredientSpec("minecraft:planks", 4, stack -> stack.is(ItemTags.PLANKS))));
                }
                yield null;
            }
        };
    }

    private void collectMissingIngredients(SynaInventory inventory, CraftRecipe recipe, List<String> missing) {
        for (IngredientSpec ingredient : recipe.ingredients()) {
            int available = countMatchingItems(inventory, ingredient.matcher());
            if (available < ingredient.count()) {
                missing.add(ingredient.label() + ":" + (ingredient.count() - available));
            }
        }
    }

    private void consumeIngredients(SynaInventory inventory, CraftRecipe recipe) {
        for (IngredientSpec ingredient : recipe.ingredients()) {
            removeMatchingItems(inventory, ingredient.matcher(), ingredient.count());
        }
    }

    private boolean tryAutoResolveCraftMaterials(AliceEntity syna, SynaInventory inventory, CraftRecipe recipe) {
        if (tryConvertLogsToPlanks(inventory, recipe)) {
            craftTaskStage = CraftTaskStage.CHECKING;
            taskDetail = "craft_rechecking_after_auto_materials:" + craftTargetItem;
            BridgeState.get().addDebug("craft_auto_materials:converted_logs_to_planks target=" + craftTargetItem);
            announceTaskMessage("材料不足，已自动把原木加工成木板，继续合成。", false);
            return true;
        }

        int neededLogs = estimateWoodGatherNeed(recipe, inventory);
        if (neededLogs > 0) {
            craftResumeAfterWood = true;
            craftRequestedWoodCount = neededLogs;
            collectWood(neededLogs, true);
            taskDetail = "craft_waiting_auto_collect_wood:" + neededLogs;
            BridgeState.get().addDebug("craft_auto_materials:collect_wood count=" + neededLogs + ",target=" + craftTargetItem);
            return true;
        }

        return false;
    }

    private boolean tryConvertLogsToPlanks(SynaInventory inventory, CraftRecipe recipe) {
        int missingPlanks = 0;
        for (IngredientSpec ingredient : recipe.ingredients()) {
            if (!"minecraft:planks".equals(ingredient.label())) {
                continue;
            }
            int available = countMatchingItems(inventory, ingredient.matcher());
            if (available < ingredient.count()) {
                missingPlanks += ingredient.count() - available;
            }
        }

        if (missingPlanks <= 0) {
            return false;
        }

        int created = autoCraftPlanksFromLogs(inventory, missingPlanks);
        return created > 0;
    }

    private int estimateWoodGatherNeed(CraftRecipe recipe, SynaInventory inventory) {
        if (recipe == null) {
            return 0;
        }

        if ("minecraft:oak_planks".equals(recipe.targetItemId())) {
            int availableOakLogs = countMatchingItems(inventory, stack -> isAnyOf(stack, Items.OAK_LOG, Items.STRIPPED_OAK_LOG, Items.OAK_WOOD, Items.STRIPPED_OAK_WOOD));
            return availableOakLogs >= 1 ? 0 : 1;
        }

        int missingPlanks = 0;
        for (IngredientSpec ingredient : recipe.ingredients()) {
            if (!"minecraft:planks".equals(ingredient.label())) {
                return 0;
            }
            int available = countMatchingItems(inventory, ingredient.matcher());
            if (available < ingredient.count()) {
                missingPlanks += ingredient.count() - available;
            }
        }

        if (missingPlanks <= 0) {
            return 0;
        }

        int craftablePlanks = estimateConvertiblePlanksFromLogs(inventory);
        int remainingPlanks = Math.max(0, missingPlanks - craftablePlanks);
        return remainingPlanks <= 0 ? 0 : (int) Math.ceil(remainingPlanks / 4.0D);
    }

    private int estimateConvertiblePlanksFromLogs(SynaInventory inventory) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (getPlanksForLog(stack.getItem()) != null) {
                total += stack.getCount() * 4;
            }
        }
        return total;
    }

    private int autoCraftPlanksFromLogs(SynaInventory inventory, int neededPlanks) {
        int created = 0;
        for (int i = 0; i < inventory.getContainerSize() && created < neededPlanks; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            Item plankItem = getPlanksForLog(stack.getItem());
            if (plankItem == null) {
                continue;
            }

            while (!stack.isEmpty() && created < neededPlanks) {
                if (!inventory.canAccept(new ItemStack(plankItem, 4))) {
                    return created;
                }
                stack.shrink(1);
                inventory.container().setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                ItemStack remaining = inventory.insert(new ItemStack(plankItem, 4));
                if (!remaining.isEmpty()) {
                    return created;
                }
                created += 4;
            }
        }
        return created;
    }

    private Item getPlanksForLog(Item item) {
        if (item == Items.OAK_LOG || item == Items.STRIPPED_OAK_LOG || item == Items.OAK_WOOD || item == Items.STRIPPED_OAK_WOOD) {
            return Items.OAK_PLANKS;
        }
        if (item == Items.BIRCH_LOG || item == Items.STRIPPED_BIRCH_LOG || item == Items.BIRCH_WOOD || item == Items.STRIPPED_BIRCH_WOOD) {
            return Items.BIRCH_PLANKS;
        }
        if (item == Items.SPRUCE_LOG || item == Items.STRIPPED_SPRUCE_LOG || item == Items.SPRUCE_WOOD || item == Items.STRIPPED_SPRUCE_WOOD) {
            return Items.SPRUCE_PLANKS;
        }
        if (item == Items.JUNGLE_LOG || item == Items.STRIPPED_JUNGLE_LOG || item == Items.JUNGLE_WOOD || item == Items.STRIPPED_JUNGLE_WOOD) {
            return Items.JUNGLE_PLANKS;
        }
        if (item == Items.ACACIA_LOG || item == Items.STRIPPED_ACACIA_LOG || item == Items.ACACIA_WOOD || item == Items.STRIPPED_ACACIA_WOOD) {
            return Items.ACACIA_PLANKS;
        }
        if (item == Items.DARK_OAK_LOG || item == Items.STRIPPED_DARK_OAK_LOG || item == Items.DARK_OAK_WOOD || item == Items.STRIPPED_DARK_OAK_WOOD) {
            return Items.DARK_OAK_PLANKS;
        }
        if (item == Items.MANGROVE_LOG || item == Items.STRIPPED_MANGROVE_LOG || item == Items.MANGROVE_WOOD || item == Items.STRIPPED_MANGROVE_WOOD) {
            return Items.MANGROVE_PLANKS;
        }
        if (item == Items.CHERRY_LOG || item == Items.STRIPPED_CHERRY_LOG || item == Items.CHERRY_WOOD || item == Items.STRIPPED_CHERRY_WOOD) {
            return Items.CHERRY_PLANKS;
        }
        return null;
    }

    private int countMatchingItems(SynaInventory inventory, Predicate<ItemStack> matcher) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int removeMatchingItems(SynaInventory inventory, Predicate<ItemStack> matcher, int count) {
        int remaining = count;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            inventory.container().setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            remaining -= taken;
        }
        return count - remaining;
    }

    private boolean isAnyOf(ItemStack stack, Item... items) {
        for (Item item : items) {
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldEscapeFromStoneTask(AliceEntity syna) {
        if (stoneSearchAnchor == null) {
            return false;
        }
        ServerLevel level = (ServerLevel) syna.level();
        boolean tooDeep = syna.getBlockY() <= stoneSearchAnchor.getY() - 6;
        boolean hazard = isImmediateHazard(level, syna.blockPosition());
        boolean stuckUnderground = tooDeep && stoneStuckTicks > 45;
        boolean hurt = syna.getHealth() <= 8.0F;
        return hazard || stuckUnderground || hurt;
    }

    private boolean isImmediateHazard(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState state = level.getBlockState(pos.relative(direction));
            if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos resolveMobilityAnchor(AliceEntity syna) {
        if (stoneSearchAnchor != null) {
            return stoneSearchAnchor.immutable();
        }
        if (woodSearchAnchor != null) {
            return woodSearchAnchor.immutable();
        }
        ServerPlayer player = BridgeState.get().getBoundPlayer();
        if (player != null) {
            return player.blockPosition();
        }
        return syna.blockPosition();
    }

    private boolean hasReachedAnchor(AliceEntity syna, BlockPos anchor) {
        return anchor != null && syna.blockPosition().closerThan(anchor, 2.0D) && syna.getY() >= anchor.getY() - 0.5D;
    }

    private MobilityTickResult tickMobilityPlan(AliceEntity syna) {
        mobilityPlanTicks++;
        mobilityStageTicks++;
        if (mobilityJumpCooldownTicks > 0) {
            mobilityJumpCooldownTicks--;
        }
        updateMobilityStuckState(syna);

        ServerLevel level = (ServerLevel) syna.level();
        int availableSupportBlocks = countSupportBlocks(syna.getInventory());
        if ((mobilityPlannerStage == MobilityPlannerStage.WAITING_FOR_SUPPORT
                || "no_support_blocks".equals(mobilityBlockedReason))
                && availableSupportBlocks <= 0) {
            if (tryScavengeNearbySupportBlock(level, syna, mobilitySupportTarget)) {
                return MobilityTickResult.RUNNING;
            }
            mobilityMode = MobilityMode.PLACING_SUPPORT;
            mobilityPlannerStage = MobilityPlannerStage.WAITING_FOR_SUPPORT;
            mobilityAction = MobilityAction.PLACE_SUPPORT;
            mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
            mobilityLastActionResult = "waiting_for_support";
            if (mobilityStageTicks > MOBILITY_STAGE_TIMEOUT_TICKS * 2) {
                mobilityLastFailure = mobilityFailure("no_support_blocks");
                return MobilityTickResult.FAILED;
            }
        }

        BlockPos goal = mobilityGoal != null ? mobilityGoal : mobilityAnchor;
        if (goal == null) {
            mobilityLastFailure = mobilityFailure("no_anchor");
            return MobilityTickResult.FAILED;
        }

        mobilityPlannerStage = MobilityPlannerStage.EVALUATING;
        mobilityTarget = goal.immutable();
        mobilityBlockedBy = null;
        mobilityBlockedReason = "";
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        mobilityDirectPathAvailable = canAttemptDirectMobilityPath(syna, goal);
        mobilityLastActionResult = "evaluating";

        if (hasReachedAnchor(syna, goal)) {
            if (mobilityPlanType == MobilityPlanType.MOVE_TO_ENTITY || mobilityPlanType == MobilityPlanType.FOLLOW_ENTITY) {
                syna.getNavigation().stop();
                mobilityMode = MobilityMode.HOLDING_POSITION;
                mobilityPlannerStage = MobilityPlannerStage.COMPLETED;
                mobilityAction = MobilityAction.NONE;
                mobilityFallback = MobilityFallback.NONE;
                mobilityDetail = "holding_follow_range:" + formatPos(goal);
                taskDetail = mobilityDetail;
                return MobilityTickResult.RUNNING;
            }
            mobilityMode = MobilityMode.COMPLETED;
            mobilityDetail = "planner_completed:" + formatPos(goal);
            taskDetail = mobilityDetail;
            return MobilityTickResult.COMPLETED;
        }

        if (tryDirectMobilityPath(syna, goal)) {
            mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
            mobilityPlannerStage = MobilityPlannerStage.DIRECT_PATH;
            mobilityAction = MobilityAction.WALK;
            mobilityFallback = MobilityFallback.DIRECT_WALK;
            mobilityLastActionResult = "direct_walk";
            mobilityDetail = ((mobilityPlanType == MobilityPlanType.MOVE_TO_ENTITY || mobilityPlanType == MobilityPlanType.FOLLOW_ENTITY)
                    ? "pathing_to_follow_stand_pos:" : "pathing_to_goal:") + formatPos(goal);
            taskDetail = mobilityDetail;
            return MobilityTickResult.RUNNING;
        }

        if (syna.getBlockY() < goal.getY()) {
            BlockPos headPos = syna.blockPosition().above();
            BlockState headState = level.getBlockState(headPos);
            if (!headState.isAir() && !headState.canBeReplaced()) {
                mobilityPlannerStage = MobilityPlannerStage.PREPARING_STAIR;
                mobilityAction = MobilityAction.DIG_STAIR;
                mobilityFallback = MobilityFallback.DIG_UP_STAIR;
                mobilityTarget = headPos.immutable();
                mobilityDigTarget = headPos.immutable();
                mobilityDigHeadTarget = null;
                if (!clearMobilityBlock(level, syna, headPos)) {
                    mobilityLastFailure = mobilityFailure(mobilityBlockedReason == null || mobilityBlockedReason.isBlank()
                            ? "head_blocked"
                            : mobilityBlockedReason);
                    return MobilityTickResult.FAILED;
                }
                mobilityDetail = "clearing_headroom:" + formatPos(headPos);
                taskDetail = mobilityDetail;
                mobilityLastActionResult = "headroom_cleared";
                return MobilityTickResult.RUNNING;
            }

            StairStep localStep = findPriorityMobilityStep(level, syna.blockPosition(), goal);
            if (localStep != null) {
                MobilityTickResult localStepResult = executeMobilityStep(level, syna, goal, localStep, "local");
                if (localStepResult != null) {
                    return localStepResult;
                }
            }

            if (goal.getY() - syna.getBlockY() == 1) {
                MobilityTickResult singleStepResult = tickSingleStepAscend(level, syna, goal);
                if (singleStepResult != null) {
                    return singleStepResult;
                }
            }

            MobilityTickResult corridorResult = tryLineCorridorFallback(level, syna, goal);
            if (corridorResult != null) {
                return corridorResult;
            }

            if (shouldUseVerticalAscend(level, syna, goal)) {
                return tickVerticalAscend(level, syna, goal);
            }
        }

        StairStep step = findBestAscendStep(level, syna.blockPosition(), goal);
        if (step == null) {
            MobilityTickResult corridorResult = tryLineCorridorFallback(level, syna, goal);
            if (corridorResult != null) {
                return corridorResult;
            }
            if (tryProtectedAscendFallback(level, syna, goal, mobilityProtectedTarget)) {
                return MobilityTickResult.RUNNING;
            }
            mobilityBlockedReason = "no_step";
            mobilityLastFailure = mobilityFailure("no_step");
            return MobilityTickResult.FAILED;
        }

        return executeMobilityStep(level, syna, goal, step, "stair");
    }

    private String mobilityFailure(String reason) {
        if ("collect_wood".equals(mobilityOwnerTask)) {
            return "collect_wood_failed:" + reason;
        }
        if ("collect_stone".equals(mobilityOwnerTask)) {
            return "collect_stone_failed:" + reason;
        }
        String prefix = switch (mobilityPlanType) {
            case MOVE_TO_ENTITY, FOLLOW_ENTITY -> "follow_failed:";
            case MOVE_TO_BLOCK -> "go_to_failed:";
            default -> "escape_to_anchor_failed:";
        };
        return prefix + reason;
    }

    private boolean tryAutoReplanFromWaitingForSupport(BlockPos goal) {
        if (mobilityReplanCount >= 2 || mobilityStageTicks <= MOBILITY_STAGE_TIMEOUT_TICKS) {
            return false;
        }
        markMobilityReplan("waiting_for_support_replan");
        mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
        mobilityPlannerStage = MobilityPlannerStage.EVALUATING;
        mobilityAction = MobilityAction.NONE;
        mobilityFallback = MobilityFallback.DIRECT_WALK;
        mobilityTarget = goal == null ? null : goal.immutable();
        mobilitySupportTarget = null;
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        mobilityBlockedBy = null;
        mobilityBlockedReason = "";
        mobilityLastActionResult = "waiting_replan";
        mobilityDetail = "waiting_for_support_replan:" + formatPos(goal);
        taskDetail = mobilityDetail;
        return true;
    }

    private boolean tryAutoReplanFromStepStuck(BlockPos goal, StairStep step, String context) {
        if (mobilityReplanCount >= 2) {
            return false;
        }
        markMobilityReplan(context.equals("single_step")
                ? "single_step_stuck_replan"
                : context + "_stuck_replan");
        mobilityTarget = goal == null
                ? (step == null ? null : step.feet())
                : goal.immutable();
        mobilitySupportTarget = null;
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        mobilityBlockedBy = null;
        mobilityBlockedReason = "";
        mobilityLastActionResult = context.equals("single_step") ? "single_step_replan" : "step_replan";
        mobilityDetail = context.equals("single_step")
                ? "single_step_stuck_replan:" + formatPos(mobilityTarget)
                : "step_stuck_replan:" + formatPos(mobilityTarget);
        taskDetail = mobilityDetail;
        return true;
    }

    private void markMobilityReplan(String reason) {
        mobilityReplanCount++;
        mobilityReplanReason = reason == null ? "unknown" : reason;
        mobilityPlannerStage = MobilityPlannerStage.EVALUATING;
        mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
        mobilityAction = MobilityAction.NONE;
        mobilityFallback = MobilityFallback.DIRECT_WALK;
        mobilityStageTicks = 0;
        mobilityStuckTicks = 0;
        lastMobilityProgressPos = null;
        mobilityLastActionResult = "replan";
    }

    private boolean shouldUseVerticalAscend(ServerLevel level, AliceEntity syna, BlockPos goal) {
        return syna != null
                && goal != null
                && goal.getY() - syna.getBlockY() >= MOBILITY_VERTICAL_ASCEND_TRIGGER
                && findPriorityMobilityStep(level, syna.blockPosition(), goal) == null;
    }

    private boolean isCenteredForVerticalAscend(AliceEntity syna, BlockPos feet) {
        if (syna == null || feet == null) {
            return false;
        }
        double dx = syna.getX() - (feet.getX() + 0.5D);
        double dz = syna.getZ() - (feet.getZ() + 0.5D);
        return dx * dx + dz * dz <= 0.04D;
    }

    private boolean isCenteredForStepAscend(AliceEntity syna, BlockPos feet) {
        if (syna == null || feet == null) {
            return false;
        }
        double dx = syna.getX() - (feet.getX() + 0.5D);
        double dz = syna.getZ() - (feet.getZ() + 0.5D);
        return dx * dx + dz * dz <= MOBILITY_STEP_CENTER_TOLERANCE_SQR;
    }

    private void lockMobilitySupportTarget(BlockPos pos) {
        if (pos == null) {
            return;
        }
        mobilityLockedSupportPos = pos.immutable();
        mobilityLockedSupportUntilPlanTick = Math.max(mobilityLockedSupportUntilPlanTick, mobilityPlanTicks + MOBILITY_SUPPORT_LOCK_TICKS);
    }

    private boolean isMobilitySupportLocked(AliceEntity syna, BlockPos pos) {
        if (pos == null || mobilityLockedSupportPos == null || !mobilityLockedSupportPos.equals(pos)) {
            return false;
        }
        if (mobilityPlanTicks > mobilityLockedSupportUntilPlanTick) {
            mobilityLockedSupportPos = null;
            mobilityLockedSupportUntilPlanTick = 0;
            return false;
        }
        if (syna != null && syna.getBlockY() > pos.getY()) {
            mobilityLockedSupportPos = null;
            mobilityLockedSupportUntilPlanTick = 0;
            return false;
        }
        return true;
    }

    private MobilityTickResult alignForStepAscend(AliceEntity syna, StairStep step, String context) {
        if (syna == null || step == null || !step.ascending()) {
            return null;
        }
        BlockPos feet = syna.blockPosition();
        if (isCenteredForStepAscend(syna, feet)) {
            return null;
        }
        syna.getNavigation().moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.9D);
        mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
        mobilityPlannerStage = MobilityPlannerStage.MOVING_STEP;
        mobilityAction = MobilityAction.WALK;
        mobilityFallback = MobilityFallback.DIG_UP_STAIR;
        mobilityTarget = feet.immutable();
        mobilitySupportTarget = null;
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        mobilityLastActionResult = stepCenteringActionResult(context);
        mobilityDetail = stepCenteringDetail(context, feet, step);
        taskDetail = mobilityDetail;
        if (mobilityStageTicks > MOBILITY_STAGE_TIMEOUT_TICKS) {
            mobilityLastActionResult = stepCenteringActionResult(context) + "_bypass";
            mobilityDetail = mobilityLastActionResult + ":" + formatPos(feet);
            taskDetail = mobilityDetail;
            BridgeState.get().setLastEvent("mobility_step_center_bypass:" + context + ":" + formatPos(feet));
            return null;
        }
        return MobilityTickResult.RUNNING;
    }

    private MobilityTickResult alignForVerticalAscend(AliceEntity syna, BlockPos feet) {
        if (syna == null || feet == null) {
            return MobilityTickResult.FAILED;
        }
        if (isCenteredForVerticalAscend(syna, feet)) {
            return null;
        }
        syna.getNavigation().moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.9D);
        mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
        mobilityPlannerStage = MobilityPlannerStage.MOVING_STEP;
        mobilityAction = MobilityAction.WALK;
        mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
        mobilityTarget = feet.immutable();
        mobilitySupportTarget = feet.immutable();
        mobilityLastActionResult = "vertical_centering";
        mobilityDetail = "vertical_ascend_centering:" + formatPos(feet);
        taskDetail = mobilityDetail;
        if (mobilityStageTicks > MOBILITY_STAGE_TIMEOUT_TICKS) {
            mobilityLastFailure = mobilityFailure("vertical_center_timeout");
            return MobilityTickResult.FAILED;
        }
        return MobilityTickResult.RUNNING;
    }

    private MobilityTickResult tickSingleStepAscend(ServerLevel level, AliceEntity syna, BlockPos goal) {
        if (level == null || syna == null || goal == null) {
            return null;
        }
        if (goal.getY() - syna.getBlockY() != 1) {
            return null;
        }

        StairStep step = findSingleStepAscendStep(level, syna.blockPosition(), goal);
        if (step == null) {
            return null;
        }
        return executeMobilityStep(level, syna, goal, step, "single_step");
    }

    private MobilityTickResult tickVerticalAscend(ServerLevel level, AliceEntity syna, BlockPos goal) {
        if (level == null || syna == null || goal == null) {
            mobilityLastFailure = mobilityFailure("vertical_invalid");
            return MobilityTickResult.FAILED;
        }

        if (syna.getBlockY() >= goal.getY()) {
            mobilitySupportPlacePhase = 0;
            return MobilityTickResult.RUNNING;
        }

        BlockPos feet = syna.blockPosition();
        MobilityTickResult alignResult = alignForVerticalAscend(syna, feet);
        if (alignResult != null) {
            return alignResult;
        }

        MobilityTickResult fusionResult = tryExitVerticalAscend(level, syna, goal);
        if (fusionResult != null) {
            return fusionResult;
        }

        MobilityTickResult headroomResult = clearVerticalHeadroom(level, syna, feet);
        if (headroomResult != null) {
            return headroomResult;
        }

        fusionResult = tryExitVerticalAscend(level, syna, goal);
        if (fusionResult != null) {
            return fusionResult;
        }

        int availableSupportBlocks = countSupportBlocks(syna.getInventory());
        if (availableSupportBlocks <= 0) {
            enterWaitingForSupport(feet, "vertical_ascend_waiting_for_support");
            return MobilityTickResult.RUNNING;
        }

        mobilityMode = MobilityMode.PLACING_SUPPORT;
        mobilityPlannerStage = MobilityPlannerStage.PLACING_SUPPORT;
        mobilityAction = MobilityAction.PLACE_SUPPORT;
        mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
        mobilityTarget = feet.immutable();
        mobilitySupportTarget = feet.immutable();
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;

        if (syna.onGround()) {
            syna.getNavigation().stop();
            performMobilityJump(syna);
            mobilitySupportPlacePhase = 1;
            mobilityVerticalPlaceDelayTicks = Math.max(mobilityVerticalPlaceDelayTicks, 1);
            mobilityLastActionResult = "vertical_jump";
            mobilityDetail = "vertical_ascend_jump:" + formatPos(feet);
            taskDetail = mobilityDetail;
            return MobilityTickResult.RUNNING;
        }

        if (mobilityVerticalPlaceDelayTicks > 0) {
            mobilityVerticalPlaceDelayTicks--;
            mobilityLastActionResult = "vertical_wait_post_jump";
            mobilityDetail = "vertical_ascend_wait_post_jump:" + formatPos(feet);
            taskDetail = mobilityDetail;
            return MobilityTickResult.RUNNING;
        }

        if (!isSafeToPlaceVerticalSupport(syna, feet)) {
            if (syna.getDeltaMovement().y <= 0.0D) {
                mobilitySupportPlacePhase = 0;
                mobilityLastActionResult = "vertical_jump_aborted";
                mobilityDetail = "vertical_ascend_retry_jump:" + formatPos(feet);
                taskDetail = mobilityDetail;
            } else {
                mobilityLastActionResult = "vertical_wait_clearance";
                mobilityDetail = "vertical_ascend_wait_clearance:" + formatPos(feet);
                taskDetail = mobilityDetail;
            }
            return MobilityTickResult.RUNNING;
        }

        SupportPlacementResult placementResult = placeSupportBlock(level, syna, feet);
        if (placementResult == SupportPlacementResult.PLACED) {
            mobilitySupportPlacePhase = 0;
            mobilityVerticalPlaceDelayTicks = 0;
            mobilityLastActionResult = "vertical_support_placed";
            mobilityDetail = "vertical_ascend_support:" + formatPos(feet);
            taskDetail = mobilityDetail;
            BridgeState.get().addDebug("mobility_vertical_support pos=" + formatPos(feet) + ",goal=" + formatPos(goal));
            return MobilityTickResult.RUNNING;
        }

        if (placementResult == SupportPlacementResult.BLOCKED
                && mobilityLastFailure != null && !mobilityLastFailure.isBlank()) {
            return MobilityTickResult.FAILED;
        }

        if (placementResult == SupportPlacementResult.WAITING_FOR_SUPPORT) {
            return MobilityTickResult.RUNNING;
        }

        mobilityLastActionResult = "vertical_wait_place";
        mobilityDetail = "vertical_ascend_wait_place:" + formatPos(feet);
        taskDetail = mobilityDetail;
        return MobilityTickResult.RUNNING;
    }

    private boolean isSafeToPlaceVerticalSupport(AliceEntity syna, BlockPos targetPos) {
        if (syna == null || targetPos == null) {
            return false;
        }
        if (syna.getDeltaMovement().y <= 0.08D) {
            return false;
        }
        AABB entityBox = syna.getBoundingBox();
        double minSafeFeetY = targetPos.getY() + 0.55D;
        if (entityBox.minY < minSafeFeetY) {
            return false;
        }

        AABB supportBox = new AABB(targetPos);
        return entityBox.maxY > supportBox.maxY;
    }

    private MobilityTickResult clearVerticalHeadroom(ServerLevel level, AliceEntity syna, BlockPos feet) {
        BlockPos[] clearTargets = new BlockPos[] {feet.above(), feet.above(2), feet.above(3)};
        for (BlockPos target : clearTargets) {
            BlockState state = level.getBlockState(target);
            if (state.isAir() || state.canBeReplaced()) {
                continue;
            }
            mobilityMode = MobilityMode.ASCENDING_STAIR;
            mobilityPlannerStage = MobilityPlannerStage.PREPARING_STAIR;
            mobilityAction = MobilityAction.DIG_STAIR;
            mobilityFallback = MobilityFallback.DIG_UP_STAIR;
            mobilityTarget = target.immutable();
            mobilityDigTarget = target.immutable();
            mobilityDigHeadTarget = null;
            if (!clearMobilityBlock(level, syna, target)) {
                mobilityLastFailure = mobilityFailure(mobilityBlockedReason == null || mobilityBlockedReason.isBlank()
                        ? "vertical_head_blocked"
                        : mobilityBlockedReason);
                return MobilityTickResult.FAILED;
            }
            mobilityLastActionResult = "vertical_headroom_cleared";
            mobilityDetail = "vertical_clear_headroom:" + formatPos(target);
            taskDetail = mobilityDetail;
            BridgeState.get().addDebug("mobility_vertical_clear pos=" + formatPos(target));
            return MobilityTickResult.RUNNING;
        }
        return null;
    }

    private MobilityTickResult tryExitVerticalAscend(ServerLevel level, AliceEntity syna, BlockPos goal) {
        if (level == null || syna == null || goal == null) {
            return null;
        }
        if (tryDirectMobilityPath(syna, goal)) {
            markMobilityReplan("vertical_exit_direct_path");
            mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
            mobilityPlannerStage = MobilityPlannerStage.DIRECT_PATH;
            mobilityAction = MobilityAction.WALK;
            mobilityFallback = MobilityFallback.DIRECT_WALK;
            mobilityTarget = goal.immutable();
            mobilitySupportTarget = null;
            mobilityLastActionResult = "vertical_exit_direct";
            mobilityDetail = "vertical_exit_to_direct:" + formatPos(goal);
            taskDetail = mobilityDetail;
            return MobilityTickResult.RUNNING;
        }

        StairStep step = findPriorityMobilityStep(level, syna.blockPosition(), goal);
        if (step == null) {
            return null;
        }
        markMobilityReplan("vertical_exit_step");
        MobilityTickResult result = executeMobilityStep(level, syna, goal, step, "vertical_exit");
        if (result == MobilityTickResult.RUNNING) {
            BridgeState.get().addDebug("mobility_vertical_exit_step target=" + formatPos(step.feet()) + ",goal=" + formatPos(goal));
        }
        return result;
    }

    private StairStep findPriorityMobilityStep(ServerLevel level, BlockPos from, BlockPos goal) {
        if (level == null || from == null || goal == null || from.getY() >= goal.getY()) {
            return null;
        }
        StairStep singleStep = findSingleStepAscendStep(level, from, goal);
        if (singleStep != null) {
            return singleStep;
        }
        StairStep stairStep = findBestAscendStep(level, from, goal);
        if (stairStep == null) {
            return null;
        }
        return stairStep.feet().getY() > from.getY() ? stairStep : null;
    }

    private MobilityTickResult executeMobilityStep(ServerLevel level, AliceEntity syna, BlockPos goal, StairStep step, String context) {
        if (level == null || syna == null || goal == null || step == null) {
            return MobilityTickResult.FAILED;
        }

        MobilityTickResult alignResult = alignForStepAscend(syna, step, context);
        if (alignResult != null) {
            return alignResult;
        }

        mobilityPlannerStage = MobilityPlannerStage.PREPARING_STAIR;
        mobilityAction = MobilityAction.DIG_STAIR;
        mobilityFallback = MobilityFallback.DIG_UP_STAIR;
        mobilityTarget = step.feet();
        mobilityDetail = stepPrepareDetail(context, step);
        taskDetail = mobilityDetail;

        if (!ensureStepPrepared(level, syna, step)) {
            if (mobilityPlannerStage == MobilityPlannerStage.BLOCKED_BY_PROTECTION) {
                if (tryProtectedAscendFallback(level, syna, goal, mobilityProtectedTarget)) {
                    return MobilityTickResult.RUNNING;
                }
                mobilityLastFailure = mobilityFailure("protected_structure");
                return MobilityTickResult.FAILED;
            }
            if (mobilityPlannerStage == MobilityPlannerStage.WAITING_FOR_SUPPORT) {
                mobilityStageTicks = 0;
                mobilityLastActionResult = "waiting_for_support";
                return MobilityTickResult.RUNNING;
            }
            if (mobilityLastFailure != null && !mobilityLastFailure.isBlank()) {
                return MobilityTickResult.FAILED;
            }
            if (mobilityStageTicks > MOBILITY_STAGE_TIMEOUT_TICKS) {
                mobilityLastFailure = mobilityFailure(context.equals("single_step") ? "single_step_prepare_timeout" : "step_prepare_timeout");
                return MobilityTickResult.FAILED;
            }
            mobilityLastActionResult = context.equals("single_step") ? "single_step_prepare_pending" : "prepare_pending";
            return MobilityTickResult.RUNNING;
        }

        syna.getNavigation().moveTo(step.feet().getX() + 0.5D, step.feet().getY(), step.feet().getZ() + 0.5D, 1.0D);
        mobilityTarget = step.feet();
        mobilityMode = step.feet().getY() > syna.getBlockY() ? MobilityMode.ASCENDING_STAIR : MobilityMode.PATHING_TO_ANCHOR;
        mobilityPlannerStage = MobilityPlannerStage.MOVING_STEP;
        mobilityAction = mobilitySupportTarget != null ? MobilityAction.PLACE_SUPPORT : MobilityAction.DIG_STAIR;
        mobilityFallback = mobilitySupportTarget != null ? MobilityFallback.SUPPORT_BLOCK : MobilityFallback.DIG_UP_STAIR;
        mobilityLastActionResult = stepMoveActionResult(context);
        mobilityDetail = stepMoveDetail(level, step, context);
        taskDetail = mobilityDetail;

        if (shouldForceStepJump(syna, step)) {
            performMobilityJump(syna);
            mobilityDetail = stepJumpDetail(context, step);
            taskDetail = mobilityDetail;
            mobilityLastActionResult = stepJumpActionResult(context);
        }

        if (syna.blockPosition().closerThan(step.feet(), 1.5D)) {
            mobilityStageTicks = 0;
        }

        if (mobilityStuckTicks > 20 || mobilityStageTicks > MOBILITY_STAGE_TIMEOUT_TICKS) {
            if (tryAutoReplanFromStepStuck(goal, step, context)) {
                return MobilityTickResult.RUNNING;
            }
        }

        if (mobilityStuckTicks > 40 || mobilityStageTicks > MOBILITY_STAGE_TIMEOUT_TICKS * 2) {
            mobilityLastFailure = mobilityFailure(context.equals("single_step") ? "single_step_stuck" : "stuck");
            return MobilityTickResult.FAILED;
        }
        return MobilityTickResult.RUNNING;
    }

    private String stepPrepareDetail(String context, StairStep step) {
        return switch (context) {
            case "single_step" -> "single_step_prepare:" + formatPos(step.feet());
            case "local" -> "local_step_prepare:" + formatPos(step.feet());
            case "vertical_exit" -> "vertical_exit_step_prepare:" + formatPos(step.feet());
            default -> "stair_prepare:" + formatPos(step.feet());
        };
    }

    private String stepMoveDetail(ServerLevel level, StairStep step, String context) {
        if ("single_step".equals(context) && step.floor().equals(step.feet().below()) && level.getBlockState(step.floor()).isAir()) {
            return "single_step_support_ready:" + formatPos(step.floor());
        }
        return switch (context) {
            case "single_step" -> "single_step_ascend:" + formatPos(step.feet());
            case "local" -> "local_step_move:" + formatPos(step.feet());
            case "vertical_exit" -> "vertical_exit_to_stair:" + formatPos(step.feet());
            default -> ((mobilityPlanType == MobilityPlanType.MOVE_TO_ENTITY || mobilityPlanType == MobilityPlanType.FOLLOW_ENTITY)
                    ? "following_via_stair:" : "ascending_stair:") + formatPos(step.feet());
        };
    }

    private String stepJumpDetail(String context, StairStep step) {
        return switch (context) {
            case "single_step" -> "single_step_jump:" + formatPos(step.feet());
            case "local" -> "local_step_jump:" + formatPos(step.feet());
            case "vertical_exit" -> "vertical_exit_jump:" + formatPos(step.feet());
            default -> "jumping_up_step:" + formatPos(step.feet());
        };
    }

    private String stepCenteringDetail(String context, BlockPos currentFeet, StairStep step) {
        return switch (context) {
            case "single_step" -> "single_step_centering:" + formatPos(currentFeet);
            case "local" -> "local_step_centering:" + formatPos(currentFeet);
            case "vertical_exit" -> "vertical_exit_centering:" + formatPos(currentFeet);
            default -> "step_centering:" + formatPos(step == null ? currentFeet : step.feet());
        };
    }

    private String stepMoveActionResult(String context) {
        return switch (context) {
            case "single_step" -> mobilitySupportTarget != null ? "single_step_support_ready" : "single_step_move";
            case "local" -> mobilitySupportTarget != null ? "local_step_support_ready" : "local_step_move";
            case "vertical_exit" -> mobilitySupportTarget != null ? "vertical_exit_support_ready" : "vertical_exit_move";
            default -> mobilitySupportTarget != null ? "support_ready" : "stair_ready";
        };
    }

    private String stepCenteringActionResult(String context) {
        return switch (context) {
            case "single_step" -> "single_step_centering";
            case "local" -> "local_step_centering";
            case "vertical_exit" -> "vertical_exit_centering";
            default -> "step_centering";
        };
    }

    private String stepJumpActionResult(String context) {
        return switch (context) {
            case "single_step" -> "single_step_jump";
            case "local" -> "local_step_jump";
            case "vertical_exit" -> "vertical_exit_jump";
            default -> "step_jump";
        };
    }

    private boolean canAttemptDirectMobilityPath(AliceEntity syna, BlockPos goal) {
        if (goal == null) {
            return false;
        }
        boolean needsAscend = syna.getBlockY() + 1 < goal.getY();
        return !needsAscend && mobilityStuckTicks < 18;
    }

    private boolean tryDirectMobilityPath(AliceEntity syna, BlockPos goal) {
        if (!canAttemptDirectMobilityPath(syna, goal)) {
            return false;
        }
        if ((mobilityPlanType == MobilityPlanType.MOVE_TO_ENTITY || mobilityPlanType == MobilityPlanType.FOLLOW_ENTITY)
                && horizontalDistanceSqr(syna.position(), Vec3.atCenterOf(goal)) <= 6.25D) {
            return syna.getNavigation().moveTo(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D, 1.1D);
        }
        return syna.getNavigation().moveTo(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D, 1.0D);
    }

    private boolean tryPathDirectlyToAnchor(AliceEntity syna, BlockPos anchor) {
        return tryDirectMobilityPath(syna, anchor);
    }

    private void updateMobilityStuckState(AliceEntity syna) {
        if (lastMobilityProgressPos == null) {
            lastMobilityProgressPos = syna.position();
            mobilityStuckTicks = 0;
            return;
        }
        if (syna.position().distanceToSqr(lastMobilityProgressPos) < 0.04D) {
            mobilityStuckTicks++;
        } else {
            lastMobilityProgressPos = syna.position();
            mobilityStuckTicks = 0;
        }
    }

    private record StairStep(BlockPos feet, BlockPos floor, Direction direction, boolean ascending) {}

    private StairStep findSingleStepAscendStep(ServerLevel level, BlockPos from, BlockPos goal) {
        if (level == null || from == null || goal == null || goal.getY() - from.getY() != 1) {
            return null;
        }
        Direction[] directions = getPreferredMobilityDirections(from, goal);
        for (Direction direction : directions) {
            if (direction == null || !direction.getAxis().isHorizontal()) {
                continue;
            }
            BlockPos feet = from.relative(direction).above();
            BlockPos floor = feet.below();
            if (isUnsafeSupportBlock(level.getBlockState(floor))) {
                continue;
            }
            if (isProtectedArtificialBlock(level.getBlockState(feet))
                    || isProtectedArtificialBlock(level.getBlockState(feet.above()))) {
                continue;
            }
            if (!canPrepareStep(level, feet, floor)) {
                continue;
            }
            return new StairStep(feet.immutable(), floor.immutable(), direction, true);
        }
        return null;
    }

    private StairStep findBestAscendStep(ServerLevel level, BlockPos from, BlockPos anchor) {
        Direction[] directions = getPreferredMobilityDirections(from, anchor);
        boolean needAscend = anchor != null && from.getY() < anchor.getY();
        for (Direction direction : directions) {
            if (direction == null || !direction.getAxis().isHorizontal()) {
                continue;
            }
            BlockPos feet = needAscend ? from.relative(direction).above() : from.relative(direction);
            BlockPos floor = feet.below();
            if (isUnsafeSupportBlock(level.getBlockState(floor))) {
                continue;
            }
            if (needAscend && isProtectedArtificialBlock(level.getBlockState(feet))) {
                continue;
            }
            if (needAscend && isProtectedArtificialBlock(level.getBlockState(feet.above()))) {
                continue;
            }
            if (!canPrepareStep(level, feet, floor)) {
                continue;
            }
            return new StairStep(feet.immutable(), floor.immutable(), direction, needAscend);
        }
        return null;
    }

    private MobilityTickResult tryLineCorridorFallback(ServerLevel level, AliceEntity syna, BlockPos goal) {
        if (level == null || syna == null || goal == null) {
            return null;
        }
        BlockPos from = syna.blockPosition();
        Direction direction = chooseLineCorridorDirection(level, from, goal);
        if (direction == null) {
            return null;
        }

        BlockPos feet = from.relative(direction);
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        BlockState floorState = level.getBlockState(floor);
        if (isUnsafeSupportBlock(floorState)) {
            mobilityBlockedBy = floor.immutable();
            mobilityBlockedReason = "unsafe_corridor_floor";
            mobilityLastActionResult = "line_corridor_blocked";
            return null;
        }

        mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
        mobilityPlannerStage = MobilityPlannerStage.PREPARING_STAIR;
        mobilityAction = MobilityAction.DIG_STAIR;
        mobilityFallback = MobilityFallback.DIG_UP_STAIR;
        mobilityTarget = feet.immutable();
        mobilitySupportTarget = null;
        mobilityDigTarget = feet.immutable();
        mobilityDigHeadTarget = head.immutable();

        if (!clearMobilityBlock(level, syna, feet)) {
            if (mobilityPlannerStage == MobilityPlannerStage.BLOCKED_BY_PROTECTION) {
                return null;
            }
            mobilityLastFailure = mobilityFailure(mobilityBlockedReason == null || mobilityBlockedReason.isBlank()
                    ? "line_corridor_feet_blocked"
                    : mobilityBlockedReason);
            return MobilityTickResult.FAILED;
        }
        if (!clearMobilityBlock(level, syna, head)) {
            if (mobilityPlannerStage == MobilityPlannerStage.BLOCKED_BY_PROTECTION) {
                return null;
            }
            mobilityLastFailure = mobilityFailure(mobilityBlockedReason == null || mobilityBlockedReason.isBlank()
                    ? "line_corridor_head_blocked"
                    : mobilityBlockedReason);
            return MobilityTickResult.FAILED;
        }

        if (floorState.isAir() || floorState.canBeReplaced()) {
            mobilityMode = MobilityMode.PLACING_SUPPORT;
            mobilityPlannerStage = MobilityPlannerStage.PLACING_SUPPORT;
            mobilityAction = MobilityAction.PLACE_SUPPORT;
            mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
            mobilitySupportTarget = floor.immutable();
            mobilityDigTarget = null;
            mobilityDigHeadTarget = null;
            mobilityLastActionResult = "line_corridor_place_floor";
            mobilityDetail = "line_corridor_place_floor:" + formatPos(floor);
            taskDetail = mobilityDetail;
            SupportPlacementResult placementResult = placeSupportBlock(level, syna, floor);
            if (placementResult == SupportPlacementResult.BLOCKED
                    && mobilityLastFailure != null && !mobilityLastFailure.isBlank()) {
                return MobilityTickResult.FAILED;
            }
            return MobilityTickResult.RUNNING;
        }

        syna.getNavigation().moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 1.0D);
        mobilityPlannerStage = MobilityPlannerStage.MOVING_STEP;
        mobilityAction = MobilityAction.WALK;
        mobilityFallback = MobilityFallback.DIG_UP_STAIR;
        mobilitySupportTarget = null;
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        mobilityLastActionResult = "line_corridor_move";
        mobilityDetail = "line_corridor_move:" + formatPos(feet) + ":goal=" + formatPos(goal);
        taskDetail = mobilityDetail;
        BridgeState.get().addDebug("mobility_line_corridor from=" + formatPos(from)
                + ",next=" + formatPos(feet)
                + ",goal=" + formatPos(goal));
        return MobilityTickResult.RUNNING;
    }

    private Direction chooseLineCorridorDirection(ServerLevel level, BlockPos from, BlockPos goal) {
        Direction[] directions = getPreferredMobilityDirections(from, goal);
        for (Direction direction : directions) {
            if (direction == null || !direction.getAxis().isHorizontal()) {
                continue;
            }
            BlockPos feet = from.relative(direction);
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(feet.above());
            BlockState floorState = level.getBlockState(feet.below());
            if (isUnsafeSupportBlock(floorState)) {
                continue;
            }
            if (isProtectedArtificialBlock(feetState) || isProtectedArtificialBlock(headState)) {
                continue;
            }
            if (!isBreakableForMobility(feetState) || !isBreakableForMobility(headState)) {
                continue;
            }
            return direction;
        }
        return null;
    }

    private Direction[] getPreferredMobilityDirections(BlockPos from, BlockPos goal) {
        if (from == null || goal == null) {
            return Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);
        }
        int dx = goal.getX() - from.getX();
        int dz = goal.getZ() - from.getZ();
        Direction primary;
        Direction secondary;
        if (Math.abs(dx) >= Math.abs(dz)) {
            primary = dx >= 0 ? Direction.EAST : Direction.WEST;
            secondary = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        } else {
            primary = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
            secondary = dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        Direction tertiary = primary.getClockWise();
        Direction quaternary = primary.getCounterClockWise();
        return new Direction[] {primary, secondary, tertiary, quaternary};
    }

    private boolean canPrepareStep(ServerLevel level, BlockPos feet, BlockPos floor) {
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        BlockState floorState = level.getBlockState(floor);
        return isBreakableForMobility(feetState)
                && isBreakableForMobility(headState)
                && !isUnsafeSupportBlock(floorState);
    }

    private boolean ensureStepPrepared(ServerLevel level, AliceEntity syna, StairStep step) {
        if (step == null) {
            return false;
        }

        if (isMobilitySupportLocked(syna, step.floor())) {
            mobilityLastActionResult = "step_support_lock_wait";
            mobilityDetail = "step_support_lock_wait:" + formatPos(mobilityLockedSupportPos);
            taskDetail = mobilityDetail;
            return false;
        }

        mobilityDigTarget = step.feet();
        if (!clearMobilityBlock(level, syna, step.feet())) {
            return false;
        }

        BlockState headState = level.getBlockState(step.feet().above());
        if (!headState.isAir() && !headState.canBeReplaced()) {
            mobilityMode = MobilityMode.ASCENDING_STAIR;
            mobilityPlannerStage = MobilityPlannerStage.PREPARING_STAIR;
            mobilityAction = MobilityAction.DIG_STAIR;
            mobilityFallback = MobilityFallback.DIG_UP_STAIR;
            mobilityTarget = step.feet().immutable();
            mobilityDigTarget = step.feet().immutable();
            mobilityDigHeadTarget = step.feet().above().immutable();
            mobilityDetail = "step_up_clear_head:" + formatPos(step.feet().above());
            taskDetail = mobilityDetail;
            mobilityLastActionResult = "step_up_clear_head";
        }
        mobilityDigHeadTarget = step.feet().above();
        if (!clearMobilityBlock(level, syna, step.feet().above())) {
            return false;
        }

        if (level.getBlockState(step.floor()).isAir()) {
            mobilityMode = MobilityMode.PLACING_SUPPORT;
            mobilityPlannerStage = MobilityPlannerStage.PLACING_SUPPORT;
            mobilityAction = MobilityAction.PLACE_SUPPORT;
            mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
            mobilitySupportTarget = step.floor();
            if (mobilitySupportPlacePhase == 0) {
                mobilitySupportPlacePhase = 1;
            }
            mobilityDetail = "step_up_place:" + formatPos(step.floor());
            taskDetail = mobilityDetail;
            mobilityLastActionResult = "step_up_place";
            SupportPlacementResult placementResult = placeSupportBlock(level, syna, step.floor());
            return placementResult == SupportPlacementResult.PLACED;
        }

        mobilitySupportTarget = null;
        mobilitySupportPlacePhase = 0;
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        return true;
    }

    private boolean clearMobilityBlock(ServerLevel level, AliceEntity syna, BlockPos pos) {
        if (isMobilitySupportLocked(syna, pos)) {
            mobilityBlockedBy = pos.immutable();
            mobilityBlockedReason = "locked_support_target";
            mobilityLastActionResult = "clear_locked_support_skip";
            mobilityDetail = "locked_support_wait:" + formatPos(pos);
            taskDetail = mobilityDetail;
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced()) {
            mobilityLastActionResult = "clear_skip";
            return true;
        }
        if (isProtectedArtificialBlock(state)) {
            mobilityProtectedTarget = pos.immutable();
            mobilityBlockedBy = pos.immutable();
            mobilityBlockedReason = "protected_structure";
            mobilityPlannerStage = MobilityPlannerStage.BLOCKED_BY_PROTECTION;
            mobilityDetail = "blocked_by_protection:" + formatPos(pos);
            taskDetail = mobilityDetail;
            BridgeState.get().addDebug("mobility_protected_block pos=" + formatPos(pos) + ",block=" + state.getBlock());
            return false;
        }
        if (!isBreakableForMobility(state)) {
            mobilityBlockedBy = pos.immutable();
            mobilityBlockedReason = "unbreakable_block";
            mobilityLastActionResult = "clear_blocked";
            return false;
        }
        mobilityProtectedTarget = null;
        lookAtBlock(syna, pos);
        syna.setMiningSwing(true);
        level.destroyBlockProgress(syna.getId(), pos, 8);
        boolean destroyed = level.destroyBlock(pos, true, syna);
        level.destroyBlockProgress(syna.getId(), pos, -1);
        syna.setMiningSwing(false);
        if (destroyed) {
            mobilityBlocksBroken++;
            mobilityLastActionResult = "block_cleared";
            BridgeState.get().addDebug("mobility_break pos=" + formatPos(pos) + ",count=" + mobilityBlocksBroken);
        } else {
            mobilityBlockedBy = pos.immutable();
            mobilityBlockedReason = "destroy_failed";
            mobilityLastActionResult = "clear_failed";
        }
        return destroyed;
    }

    private boolean tryProtectedAscendFallback(ServerLevel level, AliceEntity syna, BlockPos goal, BlockPos blockedPos) {
        if (level == null || syna == null || goal == null) {
            return false;
        }
        if (syna.getBlockY() >= goal.getY()) {
            return false;
        }

        StairStep alternative = findUnprotectedAscendStep(level, syna.blockPosition(), goal);
        if (alternative != null) {
            markMobilityReplan("protected_step_reroute");
            mobilityTarget = alternative.feet();
            mobilityDetail = "rerouting_around_protected_step:" + formatPos(alternative.feet());
            taskDetail = mobilityDetail;
            mobilityLastActionResult = "protected_reroute";
            BridgeState.get().addDebug("mobility_protected_reroute blocked="
                    + (blockedPos == null ? "none" : formatPos(blockedPos))
                    + ",alt=" + formatPos(alternative.feet()));
            return true;
        }

        int availableSupportBlocks = countSupportBlocks(syna.getInventory());
        if (availableSupportBlocks <= 0) {
            return false;
        }

        BlockPos pillarPos = syna.blockPosition();
        BlockState pillarState = level.getBlockState(pillarPos);
        if (!pillarState.isAir() && !pillarState.canBeReplaced()) {
            return false;
        }

        mobilityMode = MobilityMode.PLACING_SUPPORT;
        mobilityPlannerStage = MobilityPlannerStage.PLACING_SUPPORT;
        mobilityAction = MobilityAction.PLACE_SUPPORT;
        mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
        mobilitySupportTarget = pillarPos.immutable();
        mobilityDigTarget = null;
        mobilityDigHeadTarget = null;
        mobilityTarget = pillarPos.immutable();
        mobilityBlockedBy = blockedPos == null ? null : blockedPos.immutable();
        mobilityBlockedReason = "protected_structure";
        mobilityLastActionResult = "protected_pillar_fallback";
        if (mobilitySupportPlacePhase == 0) {
            mobilitySupportPlacePhase = 1;
        }
        syna.getNavigation().stop();
        performMobilityJump(syna);
        mobilityDetail = "pillaring_over_protected_block:" + formatPos(pillarPos);
        taskDetail = mobilityDetail;
        BridgeState.get().addDebug("mobility_protected_pillar blocked="
                + (blockedPos == null ? "none" : formatPos(blockedPos))
                + ",pillar=" + formatPos(pillarPos)
                + ",support=" + availableSupportBlocks);
        SupportPlacementResult placementResult = placeSupportBlock(level, syna, pillarPos);
        return placementResult == SupportPlacementResult.PLACED
                || placementResult == SupportPlacementResult.APPROACHING
                || placementResult == SupportPlacementResult.WAITING_FOR_SUPPORT;
    }

    private StairStep findUnprotectedAscendStep(ServerLevel level, BlockPos from, BlockPos anchor) {
        Direction[] directions = getPreferredMobilityDirections(from, anchor);
        for (Direction direction : directions) {
            if (direction == null || !direction.getAxis().isHorizontal()) {
                continue;
            }
            BlockPos feet = from.relative(direction).above();
            BlockPos floor = feet.below();
            if (isUnsafeSupportBlock(level.getBlockState(floor))) {
                continue;
            }
            if (isProtectedArtificialBlock(level.getBlockState(feet))
                    || isProtectedArtificialBlock(level.getBlockState(feet.above()))) {
                continue;
            }
            if (!canPrepareStep(level, feet, floor)) {
                continue;
            }
            return new StairStep(feet.immutable(), floor.immutable(), direction, true);
        }
        return null;
    }

    private boolean isProtectedArtificialBlock(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (mobilityProtectionMode == BuildingProtectionMode.OFF) {
            return false;
        }
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LOGS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.BRICKS)
                || state.is(Blocks.GLASS)
                || state.is(Blocks.GLASS_PANE)
                || state.is(Blocks.CRAFTING_TABLE)
                || state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.FURNACE)
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)
                || state.is(Blocks.LADDER)
                || state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.OAK_DOOR)
                || state.is(Blocks.SPRUCE_DOOR)
                || state.is(Blocks.BIRCH_DOOR)
                || state.is(Blocks.JUNGLE_DOOR)
                || state.is(Blocks.ACACIA_DOOR)
                || state.is(Blocks.DARK_OAK_DOOR)
                || state.is(Blocks.MANGROVE_DOOR)
                || state.is(Blocks.CHERRY_DOOR);
    }

    private boolean isBreakableForMobility(BlockState state) {
        return state.isAir() || state.canBeReplaced() || (!state.is(Blocks.BEDROCK) && !state.is(Blocks.OBSIDIAN));
    }

    private SupportPlacementResult placeSupportBlock(ServerLevel level, AliceEntity syna, BlockPos pos) {
        if (pos == null) {
            return SupportPlacementResult.BLOCKED;
        }
        BlockState existingState = level.getBlockState(pos);
        if (!existingState.isAir() && !existingState.canBeReplaced()) {
            mobilityLastActionResult = "support_already_present";
            mobilitySupportPlacePhase = 0;
            mobilitySupportTarget = pos.immutable();
            lockMobilitySupportTarget(pos);
            return SupportPlacementResult.PLACED;
        }
        Vec3 placeCenter = Vec3.atCenterOf(pos);
        double horizDist = horizontalDistanceSqr(syna.position(), placeCenter);
        if (horizDist > 2.25D) {
            BlockPos standPos = pos.above();
            syna.getNavigation().moveTo(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D, 1.0D);
            mobilityDetail = "moving_to_place_support:" + formatPos(pos);
            taskDetail = mobilityDetail;
            mobilityLastActionResult = "support_approach";
            return SupportPlacementResult.APPROACHING;
        }

        syna.getNavigation().stop();
        SynaInventory inventory = syna.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem) || !isSupportBlockAllowed(stack)) {
                continue;
            }
            if (!inventory.moveSlotToMainHand(i)) {
                continue;
            }
            ItemStack mainHandStack = inventory.getMainHandItem();
            if (mainHandStack.isEmpty() || !(mainHandStack.getItem() instanceof BlockItem mainHandBlockItem) || !isSupportBlockAllowed(mainHandStack)) {
                continue;
            }
            lookAtPosition(syna, placeCenter.add(0.0D, -0.85D, 0.0D));
            syna.swing(InteractionHand.MAIN_HAND, true);
            level.setBlockAndUpdate(pos, mainHandBlockItem.getBlock().defaultBlockState());
            mainHandStack.shrink(1);
            inventory.container().setItem(SynaInventory.MAIN_HAND_SLOT, mainHandStack.isEmpty() ? ItemStack.EMPTY : mainHandStack);
            mobilitySupportBlocksUsed++;
            mobilitySupportPlacePhase = 0;
            mobilityAction = MobilityAction.PLACE_SUPPORT;
            mobilityLastActionResult = "support_placed";
            mobilitySupportTarget = pos.immutable();
            lockMobilitySupportTarget(pos);
            BridgeState.get().addDebug("mobility_support_main_hand:item=" + mainHandBlockItem + ",slot=" + i + ",pos=" + formatPos(pos));
            BridgeState.get().setLastEvent("mobility_support_placed:" + formatPos(pos));
            return SupportPlacementResult.PLACED;
        }
        if (tryScavengeNearbySupportBlock(level, syna, pos)) {
            return SupportPlacementResult.WAITING_FOR_SUPPORT;
        }
        enterWaitingForSupport(pos, "waiting_for_support_blocks");
        return SupportPlacementResult.WAITING_FOR_SUPPORT;
    }

    private boolean tryScavengeNearbySupportBlock(ServerLevel level, AliceEntity syna, BlockPos neededSupportPos) {
        if (level == null || syna == null || countSupportBlocks(syna.getInventory()) > 0) {
            return false;
        }
        BlockPos target = findNearbyScavengeSupportBlock(level, syna.blockPosition(), neededSupportPos);
        if (target == null) {
            return false;
        }

        double distanceSqr = syna.getEyePosition().distanceToSqr(Vec3.atCenterOf(target));
        if (distanceSqr > MOBILITY_SUPPORT_SCAVENGE_RANGE_SQR) {
            BlockPos standPos = findStandableSpot(level, target);
            if (standPos == null) {
                return false;
            }
            syna.getNavigation().moveTo(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D, 1.0D);
            mobilityMode = MobilityMode.PATHING_TO_ANCHOR;
            mobilityPlannerStage = MobilityPlannerStage.WAITING_FOR_SUPPORT;
            mobilityAction = MobilityAction.WALK;
            mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
            mobilityTarget = target.immutable();
            mobilityBlockedBy = neededSupportPos == null ? null : neededSupportPos.immutable();
            mobilityBlockedReason = "scavenging_support_block";
            mobilityLastActionResult = "support_scavenge_approach";
            mobilityDetail = "support_scavenge_approach:" + formatPos(target);
            taskDetail = mobilityDetail;
            return true;
        }

        if (!harvestScavengeSupportBlock(level, syna, target)) {
            return false;
        }
        mobilityMode = MobilityMode.PLACING_SUPPORT;
        mobilityPlannerStage = MobilityPlannerStage.WAITING_FOR_SUPPORT;
        mobilityAction = MobilityAction.DIG_STAIR;
        mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
        mobilityTarget = neededSupportPos == null ? target.immutable() : neededSupportPos.immutable();
        mobilityDigTarget = target.immutable();
        mobilityBlockedBy = neededSupportPos == null ? null : neededSupportPos.immutable();
        mobilityBlockedReason = "scavenged_support_block";
        mobilityLastActionResult = "support_scavenged";
        mobilityDetail = "support_scavenged:" + formatPos(target);
        taskDetail = mobilityDetail;
        BridgeState.get().addDebug("mobility_support_scavenged pos=" + formatPos(target)
                + ",needed=" + formatPos(neededSupportPos)
                + ",available=" + countSupportBlocks(syna.getInventory()));
        return true;
    }

    private BlockPos findNearbyScavengeSupportBlock(ServerLevel level, BlockPos origin, BlockPos neededSupportPos) {
        if (level == null || origin == null) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int radius = MOBILITY_SUPPORT_SCAVENGE_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!isScavengeSupportBlock(level, pos, origin, neededSupportPos)) {
                        continue;
                    }
                    double score = origin.distSqr(pos);
                    if (neededSupportPos != null) {
                        score += neededSupportPos.distSqr(pos) * 0.15D;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean isScavengeSupportBlock(ServerLevel level, BlockPos pos, BlockPos origin, BlockPos neededSupportPos) {
        if (pos == null || pos.equals(origin) || pos.equals(origin.below()) || pos.equals(neededSupportPos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (!isNaturalSupportSourceBlock(state) || !isBreakableForMobility(state) || isUnsafeSupportBlock(state)) {
            return false;
        }
        if (isProtectedArtificialBlock(state) || !isStoneExposed(level, pos) || isHazardousStoneTarget(level, pos)) {
            return false;
        }
        return hasStandableSpot(level, pos) || origin.distSqr(pos) <= 6.0D;
    }

    private boolean harvestScavengeSupportBlock(ServerLevel level, AliceEntity syna, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isNaturalSupportSourceBlock(state)) {
            return false;
        }
        ItemStack drop = new ItemStack(state.getBlock().asItem());
        if (drop.isEmpty() || !isSupportBlockAllowed(drop) || !syna.getInventory().canAccept(drop)) {
            return false;
        }
        lookAtBlock(syna, pos);
        syna.getNavigation().stop();
        syna.setMiningSwing(true);
        level.destroyBlockProgress(syna.getId(), pos, 8);
        boolean removed = level.removeBlock(pos, false);
        level.destroyBlockProgress(syna.getId(), pos, -1);
        syna.setMiningSwing(false);
        if (!removed) {
            mobilityBlockedBy = pos.immutable();
            mobilityBlockedReason = "support_scavenge_failed";
            mobilityLastActionResult = "support_scavenge_failed";
            return false;
        }
        ItemStack remaining = syna.getInventory().insert(drop);
        if (!remaining.isEmpty()) {
            syna.spawnAtLocation(remaining);
        }
        mobilityBlocksBroken++;
        BridgeState.get().setLastEvent("mobility_support_scavenged:" + formatPos(pos));
        return true;
    }

    private String toCollectStoneFailure(String failure) {
        if (failure == null || failure.isBlank()) {
            return "collect_stone_failed:planner_failed";
        }
        if (failure.startsWith("collect_stone_failed:")) {
            return failure;
        }
        int idx = failure.indexOf(':');
        String suffix = idx >= 0 && idx + 1 < failure.length()
                ? failure.substring(idx + 1)
                : failure;
        return "collect_stone_failed:" + suffix;
    }

    private String toCollectWoodFailure(String failure) {
        if (failure == null || failure.isBlank()) {
            return "collect_wood_failed:planner_failed";
        }
        if (failure.startsWith("collect_wood_failed:")) {
            return failure;
        }
        int idx = failure.indexOf(':');
        String suffix = idx >= 0 && idx + 1 < failure.length()
                ? failure.substring(idx + 1)
                : failure;
        return "collect_wood_failed:" + suffix;
    }

    private void enterWaitingForSupport(BlockPos pos, String detailPrefix) {
        mobilityMode = MobilityMode.PLACING_SUPPORT;
        mobilityPlannerStage = MobilityPlannerStage.WAITING_FOR_SUPPORT;
        mobilityAction = MobilityAction.PLACE_SUPPORT;
        mobilityFallback = MobilityFallback.SUPPORT_BLOCK;
        mobilitySupportTarget = pos == null ? null : pos.immutable();
        mobilityBlockedBy = pos == null ? null : pos.immutable();
        mobilityBlockedReason = "no_support_blocks";
        mobilityLastFailure = "";
        mobilityLastActionResult = "waiting_for_support";
        mobilityDetail = detailPrefix + ":" + formatPos(pos);
        taskDetail = mobilityDetail;
    }

    private BlockPos findBestFollowStandPos(ServerLevel level, AliceEntity syna, ServerPlayer player) {
        if (level == null || syna == null || player == null) {
            return null;
        }
        BlockPos base = player.blockPosition();
        Direction preferredBack = Direction.fromYRot(player.getYRot()).getOpposite();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos candidate = base.offset(dx, dy, dz);
                    if (!isStandable(level, candidate)) {
                        continue;
                    }
                    double distToPlayer = candidate.distSqr(base);
                    if (distToPlayer < 2.0D || distToPlayer > 16.0D) {
                        continue;
                    }
                    double heightPenalty = Math.abs(candidate.getY() - base.getY()) * 4.0D;
                    double synaPenalty = syna.blockPosition().distSqr(candidate) * 0.35D;
                    Direction dir = approximateHorizontalDirection(base, candidate);
                    double formationBonus = dir == preferredBack ? -3.0D : (dir != null && dir.getAxis().isHorizontal() ? -1.0D : 0.0D);
                    double score = distToPlayer + heightPenalty + synaPenalty + formationBonus;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    private Direction approximateHorizontalDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        if (Math.abs(dz) > 0) {
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    private int countSupportBlocks(SynaInventory inventory) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isSupportBlockAllowed(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isSupportBlockAllowed(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.STONE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.OAK_PLANKS)
                || state.is(Blocks.SPRUCE_PLANKS)
                || state.is(Blocks.BIRCH_PLANKS)
                || state.is(BlockTags.LOGS);
    }

    private boolean isNaturalSupportSourceBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.STONE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT);
    }

    private boolean isUnsafeSupportBlock(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.getFluidState().isSource();
    }

    private BlockPos findNearestStoneBlock(ServerLevel level, BlockPos origin) {
        return findNearestStoneBlock(level, origin, stoneSearchRadius <= 0 ? STONE_SEARCH_RADIUS : stoneSearchRadius);
    }

    private BlockPos findNearestStoneBlock(ServerLevel level, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -12; dy <= 8; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!isValidStoneTarget(level, pos)) {
                        continue;
                    }
                    if (!hasStandableSpot(level, pos)) {
                        continue;
                    }
                    double score = scoreStoneTarget(level, origin, pos);
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private ItemEntity findNearestStoneDrop(ServerLevel level, Vec3 origin, BlockPos nearPos) {
        AABB searchBox = new AABB(origin, origin).inflate(8.0D, 6.0D, 8.0D);
        if (nearPos != null) {
            searchBox = searchBox.minmax(new AABB(nearPos).inflate(4.0D, 6.0D, 4.0D));
        }
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox, item ->
                item.isAlive() && !item.getItem().isEmpty() && isStoneDrop(item)
        );
        return items.stream()
                .min(Comparator.comparingDouble(item -> item.position().distanceToSqr(origin)))
                .orElse(null);
    }

    private boolean isStoneDrop(ItemEntity item) {
        return item != null && isStoneResourceItem(item.getItem());
    }

    private boolean isStoneResourceItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (
                stack.is(Items.COBBLESTONE)
                        || stack.is(Items.COBBLED_DEEPSLATE)
                        || stack.is(Items.STONE)
                        || stack.is(Items.ANDESITE)
                        || stack.is(Items.DIORITE)
                        || stack.is(Items.GRANITE)
        );
    }

    private boolean isValidStoneTarget(ServerLevel level, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return isStoneBlock(state)
                && isStoneExposed(level, pos)
                && !isHazardousStoneTarget(level, pos);
    }

    private boolean isStoneBlock(BlockState state) {
        return state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.STONE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE);
    }

    private boolean isStoneExposed(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighbor = level.getBlockState(neighborPos);
            if (neighbor.isAir() || neighbor.canBeReplaced() || !neighbor.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isHazardousStoneTarget(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (neighbor.is(Blocks.LAVA) || neighbor.is(Blocks.FIRE) || neighbor.is(Blocks.MAGMA_BLOCK)) {
                return true;
            }
        }
        return false;
    }

    private double scoreStoneTarget(ServerLevel level, BlockPos origin, BlockPos pos) {
        double score = origin.distSqr(pos);
        int depth = Math.max(0, origin.getY() - pos.getY());
        if (depth > STONE_MAX_DEPTH_BELOW_ORIGIN) {
            score += 5000.0D + (depth * 200.0D);
        } else {
            score += depth * 12.0D;
        }

        BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
        int surfaceDelta = Math.abs(surface.getY() - pos.getY());
        score += surfaceDelta * 4.0D;

        if (level.getBlockState(pos.above()).getFluidState().isEmpty()) {
            score -= 6.0D;
        }
        return score;
    }

    private boolean isWithinStoneBreakRange(AliceEntity syna, BlockPos pos) {
        return pos != null && syna.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= STONE_INTERACT_RANGE * STONE_INTERACT_RANGE;
    }

    private boolean tickExploreForStone(AliceEntity syna, ServerLevel level) {
        if (stoneSearchAnchor == null) {
            stoneSearchAnchor = syna.blockPosition();
        }

        if (stoneExploreTarget == null || syna.blockPosition().closerThan(stoneExploreTarget, 2.0D) || stoneStageTicks > STONE_ROAM_TIMEOUT_TICKS) {
            stoneSearchRadius = Math.min(STONE_SEARCH_RADIUS_MAX, Math.max(STONE_SEARCH_RADIUS, stoneSearchRadius + 8));
            stoneExploreTarget = chooseExploreTarget(level, stoneSearchAnchor, stoneSearchRadius);
            stoneStageTicks = 0;
            if (stoneExploreTarget == null) {
                BridgeState.get().addDebug("stone_explore:no_target radius=" + stoneSearchRadius + ",anchor=" + formatPos(stoneSearchAnchor));
                return stoneSearchRadius < STONE_SEARCH_RADIUS_MAX;
            }
            BridgeState.get().setLastEvent("collect_stone_exploring:" + formatPos(stoneExploreTarget));
        }

        syna.getNavigation().moveTo(stoneExploreTarget.getX() + 0.5D, stoneExploreTarget.getY(), stoneExploreTarget.getZ() + 0.5D, 1.0D);
        taskDetail = "exploring_for_stone:" + formatPos(stoneExploreTarget) + ":r=" + stoneSearchRadius;
        return true;
    }

    private void updateStoneStuckState(AliceEntity syna) {
        if (lastStoneProgressPos == null) {
            lastStoneProgressPos = syna.position();
            stoneStuckTicks = 0;
            return;
        }
        if (syna.position().distanceToSqr(lastStoneProgressPos) < 0.04D) {
            stoneStuckTicks++;
        } else {
            lastStoneProgressPos = syna.position();
            stoneStuckTicks = 0;
        }
    }

    private BlockPos findNearestLog(ServerLevel level, BlockPos origin) {
        return findNearestLog(level, origin, woodSearchRadius <= 0 ? WOOD_SEARCH_RADIUS : woodSearchRadius);
    }

    private BlockPos findNearestLog(ServerLevel level, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -6; dy <= 10; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(BlockTags.LOGS)) {
                        continue;
                    }
                    BlockPos normalized = normalizeToLowestConnectedLog(level, pos);
                    if (!hasStandableSpot(level, normalized)) {
                        continue;
                    }
                    double distance = origin.distSqr(normalized);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = normalized.immutable();
                    }
                }
            }
        }
        return best;
    }

    private ItemEntity findNearestWoodDrop(ServerLevel level, Vec3 origin, BlockPos nearPos) {
        AABB searchBox = new AABB(origin, origin).inflate(8.0D, 6.0D, 8.0D);
        if (nearPos != null) {
            searchBox = searchBox.minmax(new AABB(nearPos).inflate(4.0D, 6.0D, 4.0D));
        }
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox, item ->
                item.isAlive() && !item.getItem().isEmpty() && isWoodDrop(item)
        );
        return items.stream()
                .min(Comparator.comparingDouble(item -> item.position().distanceToSqr(origin)))
                .orElse(null);
    }

    private boolean isWoodDrop(ItemEntity item) {
        return item.getItem().getItem() instanceof BlockItem blockItem
                && blockItem.getBlock().defaultBlockState().is(BlockTags.LOGS);
    }

    private boolean isValidLog(ServerLevel level, BlockPos pos) {
        return pos != null && level.getBlockState(pos).is(BlockTags.LOGS);
    }

    private boolean isWithinBreakRange(AliceEntity syna, BlockPos pos) {
        return pos != null && syna.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= WOOD_INTERACT_RANGE * WOOD_INTERACT_RANGE;
    }

    private boolean tickExploreForLogs(AliceEntity syna, ServerLevel level) {
        if (woodSearchAnchor == null) {
            woodSearchAnchor = syna.blockPosition();
        }

        if (woodExploreTarget == null || syna.blockPosition().closerThan(woodExploreTarget, 2.0D) || woodStageTicks > WOOD_ROAM_TIMEOUT_TICKS) {
            woodSearchRadius = Math.min(WOOD_SEARCH_RADIUS_MAX, Math.max(WOOD_SEARCH_RADIUS, woodSearchRadius + 8));
            woodExploreTarget = chooseExploreTarget(level, woodSearchAnchor, woodSearchRadius);
            woodStageTicks = 0;
            if (woodExploreTarget == null) {
                BridgeState.get().addDebug("explore:no_target radius=" + woodSearchRadius + ",anchor=" + formatPos(woodSearchAnchor));
                return woodSearchRadius < WOOD_SEARCH_RADIUS_MAX;
            }
            BridgeState.get().addDebug("explore:new_target pos=" + formatPos(woodExploreTarget) + ",radius=" + woodSearchRadius);
            BridgeState.get().setLastEvent("collect_wood_exploring:" + formatPos(woodExploreTarget));
        }

        syna.getNavigation().moveTo(woodExploreTarget.getX() + 0.5D, woodExploreTarget.getY(), woodExploreTarget.getZ() + 0.5D, 1.0D);
        taskDetail = "exploring_for_logs:" + formatPos(woodExploreTarget) + ":r=" + woodSearchRadius;
        return true;
    }

    private BlockPos chooseExploreTarget(ServerLevel level, BlockPos anchor, int radius) {
        if (anchor == null) {
            return null;
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += 4) {
            for (int dz = -radius; dz <= radius; dz += 4) {
                BlockPos sample = anchor.offset(dx, 0, dz);
                BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample);
                if (!isStandable(level, top)) {
                    continue;
                }
                double score = Math.abs(anchor.distManhattan(top) - radius);
                if (score < bestScore) {
                    bestScore = score;
                    best = top.immutable();
                }
            }
        }
        return best;
    }

    private void updateWoodStuckState(AliceEntity syna) {
        if (lastWoodProgressPos == null) {
            lastWoodProgressPos = syna.position();
            woodStuckTicks = 0;
            return;
        }
        if (syna.position().distanceToSqr(lastWoodProgressPos) < 0.04D) {
            woodStuckTicks++;
        } else {
            lastWoodProgressPos = syna.position();
            woodStuckTicks = 0;
        }
    }

    private BlockPos normalizeToLowestConnectedLog(ServerLevel level, BlockPos pos) {
        BlockPos cursor = pos.immutable();
        while (isValidLog(level, cursor.below())) {
            cursor = cursor.below();
        }
        return cursor;
    }

    private BlockPos findNextLogInTree(ServerLevel level, BlockPos from) {
        if (from == null) {
            return null;
        }
        BlockPos above = from.above();
        if (isValidLog(level, above)) {
            return above.immutable();
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos sideAbove = from.relative(direction).above();
            if (isValidLog(level, sideAbove)) {
                return normalizeToLowestConnectedLog(level, sideAbove);
            }
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = from.offset(dx, dy, dz);
                    if (!isValidLog(level, candidate)) {
                        continue;
                    }
                    BlockPos normalized = candidate.getY() > from.getY()
                            ? candidate.immutable()
                            : normalizeToLowestConnectedLog(level, candidate);
                    double score = from.distSqr(normalized) - (normalized.getY() > from.getY() ? 2.0D : 0.0D);
                    if (score < bestScore) {
                        bestScore = score;
                        best = normalized.immutable();
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        return null;
    }

    private boolean hasStandableSpot(ServerLevel level, BlockPos logPos) {
        return findStandableSpot(level, logPos) != null;
    }

    private BlockPos findStandableSpot(ServerLevel level, BlockPos logPos) {
        if (logPos == null) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = logPos.relative(direction);
            if (!isStandable(level, candidate) || !canReachBlockFrom(level, candidate, logPos)) {
                continue;
            }
            double distance = candidate.distSqr(logPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.immutable();
            }
        }

        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos candidate = logPos.offset(dx, dy, dz);
                    if (!isStandable(level, candidate) || !canReachBlockFrom(level, candidate, logPos)) {
                        continue;
                    }
                    double verticalPenalty = Math.abs(candidate.getY() - logPos.getY()) * 1.5D;
                    double distance = candidate.distSqr(logPos) + verticalPenalty;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate.immutable();
                    }
                }
            }
        }

        if (best == null && isStandable(level, logPos.below()) && canReachBlockFrom(level, logPos.below(), logPos)) {
            best = logPos.below().immutable();
        }
        return best;
    }

    private boolean canReachBlockFrom(Level level, BlockPos standPos, BlockPos targetPos) {
        if (standPos == null || targetPos == null) {
            return false;
        }

        Vec3 eyePos = new Vec3(standPos.getX() + 0.5D, standPos.getY() + 1.62D, standPos.getZ() + 0.5D);
        return eyePos.distanceToSqr(Vec3.atCenterOf(targetPos)) <= WOOD_INTERACT_RANGE * WOOD_INTERACT_RANGE;
    }

    private boolean isStandable(Level level, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState floor = level.getBlockState(pos.below());
        return feet.canBeReplaced() && head.canBeReplaced() && !floor.isAir();
    }

    private void cleanupInvalidSynaReference() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || synaUuid == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(synaUuid);
            if (entity != null) {
                if (!(entity instanceof AliceEntity) || !entity.isAlive()) {
                    synaUuid = null;
                }
                return;
            }
        }
        synaUuid = null;
    }

    private int cleanupGhostSynas(ServerLevel level, boolean keepTrackedOne) {
        int removed = 0;
        for (AliceEntity alice : level.getEntitiesOfClass(AliceEntity.class, new AABB(-30000000, -128, -30000000, 30000000, 1024, 30000000))) {
            boolean tracked = synaUuid != null && synaUuid.equals(alice.getUUID());
            if (!keepTrackedOne || !tracked) {
                alice.discard();
                removed++;
            }
        }
        return removed;
    }

    private void lookAtBlock(AliceEntity syna, BlockPos pos) {
        lookAtPosition(syna, Vec3.atCenterOf(pos));
    }

    private void lookAtPosition(AliceEntity syna, Vec3 target) {
        Vec3 eye = syna.getEyePosition();
        Vec3 delta = target.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(delta.y, horizontal)));
        syna.setYRot(yaw);
        syna.setYHeadRot(yaw);
        syna.setXRot(pitch);
        syna.yBodyRot = yaw;
    }

    private boolean shouldForceStepJump(AliceEntity syna, StairStep step) {
        if (step == null || !step.ascending() || !syna.onGround() || mobilityJumpCooldownTicks > 0) {
            return false;
        }
        BlockState headState = syna.level().getBlockState(syna.blockPosition().above());
        if (!headState.isAir() && !headState.canBeReplaced()) {
            return false;
        }
        double dx = syna.getX() - (step.feet().getX() + 0.5D);
        double dz = syna.getZ() - (step.feet().getZ() + 0.5D);
        double horizontalDistanceSqr = dx * dx + dz * dz;
        return horizontalDistanceSqr < 2.25D && step.feet().getY() > syna.getY() + 0.25D;
    }

    private void performMobilityJump(AliceEntity syna) {
        Vec3 motion = syna.getDeltaMovement();
        syna.setDeltaMovement(motion.x, Math.max(motion.y, 0.42D), motion.z);
        syna.hasImpulse = true;
        mobilityJumpCooldownTicks = 10;
    }

    private double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private String round3(double value) {
        return String.format(java.util.Locale.US, "%.3f", value);
    }

    private void clearBreakingAnimation(ServerLevel level) {
        clearBreakingAnimation(level, woodTarget);
    }

    private void clearBreakingAnimation(ServerLevel level, BlockPos target) {
        if (level != null && target != null && synaUuid != null) {
            Entity entity = level.getEntity(synaUuid);
            if (entity != null) {
                level.destroyBlockProgress(entity.getId(), target, -1);
            }
        }
    }

    private void playHorrorEntranceEffect(AliceEntity syna) {
        if (!(syna.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = syna.position().add(0.0D, syna.getBbHeight() * 0.55D, 0.0D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, center.x, center.y, center.z, 48, 0.75D, 0.85D, 0.75D, 0.025D);
        level.sendParticles(ParticleTypes.SMOKE, center.x, center.y + 0.2D, center.z, 64, 0.9D, 0.7D, 0.9D, 0.02D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, center.x, center.y, center.z, 18, 0.55D, 0.45D, 0.55D, 0.01D);
        level.playSound(null, syna.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.8F, 0.62F);
        level.playSound(null, syna.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.9F, 0.75F);
        syna.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 5, 0, false, false, true));
        BridgeState.get().setLastEvent("horror_entrance:" + syna.getName().getString());
    }
    private void hideOriginalSynaPlayer(String ownerName, AliceEntity horrorSyna) {
        if (ownerName == null || ownerName.isBlank()) {
            BridgeState.get().addDebug("horror_takeover:no_owner_to_hide");
            return;
        }
        ServerPlayer player = findPlayerByExactName(ownerName);
        if (player == null) {
            BridgeState.get().addDebug("horror_takeover:owner_not_found=" + ownerName);
            return;
        }
        if (horrorSyna != null && player.getUUID().equals(horrorSyna.getUUID())) {
            return;
        }
        if (hiddenSynaPlayerUuid == null || !hiddenSynaPlayerUuid.equals(player.getUUID())) {
            hiddenSynaHadInvisibility = player.hasEffect(MobEffects.INVISIBILITY);
            hiddenSynaWasInvulnerable = player.isInvulnerable();
            hiddenSynaWasNoPhysics = player.noPhysics;
            hiddenSynaWasCustomNameVisible = player.isCustomNameVisible();
        }
        hiddenSynaPlayerUuid = player.getUUID();
        hiddenSynaPlayerName = player.getGameProfile().getName();
        applyOriginalSynaInvisibility(player);
        BridgeState.get().setLastEvent("horror_second_form:" + hiddenSynaPlayerName + ":hidden");
    }

    private void keepOriginalSynaPlayerHidden() {
        if (hiddenSynaPlayerUuid == null) {
            return;
        }
        ServerPlayer player = getPlayer(hiddenSynaPlayerUuid);
        if (player == null) {
            return;
        }
        var effect = player.getEffect(MobEffects.INVISIBILITY);
        if (effect == null || effect.getDuration() < 40) {
            applyOriginalSynaInvisibility(player);
        } else if (player.tickCount % 20 == 0) {
            sendHiddenSynaEquipment(player);
        }
    }

    private void applyOriginalSynaInvisibility(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 20, 0, false, false, false));
        player.setInvisible(true);
        player.setCustomNameVisible(false);
        player.setInvulnerable(true);
        player.noPhysics = true;
        player.setPose(Pose.STANDING);
        sendHiddenSynaEquipment(player);
    }

    private void sendHiddenSynaEquipment(ServerPlayer player) {
        sendSynaEquipmentView(player, true);
    }

    private void sendRealSynaEquipment(ServerPlayer player) {
        sendSynaEquipmentView(player, false);
    }

    private void sendSynaEquipmentView(ServerPlayer player, boolean hidden) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || player == null) {
            return;
        }
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack stack = hidden ? ItemStack.EMPTY : player.getItemBySlot(slot).copy();
            slots.add(Pair.of(slot, stack));
        }
        ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(player.getId(), slots);
        for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
            if (!recipient.getUUID().equals(player.getUUID())) {
                recipient.connection.send(packet);
            }
        }
    }

    private void restoreOriginalSynaPlayer(String reason) {
        if (hiddenSynaPlayerUuid == null) {
            return;
        }
        ServerPlayer player = getPlayer(hiddenSynaPlayerUuid);
        String restoredName = hiddenSynaPlayerName == null || hiddenSynaPlayerName.isBlank() ? "unknown" : hiddenSynaPlayerName;
        if (player != null) {
            if (!hiddenSynaHadInvisibility) {
                player.removeEffect(MobEffects.INVISIBILITY);
                player.setInvisible(false);
            }
            player.setInvulnerable(hiddenSynaWasInvulnerable);
            player.noPhysics = hiddenSynaWasNoPhysics;
            player.setCustomNameVisible(hiddenSynaWasCustomNameVisible);
            sendRealSynaEquipment(player);
        }
        hiddenSynaPlayerUuid = null;
        hiddenSynaPlayerName = "";
        hiddenSynaHadInvisibility = false;
        hiddenSynaWasInvulnerable = false;
        hiddenSynaWasNoPhysics = false;
        hiddenSynaWasCustomNameVisible = false;
        BridgeState.get().setLastEvent("horror_second_form_restored:" + restoredName + ":" + reason);
    }

    private ServerPlayer findPlayerByExactName(String playerName) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || playerName == null || playerName.isBlank()) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        return null;
    }
    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
    private String describeDamageCause(DamageSource source) {
        if (source == null || source.type() == null) {
            return "unknown";
        }
        return safeEventToken(source.type().msgId());
    }

    private String describeDamageSourceKind(DamageSource source) {
        if (source == null) {
            return "unknown";
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer) {
            return "player";
        }
        if (attacker != null || source.getDirectEntity() != null) {
            return "mob";
        }
        return "environment";
    }

    private String safeEventToken(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace(':', '_').replace(' ', '_');
    }

    private String formatPos(BlockPos pos) {
        return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private void say(MinecraftServer server, String text) {
        if (text != null && !text.isBlank()) {
            SynaManifestationDirector.get().rememberLine(text);
            server.getPlayerList().broadcastSystemMessage(Component.literal("[Syna] " + text), false);
            BridgeState.get().setLastEvent("say");
        }
    }

    private void announce(MinecraftServer server, String text, String event) {
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
        }
        BridgeState.get().setLastEvent(event);
    }

    private void bindFirstPlayer(MinecraftServer server) {
        if (!server.getPlayerList().getPlayers().isEmpty()) {
            BridgeState.get().bind(server.getPlayerList().getPlayers().get(0));
        }
    }

    private void announceTaskMessage(String text, boolean isFailure) {
        if (text == null || text.isBlank()) {
            return;
        }
        ServerPlayer player = BridgeState.get().getBoundPlayer();
        String prefix = isFailure ? "[Syna][失败] " : "[Syna] ";
        if (player != null) {
            player.sendSystemMessage(Component.literal(prefix + text));
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(prefix + text), false);
        }
    }

    private String simplifyItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "unknown";
        }
        int index = itemId.indexOf(':');
        return index >= 0 ? itemId.substring(index + 1) : itemId;
    }

    private String humanizeMissingList(List<String> missing) {
        if (missing == null || missing.isEmpty()) {
            return "未知材料";
        }
        List<String> parts = new ArrayList<>();
        for (String entry : missing) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] pieces = entry.split(":");
            if (pieces.length >= 3) {
                String label = pieces[0] + ":" + pieces[1];
                String count = pieces[2];
                parts.add(simplifyItemId(label) + " x" + count);
            } else if (pieces.length == 2) {
                parts.add(simplifyItemId(pieces[0]) + " x" + pieces[1]);
            } else {
                parts.add(entry);
            }
        }
        return parts.isEmpty() ? "未知材料" : String.join("，", parts);
    }

    private ServerPlayer getPlayer(UUID uuid) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    private ServerPlayer findPlayerByName(String playerName) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        if (playerName == null || playerName.isBlank()) {
            return BridgeState.get().getBoundPlayer();
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        return null;
    }
}
