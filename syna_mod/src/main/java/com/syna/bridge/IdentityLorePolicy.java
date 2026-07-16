package com.syna.bridge;

import java.util.Set;

final class IdentityLorePolicy {
    private IdentityLorePolicy() {}

    static int allowedVersion(Topic topic, int chapter, Set<String> clues) {
        if (topic == null) return 0;
        boolean identityFragment = clues != null && clues.contains("identity_fragment");
        return switch (topic) {
            case ORIGIN -> identityFragment ? 2 : 1;
            case NAME -> chapter >= 2 ? (identityFragment ? 2 : 1) : 0;
            case BELLS -> chapter >= 3 ? (identityFragment ? 2 : 1) : 0;
        };
    }

    static Topic parseTopic(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "origin" -> Topic.ORIGIN;
            case "name" -> Topic.NAME;
            case "bells" -> Topic.BELLS;
            default -> null;
        };
    }

    enum Topic {
        ORIGIN("origin"),
        NAME("name"),
        BELLS("bells");

        final String id;

        Topic(String id) {
            this.id = id;
        }
    }
}
