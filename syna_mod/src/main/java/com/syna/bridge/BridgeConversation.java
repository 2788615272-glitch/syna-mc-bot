package com.syna.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class BridgeConversation {
    public record Event(long id, String type, String player, String text,
                        String eventKey, String item, int count) {}

    private static final BridgeConversation INSTANCE = new BridgeConversation();
    private static final int MESSAGE_LIMIT = 64;

    private final Deque<Event> events = new ArrayDeque<>();
    private long nextId = 1L;

    private BridgeConversation() {}

    public static BridgeConversation get() {
        return INSTANCE;
    }

    public synchronized void record(String player, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        add(new Event(nextId++, "player_chat", player == null ? "" : player,
                text, "", "", 0));
    }

    public synchronized void recordEpisodeHelp(String player, String eventKey, String item, int count) {
        if (eventKey == null || eventKey.isBlank() || item == null || item.isBlank() || count <= 0) return;
        add(new Event(nextId++, "episode_help", player == null ? "" : player,
                "", eventKey, item, count));
    }

    private void add(Event event) {
        events.addLast(event);
        while (events.size() > MESSAGE_LIMIT) {
            events.removeFirst();
        }
    }

    public synchronized List<Event> after(long id) {
        List<Event> result = new ArrayList<>();
        for (Event event : events) {
            if (event.id() > id) {
                result.add(event);
            }
        }
        return result;
    }

    public synchronized long latestId() {
        Event latest = events.peekLast();
        return latest == null ? 0L : latest.id();
    }
}
