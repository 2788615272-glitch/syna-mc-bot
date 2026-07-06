package com.syna.bridge.mobility;

import com.syna.bridge.mobility.path.PathFindingResult;
import com.syna.bridge.mobility.path.PathPlan;
import com.syna.bridge.mobility.path.Waypoint;
import com.syna.bridge.mobility.resource.ResourceSubtaskRequest;
import com.syna.bridge.mobility.resource.ResourceSubtaskType;
import net.minecraft.core.BlockPos;

public final class MobilityRuntime {
    private MobilityStatus status = MobilityStatus.IDLE;
    private MobilityRequest request;
    private PathPlan plan;
    private PathFindingResult lastPathFindingResult;
    private ResourceRequirement missingResource;
    private ResourceSubtaskRequest resourceSubtask;
    private String lastFailure = "";
    private int waypointIndex;
    private int stuckTicks;

    public void submit(MobilityRequest request) {
        this.request = request;
        this.plan = null;
        this.lastPathFindingResult = null;
        this.missingResource = null;
        this.resourceSubtask = null;
        this.lastFailure = "";
        this.waypointIndex = 0;
        this.stuckTicks = 0;
        this.status = MobilityStatus.PLANNING;
    }

    public void cancel(String reason) {
        this.lastFailure = reason == null ? "cancelled" : reason;
        this.status = MobilityStatus.IDLE;
        this.request = null;
        this.plan = null;
        this.missingResource = null;
        this.resourceSubtask = null;
        this.waypointIndex = 0;
    }

    public MobilityStatus status() {
        return status;
    }

    public MobilityRequest request() {
        return request;
    }

    public PathPlan plan() {
        return plan;
    }

    public void markPathResult(PathFindingResult result) {
        this.lastPathFindingResult = result;
    }

    public void startFollowing(PathPlan plan) {
        this.plan = plan;
        this.waypointIndex = 0;
        this.status = MobilityStatus.FOLLOWING;
    }

    public void suspend(ResourceRequirement requirement) {
        this.missingResource = requirement;
        this.resourceSubtask = requirement == null ? null : new ResourceSubtaskRequest(
                ResourceSubtaskType.COLLECT_SUPPORT_BLOCKS, requirement, request == null ? "" : request.requestId());
        this.status = MobilityStatus.SUSPENDED;
    }

    public ResourceSubtaskRequest resourceSubtask() {
        return resourceSubtask;
    }

    public void resumePlanningAfterResource(String reason) {
        this.plan = null;
        this.missingResource = null;
        this.resourceSubtask = null;
        this.waypointIndex = 0;
        this.stuckTicks = 0;
        this.lastFailure = reason == null ? "resource_ready" : reason;
        this.status = MobilityStatus.PLANNING;
    }

    public void complete() {
        this.status = MobilityStatus.COMPLETED;
    }

    public void fail(String reason) {
        this.lastFailure = reason == null ? "failed" : reason;
        this.status = MobilityStatus.FAILED;
    }

    public MobilitySnapshot snapshot() {
        BlockPos goal = null;
        String targetType = "none";
        if (request != null && request.target() != null) {
            targetType = request.target().typeName();
            if (request.target() instanceof BlockTarget blockTarget) {
                goal = blockTarget.pos();
            } else if (request.target() instanceof EntityStandTarget entityStandTarget) {
                goal = entityStandTarget.desiredStandPos();
            } else if (request.target() instanceof BreakSpotTarget breakSpotTarget) {
                goal = breakSpotTarget.resolveGoal(null, null);
            }
        }
        Waypoint currentWaypoint = plan == null ? null : plan.waypointAtOrNull(waypointIndex);
        int waypointCount = plan == null ? 0 : plan.waypoints().size();
        String ownerTask = request == null ? "idle" : request.ownerTask();
        String pathStatus = lastPathFindingResult == null ? "none" : lastPathFindingResult.status().name().toLowerCase();
        int expandedNodes = lastPathFindingResult == null ? 0 : lastPathFindingResult.expandedNodes();
        double totalCost = plan == null ? 0.0D : plan.totalCost();
        return new MobilitySnapshot(status != MobilityStatus.IDLE, status, ownerTask, targetType, goal, pathStatus,
                waypointIndex, waypointCount, currentWaypoint, expandedNodes, totalCost, missingResource,
                status == MobilityStatus.SUSPENDED, lastFailure, stuckTicks);
    }
}