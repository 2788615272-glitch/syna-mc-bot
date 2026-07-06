package com.syna.bridge.mobility;

public record ResourceRequirement(
        ResourceType type,
        int count,
        String reason
) {}