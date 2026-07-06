package com.syna.bridge.mobility.safety;

import com.syna.bridge.mobility.BuildingProtectionMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class BuildingProtectionPolicy {
    private final BlockSafetyClassifier classifier = new BlockSafetyClassifier();

    public boolean isProtected(BlockState state, BlockPos pos, BuildingProtectionMode mode) {
        if (mode == BuildingProtectionMode.OFF) {
            return false;
        }
        return classifier.isLikelyPlayerStructure(state);
    }
}