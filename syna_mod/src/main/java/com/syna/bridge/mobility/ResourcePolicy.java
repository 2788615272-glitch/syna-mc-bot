package com.syna.bridge.mobility;

public record ResourcePolicy(
        boolean allowSupportBlockSubtask,
        int minimumSupportBlocks,
        int searchRadius
) {
    public static final ResourcePolicy DEFAULT = new ResourcePolicy(true, 8, 12);
    public static final ResourcePolicy NEVER_INTERRUPT = new ResourcePolicy(false, 0, 0);
}