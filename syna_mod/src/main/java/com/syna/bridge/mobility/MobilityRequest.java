package com.syna.bridge.mobility;

import net.minecraft.core.BlockPos;

import java.util.UUID;

public record MobilityRequest(
        String requestId,
        String ownerTask,
        MobilityTarget target,
        MovementPermission permission,
        BuildingProtectionMode protectionMode,
        ResourcePolicy resourcePolicy,
        int maxSearchNodes,
        int maxVerticalUp,
        int maxVerticalDown,
        double arriveDistance,
        boolean allowReplan,
        String reason
) {
    public static MobilityRequest goTo(BlockPos target, String ownerTask, MovementPermission permission) {
        return new MobilityRequest(UUID.randomUUID().toString(), ownerTask, new BlockTarget(target), permission,
                BuildingProtectionMode.NORMAL, ResourcePolicy.DEFAULT, 6000, 16, 24, 1.5D, true, "go_to");
    }

    public static MobilityRequest followEntity(UUID entityUuid, BlockPos desiredStandPos, MovementPermission permission) {
        return new MobilityRequest(UUID.randomUUID().toString(), "follow", new EntityStandTarget(entityUuid, desiredStandPos, 2.0D, 4.0D), permission,
                BuildingProtectionMode.NORMAL, ResourcePolicy.DEFAULT, 5000, 16, 24, 2.0D, true, "follow_entity");
    }
}