package com.syna.bridge;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;

/**
 * Shared "is this player a bot?" check.
 *
 * Extracted from BotGuard so other systems (BlueprintProtection, etc.) can
 * apply bot-only rules without duplicating the prefix list.
 *
 * BotGuard remains the source of truth for the prefix/exact-name sets via
 * its public addBotName API; this class just exposes the read side.
 */
public final class BotIdentity {
    /** Prefixes — any player whose name starts with one of these is a bot. */
    private static final Set<String> BOT_PREFIXES = new HashSet<>();
    /** Exact names registered via addBotName(). */
    private static final Set<String> BOT_NAMES = new HashSet<>();

    static {
        BOT_PREFIXES.add("Syna");
        BOT_PREFIXES.add("Andy");
        BOT_PREFIXES.add("Jill");
        BOT_PREFIXES.add("syna");
    }

    private BotIdentity() {}

    public static void addBotName(String name) {
        if (name != null && !name.isBlank()) {
            BOT_NAMES.add(name);
        }
    }

    public static boolean isBot(String playerName) {
        if (playerName == null) return false;
        if (BOT_NAMES.contains(playerName)) return true;
        for (String prefix : BOT_PREFIXES) {
            if (playerName.startsWith(prefix)) return true;
        }
        return false;
    }

    public static boolean isBot(ServerPlayer player) {
        if (player == null) return false;
        return isBot(player.getGameProfile().getName());
    }
}
