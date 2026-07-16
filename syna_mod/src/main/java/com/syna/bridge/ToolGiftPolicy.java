package com.syna.bridge;

final class ToolGiftPolicy {
    static final long MIN_BREAK_INTERVAL_TICKS = 20L * 60L * 15L;

    private ToolGiftPolicy() {}

    static boolean canOffer(int cycleNumber, long currentTick, int lastGiftCycle, long lastEligibleBreakTick) {
        if (cycleNumber <= 0 || cycleNumber == lastGiftCycle) return false;
        return lastEligibleBreakTick < 0L
                || currentTick - lastEligibleBreakTick >= MIN_BREAK_INTERVAL_TICKS;
    }
}
