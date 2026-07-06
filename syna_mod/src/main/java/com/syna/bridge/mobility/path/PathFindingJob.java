package com.syna.bridge.mobility.path;

import com.syna.bridge.mobility.MobilityRequest;

public record PathFindingJob(
        PathFindingContext context,
        MobilityRequest request
) {}