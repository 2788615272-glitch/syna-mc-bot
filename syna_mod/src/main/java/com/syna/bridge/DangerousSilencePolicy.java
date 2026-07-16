package com.syna.bridge;

final class DangerousSilencePolicy {
    static final long MIN_DELAY_TICKS = 20L * 8L;
    static final long DELAY_VARIATION_TICKS = 20L * 8L;
    static final long COOLDOWN_TICKS = 20L * 90L;

    private DangerousSilencePolicy() {}

    static boolean canSchedule(long now, long dueTick, long lastResolvedTick) {
        if (dueTick > now) return false;
        return lastResolvedTick <= 0L || now - lastResolvedTick >= COOLDOWN_TICKS;
    }

    static long delayTicks(int chapter, int sequence) {
        long offset = Math.floorMod(chapter * 97L + sequence * 53L, DELAY_VARIATION_TICKS + 1L);
        return MIN_DELAY_TICKS + offset;
    }

    static Outcome choose(int chapter, int sequence) {
        int safeChapter = Math.max(1, Math.min(5, chapter));
        int variants = safeChapter >= 4 ? 4 : Math.max(1, safeChapter);
        return switch (Math.floorMod(sequence, variants)) {
            case 1 -> Outcome.BRIEF_DARKNESS;
            case 2 -> Outcome.MANIFESTATION;
            case 3 -> Outcome.DISTANT_KNOCK;
            default -> Outcome.PHANTOM_STEPS;
        };
    }

    enum Outcome {
        PHANTOM_STEPS,
        BRIEF_DARKNESS,
        MANIFESTATION,
        DISTANT_KNOCK
    }
}
