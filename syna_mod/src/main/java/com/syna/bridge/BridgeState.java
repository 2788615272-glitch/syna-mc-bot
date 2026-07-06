package com.syna.bridge;

import com.syna.bridge.mobility.MobilitySnapshot;
import com.syna.bridge.mobility.ResourceRequirement;
import com.syna.bridge.mobility.path.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public final class BridgeState {
    private static final BridgeState INSTANCE = new BridgeState();
    private static final int DEBUG_LOG_LIMIT = 80;

    private volatile UUID boundPlayerUuid;
    private volatile String lastEvent = "boot";
    private volatile long tick = 0L;
    private final Deque<String> debugLog = new ArrayDeque<>();

    private BridgeState() {}

    public static BridgeState get() {
        return INSTANCE;
    }

    public void onServerTick() {
        tick++;
    }

    public void bind(ServerPlayer player) {
        if (player != null) {
            this.boundPlayerUuid = player.getUUID();
            this.lastEvent = "bind:" + player.getGameProfile().getName();
        }
    }

    public ServerPlayer getBoundPlayer() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || boundPlayerUuid == null) return null;
        return server.getPlayerList().getPlayer(boundPlayerUuid);
    }

    public void setLastEvent(String event) {
        this.lastEvent = event;
        addDebug("event=" + event);
    }

    public synchronized void addDebug(String message) {
        String entry = tick + ":" + escape(message == null ? "" : message);
        debugLog.addLast(entry);
        while (debugLog.size() > DEBUG_LOG_LIMIT) {
            debugLog.removeFirst();
        }
    }

    public String toJson() {
        var server = ServerLifecycleHooks.getCurrentServer();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"ok\":true,");
        sb.append("\"tick\":").append(tick).append(",");
        sb.append("\"lastEvent\":\"").append(escape(lastEvent)).append("\",");
        appendDebugLog(sb);
        appendSynaState(sb);

        if (server == null) {
            sb.append("\"server\":null}");
            return sb.toString();
        }

        sb.append("\"playersOnline\":").append(server.getPlayerCount()).append(",");

        ServerPlayer player = getBoundPlayer();
        if (player == null && !server.getPlayerList().getPlayers().isEmpty()) {
            player = server.getPlayerList().getPlayers().get(0);
        }

        if (player == null) {
            sb.append("\"boundPlayer\":null}");
            return sb.toString();
        }

        Level level = player.level();
        sb.append("\"boundPlayer\":{");
        sb.append("\"name\":\"").append(escape(player.getGameProfile().getName())).append("\",");
        sb.append("\"uuid\":\"").append(player.getUUID()).append("\",");
        sb.append("\"x\":").append(round(player.getX())).append(",");
        sb.append("\"y\":").append(round(player.getY())).append(",");
        sb.append("\"z\":").append(round(player.getZ())).append(",");
        sb.append("\"yaw\":").append(round(player.getYRot())).append(",");
        sb.append("\"pitch\":").append(round(player.getXRot())).append(",");
        sb.append("\"health\":").append(round(player.getHealth())).append(",");
        sb.append("\"food\":").append(player.getFoodData().getFoodLevel()).append(",");
        sb.append("\"dimension\":\"").append(escape(level.dimension().location().toString())).append("\"}");
        sb.append("}");
        return sb.toString();
    }

    private void appendSynaState(StringBuilder sb) {
        SynaController controller = SynaController.get();
        var syna = SynaController.get().getSyna();
        sb.append("\"syna\":");
        if (syna == null) {
            sb.append("null,");
            return;
        }

        sb.append("{");
        sb.append("\"name\":\"").append(escape(syna.getName().getString())).append("\",");
        sb.append("\"uuid\":\"").append(syna.getUUID()).append("\",");
        sb.append("\"x\":").append(round(syna.getX())).append(",");
        sb.append("\"y\":").append(round(syna.getY())).append(",");
        sb.append("\"z\":").append(round(syna.getZ())).append(",");
        sb.append("\"health\":").append(round(syna.getHealth())).append(",");
        sb.append("\"task\":\"").append(escape(controller.getCurrentTask())).append("\",");
        sb.append("\"taskDetail\":\"").append(escape(controller.getTaskDetail())).append("\",");
        sb.append("\"horror\":");
        appendHorrorState(sb, controller);
        sb.append(",");
        sb.append("\"dimension\":\"").append(escape(syna.level().dimension().location().toString())).append("\",");
        sb.append("\"inventory\":");
        appendInventoryState(sb, syna);
        sb.append(",");
        sb.append("\"mobility\":");
        appendMobilityState(sb, controller);
        sb.append(",");
        sb.append("\"woodTask\":");
        appendWoodTaskState(sb, controller);
        sb.append(",");
        sb.append("\"stoneTask\":");
        appendStoneTaskState(sb, controller);
        sb.append(",");
        sb.append("\"craftTask\":");
        appendCraftTaskState(sb, controller);
        sb.append("},");
    }

    private void appendHorrorState(StringBuilder sb, SynaController controller) {
        sb.append("{");
        sb.append("\"form\":\"").append(escape(controller.getHorrorForm())).append("\",");
        sb.append("\"stage\":\"").append(escape(controller.getHorrorStage())).append("\",");
        sb.append("\"anger\":").append(controller.getHorrorAnger()).append(",");
        sb.append("\"target\":\"").append(escape(controller.getHorrorTargetName())).append("\",");
        sb.append("\"targetKind\":\"").append(escape(controller.getHorrorTargetKind())).append("\",");
        sb.append("\"countdownTicks\":").append(controller.getHorrorCountdownTicks()).append(",");
        sb.append("\"countdownSeconds\":").append(round(controller.getHorrorCountdownTicks() / 20.0D)).append(",");
        sb.append("\"awaitingConfession\":").append(controller.isHorrorAwaitingConfession()).append(",");
        sb.append("\"angerKey\":\"").append(escape(controller.getHorrorAngerKey())).append("\",");
        sb.append("\"challengeKind\":\"").append(escape(controller.getHorrorChallengeKind())).append("\",");
        sb.append("\"challengeTarget\":\"").append(escape(controller.getHorrorChallengeTarget())).append("\",");
        sb.append("\"challengeClue\":\"").append(escape(controller.getHorrorChallengeClue())).append("\",");
        sb.append("\"challengeRequired\":").append(controller.getHorrorChallengeRequired()).append(",");
        sb.append("\"challengeProgress\":").append(controller.getHorrorChallengeProgress()).append(",");
        sb.append("\"challengeTicks\":").append(controller.getHorrorChallengeTicks()).append(",");
        sb.append("\"challengeSeconds\":").append(round(controller.getHorrorChallengeTicks() / 20.0D));
        sb.append("}");
    }

    private void appendInventoryState(StringBuilder sb, AliceEntity syna) {
        SynaInventory inventory = syna.getInventory();
        sb.append("{");
        sb.append("\"occupiedSlots\":").append(syna.getInventoryOccupiedSlots()).append(",");
        sb.append("\"freeSlots\":").append(inventory.getFreeSlots()).append(",");
        sb.append("\"totalSlots\":").append(inventory.getContainerSize()).append(",");
        sb.append("\"totalItemCount\":").append(syna.getInventoryTotalItemCount()).append(",");
        sb.append("\"mainHand\":");
        appendStackSummary(sb, inventory.getMainHandItem());
        sb.append(",");
        sb.append("\"items\":[");
        boolean first = true;
        for (SynaInventory.ItemView view : inventory.snapshot()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{");
            sb.append("\"slot\":").append(view.slot()).append(",");
            sb.append("\"item\":\"").append(escape(view.item())).append("\",");
            sb.append("\"count\":").append(view.count()).append(",");
            sb.append("\"mainHand\":").append(view.mainHand());
            sb.append("}");
        }
        sb.append("]");
        sb.append("}");
    }

    private void appendStackSummary(StringBuilder sb, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            sb.append("null");
            return;
        }

        sb.append("{");
        sb.append("\"item\":\"").append(escape(stack.getItem().toString())).append("\",");
        sb.append("\"count\":").append(stack.getCount());
        sb.append("}");
    }

    private synchronized void appendDebugLog(StringBuilder sb) {
        sb.append("\"debugLog\":[");
        boolean first = true;
        for (String entry : debugLog) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(entry).append("\"");
        }
        sb.append("],");
    }

    private void appendWoodTaskState(StringBuilder sb, SynaController controller) {
        BlockPos target = controller.getWoodTarget();
        sb.append("{");
        sb.append("\"active\":").append(controller.isWoodTaskActive()).append(",");
        sb.append("\"stage\":\"").append(escape(controller.getWoodTaskStage())).append("\",");
        sb.append("\"goalCount\":").append(controller.getWoodGoalCount()).append(",");
        sb.append("\"collectedCount\":").append(controller.getWoodCollectedCount()).append(",");
        sb.append("\"brokenCount\":").append(controller.getWoodBrokenCount()).append(",");
        sb.append("\"breakTicks\":").append(controller.getWoodBreakTicks()).append(",");
        sb.append("\"breakProgress\":").append(round(progressPercent(controller))).append(",");
        sb.append("\"remainingCount\":").append(Math.max(0, controller.getWoodGoalCount() - controller.getWoodCollectedCount())).append(",");
        sb.append("\"hasDropTarget\":").append(controller.getWoodDropTargetUuid() != null).append(",");
        sb.append("\"target\":");
        if (target == null) {
            sb.append("null");
        } else {
            sb.append("{");
            sb.append("\"x\":").append(target.getX()).append(",");
            sb.append("\"y\":").append(target.getY()).append(",");
            sb.append("\"z\":").append(target.getZ());
            sb.append("}");
        }
        sb.append("}");
    }

    private void appendMobilityState(StringBuilder sb, SynaController controller) {
        BlockPos anchor = controller.getMobilityAnchor();
        BlockPos goal = controller.getMobilityGoal();
        BlockPos target = controller.getMobilityTarget();
        BlockPos targetStandPos = controller.getMobilityTargetStandPos();
        BlockPos digTarget = controller.getMobilityDigTarget();
        BlockPos digHeadTarget = controller.getMobilityDigHeadTarget();
        BlockPos supportTarget = controller.getMobilitySupportTarget();
        BlockPos protectedTarget = controller.getMobilityProtectedTarget();
        BlockPos blockedBy = controller.getMobilityBlockedBy();
        sb.append("{");
        sb.append("\"active\":").append(controller.isMobilityTaskActive()).append(",");
        sb.append("\"ownerTask\":\"").append(escape(controller.getMobilityOwnerTask())).append("\",");
        sb.append("\"targetPlayerUuid\":\"").append(escape(controller.getMobilityTargetPlayerUuid())).append("\",");
        sb.append("\"planner\":\"").append(escape(controller.getMobilityPlanType())).append("\",");
        sb.append("\"stage\":\"").append(escape(controller.getMobilityPlannerStage())).append("\",");
        sb.append("\"action\":\"").append(escape(controller.getMobilityAction())).append("\",");
        sb.append("\"fallback\":\"").append(escape(controller.getMobilityFallback())).append("\",");
        sb.append("\"mode\":\"").append(escape(controller.getMobilityMode())).append("\",");
        sb.append("\"detail\":\"").append(escape(controller.getMobilityDetail())).append("\",");
        sb.append("\"reason\":\"").append(escape(controller.getMobilityReason())).append("\",");
        sb.append("\"lastFailure\":\"").append(escape(controller.getMobilityLastFailure())).append("\",");
        sb.append("\"blockedReason\":\"").append(escape(controller.getMobilityBlockedReason())).append("\",");
        sb.append("\"replanReason\":\"").append(escape(controller.getMobilityReplanReason())).append("\",");
        sb.append("\"lastActionResult\":\"").append(escape(controller.getMobilityLastActionResult())).append("\",");
        sb.append("\"hud\":\"").append(escape(controller.getMobilityHudText())).append("\",");
        sb.append("\"stuckLevel\":\"").append(escape(controller.getMobilityStuckLevel())).append("\",");
        sb.append("\"distanceToGoal\":").append(round(controller.getMobilityDistanceToGoal())).append(",");
        sb.append("\"directPathAvailable\":").append(controller.isMobilityDirectPathAvailable()).append(",");
        sb.append("\"protectionMode\":\"").append(escape(controller.getMobilityProtectionMode())).append("\",");
        sb.append("\"stageTicks\":").append(controller.getMobilityStageTicks()).append(",");
        sb.append("\"planTicks\":").append(controller.getMobilityPlanTicks()).append(",");
        sb.append("\"stuckTicks\":").append(controller.getMobilityStuckTicks()).append(",");
        sb.append("\"replanCount\":").append(controller.getMobilityReplanCount()).append(",");
        sb.append("\"placePhase\":").append(controller.getMobilityPlacePhase()).append(",");
        sb.append("\"blocksBroken\":").append(controller.getMobilityBlocksBroken()).append(",");
        sb.append("\"supportBlocksUsed\":").append(controller.getMobilitySupportBlocksUsed()).append(",");
        sb.append("\"availableSupportBlocks\":").append(controller.getMobilityAvailableSupportBlocks()).append(",");
        sb.append("\"anchor\":");
        appendPos(sb, anchor);
        sb.append(",");
        sb.append("\"goal\":");
        appendPos(sb, goal);
        sb.append(",");
        sb.append("\"target\":");
        appendPos(sb, target);
        sb.append(",");
        sb.append("\"targetStandPos\":");
        appendPos(sb, targetStandPos);
        sb.append(",");
        sb.append("\"digTarget\":");
        appendPos(sb, digTarget);
        sb.append(",");
        sb.append("\"digHeadTarget\":");
        appendPos(sb, digHeadTarget);
        sb.append(",");
        sb.append("\"supportTarget\":");
        appendPos(sb, supportTarget);
        sb.append(",");
        sb.append("\"protectedTarget\":");
        appendPos(sb, protectedTarget);
        sb.append(",");
        sb.append("\"blockedBy\":");
        appendPos(sb, blockedBy);
        sb.append(",");
        appendGlobalPathingState(sb, controller.getGlobalPathingMobilitySnapshot());
        sb.append("}");
    }

    private void appendGlobalPathingState(StringBuilder sb, MobilitySnapshot snapshot) {
        if (snapshot == null) {
            snapshot = MobilitySnapshot.idle();
        }
        sb.append("\"globalPathing\":{");
        sb.append("\"active\":").append(snapshot.active()).append(",");
        sb.append("\"status\":\"").append(escape(snapshot.status().name().toLowerCase())).append("\",");
        sb.append("\"ownerTask\":\"").append(escape(snapshot.ownerTask())).append("\",");
        sb.append("\"targetType\":\"").append(escape(snapshot.targetType())).append("\",");
        sb.append("\"goal\":");
        appendPos(sb, snapshot.goal());
        sb.append(",");
        sb.append("\"pathStatus\":\"").append(escape(snapshot.pathStatus())).append("\",");
        sb.append("\"waypointIndex\":").append(snapshot.waypointIndex()).append(",");
        sb.append("\"waypointCount\":").append(snapshot.waypointCount()).append(",");
        sb.append("\"currentWaypoint\":");
        appendWaypoint(sb, snapshot.currentWaypoint());
        sb.append(",");
        sb.append("\"expandedNodes\":").append(snapshot.expandedNodes()).append(",");
        sb.append("\"totalCost\":").append(round(snapshot.totalCost())).append(",");
        sb.append("\"missingResource\":");
        appendResourceRequirement(sb, snapshot.missingResource());
        sb.append(",");
        sb.append("\"suspended\":").append(snapshot.suspended()).append(",");
        sb.append("\"lastFailure\":\"").append(escape(snapshot.lastFailure())).append("\",");
        sb.append("\"stuckTicks\":").append(snapshot.stuckTicks());
        sb.append("}");
    }

    private void appendWaypoint(StringBuilder sb, Waypoint waypoint) {
        if (waypoint == null) {
            sb.append("null");
            return;
        }
        sb.append("{");
        sb.append("\"action\":\"").append(escape(waypoint.action().name().toLowerCase())).append("\",");
        sb.append("\"standPos\":");
        appendPos(sb, waypoint.standPos());
        sb.append(",");
        sb.append("\"actionTarget\":");
        appendPos(sb, waypoint.actionTarget());
        sb.append(",");
        sb.append("\"reason\":\"").append(escape(waypoint.reason())).append("\",");
        sb.append("\"cost\":").append(round(waypoint.cost()));
        sb.append("}");
    }

    private void appendResourceRequirement(StringBuilder sb, ResourceRequirement requirement) {
        if (requirement == null) {
            sb.append("null");
            return;
        }
        sb.append("{");
        sb.append("\"type\":\"").append(escape(requirement.type().name().toLowerCase())).append("\",");
        sb.append("\"count\":").append(requirement.count()).append(",");
        sb.append("\"reason\":\"").append(escape(requirement.reason())).append("\"");
        sb.append("}");
    }

    private void appendCraftTaskState(StringBuilder sb, SynaController controller) {
        sb.append("{");
        sb.append("\"active\":").append(controller.isCraftTaskActive()).append(",");
        sb.append("\"stage\":\"").append(escape(controller.getCraftTaskStage())).append("\",");
        sb.append("\"targetItem\":");
        if (controller.getCraftTargetItem() == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escape(controller.getCraftTargetItem())).append("\"");
        }
        sb.append(",");
        sb.append("\"targetCount\":").append(controller.getCraftTargetCount()).append(",");
        sb.append("\"craftedCount\":").append(controller.getCraftCraftedCount()).append(",");
        sb.append("\"remainingCount\":").append(Math.max(0, controller.getCraftTargetCount() - controller.getCraftCraftedCount())).append(",");
        sb.append("\"waitingForWood\":").append(controller.isCraftWaitingForWood()).append(",");
        sb.append("\"requestedWoodCount\":").append(controller.getCraftRequestedWoodCount()).append(",");
        sb.append("\"lastResult\":");
        if (controller.getCraftLastResult() == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escape(controller.getCraftLastResult())).append("\"");
        }
        sb.append(",");
        sb.append("\"missing\":[");
        boolean first = true;
        for (String missing : controller.getCraftMissingItems()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escape(missing)).append("\"");
        }
        sb.append("]");
        sb.append("}");
    }

    private void appendStoneTaskState(StringBuilder sb, SynaController controller) {
        BlockPos target = controller.getStoneTarget();
        sb.append("{");
        sb.append("\"active\":").append(controller.isStoneTaskActive()).append(",");
        sb.append("\"stage\":\"").append(escape(controller.getStoneTaskStage())).append("\",");
        sb.append("\"goalCount\":").append(controller.getStoneGoalCount()).append(",");
        sb.append("\"collectedCount\":").append(controller.getStoneCollectedCount()).append(",");
        sb.append("\"brokenCount\":").append(controller.getStoneBrokenCount()).append(",");
        sb.append("\"breakTicks\":").append(controller.getStoneBreakTicks()).append(",");
        sb.append("\"breakProgress\":").append(round(progressPercent(controller.getStoneGoalCount(), controller.getStoneCollectedCount()))).append(",");
        sb.append("\"remainingCount\":").append(Math.max(0, controller.getStoneGoalCount() - controller.getStoneCollectedCount())).append(",");
        sb.append("\"hasDropTarget\":").append(controller.getStoneDropTargetUuid() != null).append(",");
        sb.append("\"target\":");
        if (target == null) {
            sb.append("null");
        } else {
            sb.append("{");
            sb.append("\"x\":").append(target.getX()).append(",");
            sb.append("\"y\":").append(target.getY()).append(",");
            sb.append("\"z\":").append(target.getZ());
            sb.append("}");
        }
        sb.append("}");
    }

    private double progressPercent(SynaController controller) {
        int goal = controller.getWoodGoalCount();
        if (goal <= 0) {
            return 0.0D;
        }
        return Math.min(100.0D, (controller.getWoodCollectedCount() * 100.0D) / goal);
    }

    private double progressPercent(int goal, int collected) {
        if (goal <= 0) {
            return 0.0D;
        }
        return Math.min(100.0D, (collected * 100.0D) / goal);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void appendPos(StringBuilder sb, BlockPos pos) {
        if (pos == null) {
            sb.append("null");
            return;
        }
        sb.append("{");
        sb.append("\"x\":").append(pos.getX()).append(",");
        sb.append("\"y\":").append(pos.getY()).append(",");
        sb.append("\"z\":").append(pos.getZ());
        sb.append("}");
    }

    private static String round(double value) {
        return String.format(java.util.Locale.US, "%.3f", value);
    }
}