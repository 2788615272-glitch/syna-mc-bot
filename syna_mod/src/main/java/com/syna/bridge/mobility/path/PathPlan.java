package com.syna.bridge.mobility.path;

import com.syna.bridge.mobility.ResourceRequirement;

import java.util.List;

public record PathPlan(
        List<Waypoint> waypoints,
        double totalCost,
        int estimatedTicks,
        List<ResourceRequirement> requiredResources,
        String debugSummary
) {
    public PathPlan {
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        requiredResources = requiredResources == null ? List.of() : List.copyOf(requiredResources);
    }

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    public Waypoint waypointAtOrNull(int index) {
        return index < 0 || index >= waypoints.size() ? null : waypoints.get(index);
    }
}