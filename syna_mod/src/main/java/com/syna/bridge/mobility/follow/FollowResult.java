package com.syna.bridge.mobility.follow;

import com.syna.bridge.mobility.ResourceRequirement;

public record FollowResult(
        FollowResultType type,
        String reason,
        ResourceRequirement resourceRequirement
) {
    public static FollowResult running(String reason) {
        return new FollowResult(FollowResultType.RUNNING, reason, null);
    }

    public static FollowResult completed(String reason) {
        return new FollowResult(FollowResultType.PATH_COMPLETED, reason, null);
    }

    public static FollowResult failed(String reason) {
        return new FollowResult(FollowResultType.FAILED, reason, null);
    }
}