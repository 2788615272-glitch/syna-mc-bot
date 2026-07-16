package com.syna.bridge;

final class StoryPacingPolicy {
    static final int MAX_BUDGET = 10;
    static final int INITIAL_BUDGET = 8;
    private static final long HIGH_INTENSITY_QUIET_TICKS = 20L * 45L;

    private StoryPacingPolicy() {}

    static int recover(int budget) {
        return Math.min(MAX_BUDGET, Math.max(0, budget) + 1);
    }

    static boolean canStart(int budget, long quietUntilTick, long now, String scene) {
        return now >= quietUntilTick && budget >= intensity(scene);
    }

    static int spend(int budget, String scene) {
        return Math.max(0, budget - intensity(scene));
    }

    static long quietUntil(long currentQuietUntil, long now, String scene) {
        return intensity(scene) >= 4
                ? Math.max(currentQuietUntil, now + HIGH_INTENSITY_QUIET_TICKS)
                : currentQuietUntil;
    }

    static int intensity(String scene) {
        if (scene == null) return 1;
        return switch (scene) {
            case "observe", "footsteps", "silence" -> 1;
            case "trace", "disappear", "boundary", "warning" -> 2;
            case "watcher", "reveal" -> 3;
            case "stalker", "touch" -> 4;
            case "ambush", "enforcer", "pursuit", "locked_door", "judgment" -> 5;
            default -> 2;
        };
    }
}
