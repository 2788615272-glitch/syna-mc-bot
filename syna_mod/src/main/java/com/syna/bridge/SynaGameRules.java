package com.syna.bridge;

import net.minecraft.world.level.GameRules;

final class SynaGameRules {
    static final GameRules.Key<GameRules.BooleanValue> FINAL_CYCLE = GameRules.register(
            "synaFinalCycle", GameRules.Category.MISC, GameRules.BooleanValue.create(true));
    static final GameRules.Key<GameRules.BooleanValue> HORROR_EVENTS = GameRules.register(
            "synaHorrorEvents", GameRules.Category.MISC, GameRules.BooleanValue.create(true));

    private SynaGameRules() {}

    static void bootstrap() {
        // Loading this class registers both rules before worlds start ticking.
    }
}
