package com.syna.bridge;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class BridgeCommandQueue {
    private static final BridgeCommandQueue INSTANCE = new BridgeCommandQueue();

    private final Queue<BridgeCommand> queue = new ConcurrentLinkedQueue<>();

    private BridgeCommandQueue() {
    }

    public static BridgeCommandQueue get() {
        return INSTANCE;
    }

    public void offer(BridgeCommand command) {
        if (command != null && command.type() != null && !command.type().isBlank()) {
            queue.offer(command);
        }
    }

    public void drainAndExecute() {
        BridgeCommand command;
        while ((command = queue.poll()) != null) {
            SynaController.get().handle(command);
        }
    }
}