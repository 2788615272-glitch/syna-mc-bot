package com.syna.bridge;

public record BridgeCommand(
        String type,
        String text,
        String item,
        String player,
        String reason,
        String owner,
        Double x,
        Double y,
        Double z,
        Integer count,
        Integer seconds
) {
}