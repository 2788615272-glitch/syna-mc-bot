package com.syna.bridge;

import com.google.gson.JsonObject;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class BridgeIntentQueue {
    private static final BridgeIntentQueue INSTANCE = new BridgeIntentQueue();
    private final Queue<PendingIntent> queue = new ConcurrentLinkedQueue<>();

    private BridgeIntentQueue() {}

    public static BridgeIntentQueue get() {
        return INSTANCE;
    }

    public CompletableFuture<JsonObject> offer(JsonObject request) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        queue.offer(new PendingIntent(request, future));
        return future;
    }

    public void drainAndExecute() {
        PendingIntent pending;
        while ((pending = queue.poll()) != null) {
            try {
                pending.future.complete(SynaController.get().executeIntent(pending.request));
            } catch (Exception error) {
                JsonObject receipt = new JsonObject();
                receipt.addProperty("accepted", false);
                receipt.addProperty("completed", false);
                receipt.addProperty("result", "internal_error");
                pending.future.complete(receipt);
            }
        }
    }

    private record PendingIntent(JsonObject request, CompletableFuture<JsonObject> future) {}
}
