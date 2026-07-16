package com.syna.bridge;

final class TunnelRevealPolicy {
    private TunnelRevealPolicy() {}

    static boolean qualifiesMining(int playerY, int seaLevel, int verticalDelta, int horizontalDelta) {
        if (playerY > seaLevel - 4) return false;
        if (verticalDelta > 1 || horizontalDelta > 1) return false;
        return true;
    }

    static int advanceConsecutive(int current, boolean sameDirection, int tickGap, int horizontalGap) {
        if (!sameDirection || tickGap < 0 || tickGap > 200 || horizontalGap > 2) return 1;
        return Math.min(64, Math.max(0, current) + 1);
    }

    static int scoreCandidate(boolean covered, boolean solidFloor, boolean carveLower, boolean carveUpper,
                              boolean straightAhead, int adjacentSolidFaces, int distance) {
        if (!covered || !solidFloor || !carveLower || !carveUpper) return -1;
        int score = 20;
        if (straightAhead) score += 4;
        score += Math.max(0, Math.min(4, adjacentSolidFaces));
        score += Math.max(0, 10 - Math.max(0, distance));
        return score;
    }
}
