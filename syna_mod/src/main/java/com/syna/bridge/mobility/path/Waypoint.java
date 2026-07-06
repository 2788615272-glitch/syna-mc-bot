package com.syna.bridge.mobility.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record Waypoint(
        BlockPos standPos,
        WaypointAction action,
        BlockPos actionTarget,
        Direction face,
        String reason,
        double cost
) {}