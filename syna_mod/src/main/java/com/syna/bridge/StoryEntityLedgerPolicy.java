package com.syna.bridge;

import java.util.Map;
import java.util.Set;

final class StoryEntityLedgerPolicy {
    static final long TEMPLATE_COOLDOWN_TICKS = 20L * 60L * 5L;

    private StoryEntityLedgerPolicy() {}

    static boolean canSchedule(String scene, int horrorEpisodeId, long storyTick,
                               Set<String> episodeEvents, Set<String> persistentEvents,
                               Map<String, Long> lastTemplateTicks) {
        if (scene == null || episodeEvents.contains("scene:" + scene)) return false;
        if (persistentEvents.contains(persistentKey(scene, horrorEpisodeId, storyTick))) return false;
        Long lastTick = lastTemplateTicks.get(scene);
        return lastTick == null || storyTick < lastTick || storyTick - lastTick >= TEMPLATE_COOLDOWN_TICKS;
    }

    static String persistentKey(String scene, int horrorEpisodeId, long storyTick) {
        return switch (scene) {
            case "stalker" -> "story:stalker";
            case "ambush", "enforcer" -> "horror:" + horrorEpisodeId + ":" + scene;
            case "watcher" -> "day:" + Math.floorDiv(Math.max(0L, storyTick), 24_000L) + ":watcher";
            default -> "story:" + scene;
        };
    }
}
