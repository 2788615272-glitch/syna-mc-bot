package com.syna.bridge.mobility;

import com.syna.bridge.AliceEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

public record EntityStandTarget(
        UUID entityUuid,
        BlockPos desiredStandPos,
        double minDistance,
        double maxDistance
) implements MobilityTarget {
    @Override
    public BlockPos resolveGoal(ServerLevel level, AliceEntity syna) {
        return desiredStandPos;
    }

    @Override
    public boolean isStillValid(ServerLevel level, AliceEntity syna) {
        return entityUuid != null && desiredStandPos != null && level.getEntity(entityUuid) != null;
    }

    @Override
    public String typeName() {
        return "entity_stand";
    }
}