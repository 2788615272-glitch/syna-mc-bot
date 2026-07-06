package com.syna.bridge.mobility.path;

import com.syna.bridge.mobility.MobilityRequest;

public interface PathFinder {
    PathFindingResult findPath(PathFindingContext context, MobilityRequest request);

    default PathFindingJob startJob(PathFindingContext context, MobilityRequest request) {
        return new PathFindingJob(context, request);
    }

    default PathFindingResult pollJob(PathFindingJob job, int nodeBudgetPerTick) {
        return findPath(job.context(), job.request());
    }
}