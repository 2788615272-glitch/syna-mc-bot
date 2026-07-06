package com.syna.bridge.mobility;

import com.syna.bridge.mobility.path.Waypoint;
import net.minecraft.core.BlockPos;

public record MobilitySnapshot(
        boolean active,
        MobilityStatus status,
        String ownerTask,
        String targetType,
        BlockPos goal,
        String pathStatus,
        int waypointIndex,
        int waypointCount,
        Waypoint currentWaypoint,
        int expandedNodes,
        double totalCost,
        ResourceRequirement missingResource,
        boolean suspended,
        String lastFailure,
        int stuckTicks
) {
    public static MobilitySnapshot idle() {
        return new MobilitySnapshot(false, MobilityStatus.IDLE, "idle", "none", null, "none", 0, 0, null,
                0, 0.0D, null, false, "", 0);
    }
}