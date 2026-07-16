package com.syna.bridge;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TrueNameMysteryPolicy {
    static final int REQUIRED_CLUES = 3;
    static final long ATTEMPT_COOLDOWN_TICKS = 20L * 10L;
    static final double RITUAL_RADIUS_SQR = 36.0D;

    private static final String[] FIRST = {"vae", "sel", "nor", "ira", "thal", "ely", "mir", "cal"};
    private static final String[] SECOND = {"ra", "no", "the", "li", "sa", "ve", "mi", "ora"};
    private static final String[] THIRD = {"n", "is", "ra", "el", "th", "yn", "a", "eth"};
    private static final Pattern RITUAL = Pattern.compile(
            "^(?:我以你的真名封印你|以你的真名封印你|我以真名封印你|真名封印)\\s*[：:]\\s*([a-zA-Z-]{3,32})[。.!！]?$",
            Pattern.CASE_INSENSITIVE);

    private TrueNameMysteryPolicy() {}

    static String[] fragments(long worldSeed) {
        long mixed = mix(worldSeed ^ 0x53_59_4E_41_4CL);
        String first = FIRST[(int) Math.floorMod(mixed, FIRST.length)];
        mixed = mix(mixed + 0x9E3779B97F4A7C15L);
        String second = SECOND[(int) Math.floorMod(mixed, SECOND.length)];
        mixed = mix(mixed + 0xD1B54A32D192ED03L);
        String third = THIRD[(int) Math.floorMod(mixed, THIRD.length)];
        return new String[]{first, second, third};
    }

    static String trueName(long worldSeed) {
        return String.join("", fragments(worldSeed));
    }

    static String parseRitualCandidate(String text) {
        Matcher matcher = RITUAL.matcher(text == null ? "" : text.trim());
        return matcher.matches() ? normalizeName(matcher.group(1)) : null;
    }

    static boolean hasValidRitualSyntax(String text) {
        return RITUAL.matcher(text == null ? "" : text.trim()).matches();
    }

    static boolean looksLikeRitual(String text) {
        String value = text == null ? "" : text.trim();
        return value.startsWith("我以你的真名封印你")
                || value.startsWith("以你的真名封印你")
                || value.startsWith("我以真名封印你")
                || value.startsWith("真名封印");
    }

    static boolean matches(long worldSeed, String candidate) {
        return trueName(worldSeed).equals(normalizeName(candidate));
    }

    static String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
