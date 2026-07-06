package com.syna.bridge.mobility.resource;

import com.syna.bridge.mobility.ResourceRequirement;

public record ResourceSubtaskRequest(
        ResourceSubtaskType type,
        ResourceRequirement requirement,
        String resumeRequestId
) {}