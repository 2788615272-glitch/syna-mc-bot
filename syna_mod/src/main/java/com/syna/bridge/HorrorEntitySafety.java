package com.syna.bridge;

import java.util.Locale;
import java.util.Set;

final class HorrorEntitySafety {
    private static final Set<String> DENIED_ENTITY_IDS = Set.of(
            "minecraft:ender_dragon", "minecraft:wither", "minecraft:creeper",
            "minecraft:ghast", "minecraft:enderman", "minecraft:silverfish", "minecraft:ravager",
            "minecraft:warden", "minecraft:elder_guardian", "minecraft:ender_crystal",
            "minecraft:tnt", "minecraft:command_block_minecart"
    );

    private HorrorEntitySafety() {}

    static String normalizeAllowedId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return null;
        return DENIED_ENTITY_IDS.contains(normalized) ? null : normalized;
    }
}
