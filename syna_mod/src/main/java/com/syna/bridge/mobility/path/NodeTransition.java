package com.syna.bridge.mobility.path;

import com.syna.bridge.mobility.ResourceRequirement;

public record NodeTransition(
        PathNode from,
        PathNode to,
        Waypoint waypoint,
        int cost,
        ResourceRequirement resourceCost,
        String reason
) {}