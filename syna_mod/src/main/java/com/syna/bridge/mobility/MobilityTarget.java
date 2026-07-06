package com.syna.bridge.mobility;

import com.syna.bridge.AliceEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public sealed interface MobilityTarget permits BlockTarget, EntityStandTarget, BreakSpotTarget {
    BlockPos resolveGoal(ServerLevel level, AliceEntity syna);

    boolean isStillValid(ServerLevel level, AliceEntity syna);

    String typeName();
}