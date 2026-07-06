package com.syna.bridge.mobility.follow;

public record FollowerSnapshot(
        FollowerState state,
        int waypointIndex,
        String detail
) {}