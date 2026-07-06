package com.syna.bridge.mobility;

import com.syna.bridge.AliceEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public record BreakSpotTarget(
        BlockPos blockToBreak,
        BlockPos standPos,
        double interactRange
) implements MobilityTarget {
    @Override
    public BlockPos resolveGoal(ServerLevel level, AliceEntity syna) {
        return standPos == null ? blockToBreak : standPos;
    }

    @Override
    public boolean isStillValid(ServerLevel level, AliceEntity syna) {
        return blockToBreak != null;
    }

    @Override
    public String typeName() {
        return "break_spot";
    }
}