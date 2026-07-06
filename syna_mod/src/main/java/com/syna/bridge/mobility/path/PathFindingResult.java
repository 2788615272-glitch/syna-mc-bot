package com.syna.bridge.mobility.path;

import com.syna.bridge.mobility.ResourceRequirement;
import net.minecraft.core.BlockPos;

public record PathFindingResult(
        PathFindingStatus status,
        PathPlan plan,
        ResourceRequirement missingResource,
        BlockPos nearestReachable,
        String reason,
        int expandedNodes,
        double totalCost
) {
    public static PathFindingResult success(PathPlan plan, int expandedNodes) {
        return new PathFindingResult(PathFindingStatus.SUCCESS, plan, null, null, "success", expandedNodes,
                plan == null ? 0.0D : plan.totalCost());
    }

    public static PathFindingResult noPath(String reason, BlockPos nearestReachable, int expandedNodes) {
        return new PathFindingResult(PathFindingStatus.NO_PATH, null, null, nearestReachable, reason, expandedNodes, 0.0D);
    }

    public static PathFindingResult needResource(ResourceRequirement requirement, BlockPos nearestReachable, int expandedNodes) {
        return new PathFindingResult(PathFindingStatus.NEED_RESOURCE, null, requirement, nearestReachable,
                "need_resource", expandedNodes, 0.0D);
    }
}