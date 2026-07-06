package com.syna.bridge.mobility.path;

import net.minecraft.core.BlockPos;

public record PathNode(
        BlockPos feet,
        int gCost,
        int hCost,
        int flags
) {
    public int fCost() {
        return gCost + hCost;
    }
}