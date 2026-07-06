package com.syna.bridge.mobility;

public record MobilityTickResult(
        MobilityTickResultType type,
        String reason,
        ResourceRequirement resourceRequirement
) {
    public static MobilityTickResult idle() {
        return new MobilityTickResult(MobilityTickResultType.IDLE, "idle", null);
    }

    public static MobilityTickResult running(String reason) {
        return new MobilityTickResult(MobilityTickResultType.RUNNING, reason, null);
    }

    public static MobilityTickResult completed(String reason) {
        return new MobilityTickResult(MobilityTickResultType.COMPLETED, reason, null);
    }

    public static MobilityTickResult failed(String reason) {
        return new MobilityTickResult(MobilityTickResultType.FAILED, reason, null);
    }

    public static MobilityTickResult needResource(ResourceRequirement requirement) {
        return new MobilityTickResult(MobilityTickResultType.NEED_RESOURCE, "need_resource", requirement);
    }
}