package com.syna.bridge.mobility;

import com.syna.bridge.AliceEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record BlockTarget(BlockPos pos) implements MobilityTarget {
    @Override
    public BlockPos resolveGoal(ServerLevel level, AliceEntity syna) {
        return pos;
    }

    @Override
    public boolean isStillValid(ServerLevel level, AliceEntity syna) {
        return pos != null;
    }

    @Override
    public String typeName() {
        return "block";
    }
}