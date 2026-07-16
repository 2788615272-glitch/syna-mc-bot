package com.syna.bridge;

final class AttentionPolicy {
    private AttentionPolicy() {}

    static double turnDegrees(double normalizedDotProduct) {
        double clamped = Math.max(-1.0D, Math.min(1.0D, normalizedDotProduct));
        return Math.toDegrees(Math.acos(clamped));
    }

    static boolean isSuddenTurn(double degrees) {
        return degrees >= 55.0D;
    }

    static boolean isInView(double normalizedDotProduct) {
        return normalizedDotProduct >= 0.35D;
    }

    static boolean isApproaching(double previousDistance, double currentDistance) {
        return Double.isFinite(previousDistance) && currentDistance < previousDistance - 0.025D;
    }
}
