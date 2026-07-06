package com.syna.bridge.mobility;

public record MovementPermission(
        boolean allowWalk,
        boolean allowJump,
        boolean allowDig,
        boolean allowPlaceSupport,
        boolean allowUnsafeBreak
) {
    public static final MovementPermission DEFAULT_SURVIVAL = new MovementPermission(true, true, true, true, false);
    public static final MovementPermission WALK_ONLY = new MovementPermission(true, true, false, false, false);
}