package com.syna.bridge;

final class SynaBoredomPolicy {
    static final int MAX_BOREDOM = 100;
    static final int STARTING_BOREDOM = 12;
    static final long PASSIVE_INTERVAL_TICKS = 20L * 45L;
    static final long PRESSURE_INTERVAL_TICKS = 20L * 90L;

    private SynaBoredomPolicy() {}

    static int entertainmentGain(Activity activity, int priorRepetitions) {
        int repetitions = Math.max(0, priorRepetitions);
        double novelty = 1.0D / (1.0D + repetitions * 0.7D);
        return Math.max(1, (int) Math.round(activity.baseGain * novelty));
    }

    static int passiveIncrease(int cycleNumber) {
        return 1 + Math.max(0, cycleNumber - 1) / 3;
    }

    static int pressureFloor(long cycleTicks, int cycleNumber) {
        long intervals = Math.max(0L, cycleTicks) / PRESSURE_INTERVAL_TICKS;
        int acceleration = Math.max(0, cycleNumber - 1) * 2;
        return clamp((int) Math.min(Integer.MAX_VALUE, intervals) + acceleration);
    }

    static int afterTime(int boredom, long cycleTicks, int cycleNumber) {
        return Math.max(pressureFloor(cycleTicks, cycleNumber),
                clamp(boredom + passiveIncrease(cycleNumber)));
    }

    static int afterEntertainment(int boredom, long cycleTicks, int cycleNumber,
                                  Activity activity, int priorRepetitions) {
        int reduced = clamp(boredom - entertainmentGain(activity, priorRepetitions));
        return Math.max(pressureFloor(cycleTicks, cycleNumber), reduced);
    }

    static double horrorChance(int boredom) {
        double normalized = clamp(boredom) / (double) MAX_BOREDOM;
        return Math.min(0.85D, 0.05D + normalized * normalized * 0.80D);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_BOREDOM, value));
    }

    enum Activity {
        MINING(5),
        CRAFTING(9),
        HOSTILE_KILL(10),
        PASSIVE_KILL(4),
        DIMENSION_TRAVEL(18),
        BUILDING(7),
        CONVERSATION(3),
        DISCOVERY(12);

        private final int baseGain;

        Activity(int baseGain) {
            this.baseGain = baseGain;
        }
    }
}
