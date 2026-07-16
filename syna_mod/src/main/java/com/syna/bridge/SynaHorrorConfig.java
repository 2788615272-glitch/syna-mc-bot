package com.syna.bridge;

import net.minecraftforge.common.ForgeConfigSpec;

final class SynaHorrorConfig {
    static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue COUNTDOWN_SECONDS;
    private static final ForgeConfigSpec.IntValue HUNT_SECONDS;
    private static final ForgeConfigSpec.DoubleValue HUNT_DAMAGE;
    private static final ForgeConfigSpec.BooleanValue WORLD_SCARRING;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("horror_syna");
        COUNTDOWN_SECONDS = builder
                .comment("Seconds between the confrontation and the hunt.")
                .defineInRange("countdown_seconds", 45, 10, 300);
        HUNT_SECONDS = builder
                .comment("Maximum duration of one pursuit before Syna withdraws.")
                .defineInRange("hunt_seconds", 90, 15, 600);
        HUNT_DAMAGE = builder
                .comment("Damage dealt by one Horror Syna hit (2 health points = 1 heart).")
                .defineInRange("hunt_damage", 7.0D, 0.0D, 40.0D);
        WORLD_SCARRING = builder
                .comment("Allow horror ambience to permanently turn grass into dirt and remove plants.")
                .define("world_scarring", false);
        builder.pop();
        SPEC = builder.build();
    }

    private SynaHorrorConfig() {
    }

    static int countdownTicks() {
        return COUNTDOWN_SECONDS.get() * 20;
    }

    static int huntTicks() {
        return HUNT_SECONDS.get() * 20;
    }

    static float huntDamage() {
        return HUNT_DAMAGE.get().floatValue();
    }

    static boolean worldScarring() {
        return WORLD_SCARRING.get();
    }
}
