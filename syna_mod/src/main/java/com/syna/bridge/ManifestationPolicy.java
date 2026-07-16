package com.syna.bridge;

final class ManifestationPolicy {
    static final int IDLE_LIFETIME_TICKS = 20 * 16;
    static final int GIFT_LIFETIME_TICKS = 20 * 10;

    private ManifestationPolicy() {}

    static boolean shouldDepart(int lifetimeTicks, boolean calm, boolean idle) {
        return lifetimeTicks <= 0 && calm && idle;
    }
}
