package com.syna.bridge;

final class PresenceProofPolicy {
    static final long COOLDOWN_TICKS = 20L * 60L;

    private PresenceProofPolicy() {}

    static boolean canTrigger(long storyTicks, long lastProofTick) {
        return lastProofTick <= 0L || storyTicks - lastProofTick >= COOLDOWN_TICKS;
    }

    static Proof choose(int chapter, int priorProofs) {
        int safeChapter = Math.max(1, Math.min(5, chapter));
        int variant = Math.floorMod(priorProofs, safeChapter >= 4 ? 4 : safeChapter);
        if (variant == 0) return Proof.PHANTOM_STEPS;
        if (variant == 1) return Proof.BRIEF_DARKNESS;
        if (variant == 2) return Proof.MANIFESTATION;
        return Proof.DISTANT_KNOCK;
    }

    enum Proof {
        PHANTOM_STEPS,
        BRIEF_DARKNESS,
        MANIFESTATION,
        DISTANT_KNOCK
    }
}
