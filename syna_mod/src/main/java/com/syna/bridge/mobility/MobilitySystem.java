package com.syna.bridge.mobility;

import com.syna.bridge.AliceEntity;
import com.syna.bridge.mobility.follow.BasicPathFollower;
import com.syna.bridge.mobility.follow.FollowResult;
import com.syna.bridge.mobility.follow.FollowResultType;
import com.syna.bridge.mobility.follow.PathFollower;
import com.syna.bridge.mobility.path.AStarPathFinder;
import com.syna.bridge.mobility.path.PathFinder;
import com.syna.bridge.mobility.path.PathFindingContext;
import com.syna.bridge.mobility.path.PathFindingResult;
import com.syna.bridge.mobility.path.PathFindingStatus;
import net.minecraft.server.level.ServerLevel;

public final class MobilitySystem {
    private final PathFinder pathFinder;
    private final PathFollower pathFollower;
    private final MobilityRuntime runtime;

    public MobilitySystem() {
        this.pathFinder = new AStarPathFinder();
        this.pathFollower = new BasicPathFollower();
        this.runtime = new MobilityRuntime();
    }

    public void submit(MobilityRequest request) {
        runtime.submit(request);
    }

    public void updateRequest(MobilityRequest request) {
        if (request != null) {
            runtime.submit(request);
        }
    }

    public void cancel(String reason) {
        pathFollower.cancel(reason);
        runtime.cancel(reason);
    }

    public void resumeAfterResource(String reason) {
        runtime.resumePlanningAfterResource(reason);
    }

    public MobilityTickResult tick(AliceEntity syna) {
        if (syna == null || !(syna.level() instanceof ServerLevel level)) {
            return MobilityTickResult.idle();
        }
        if (runtime.status() == MobilityStatus.IDLE || runtime.request() == null) {
            return MobilityTickResult.idle();
        }
        if (runtime.status() == MobilityStatus.PLANNING) {
            PathFindingContext context = PathFindingContext.from(level, syna);
            PathFindingResult result = pathFinder.findPath(context, runtime.request());
            runtime.markPathResult(result);
            if (result.status() == PathFindingStatus.SUCCESS) {
                runtime.startFollowing(result.plan());
                pathFollower.start(result.plan());
                return MobilityTickResult.running("path_ready");
            }
            if (result.status() == PathFindingStatus.NEED_RESOURCE) {
                runtime.suspend(result.missingResource());
                return MobilityTickResult.needResource(result.missingResource());
            }
            runtime.fail(result.reason());
            return MobilityTickResult.failed(result.reason());
        }
        if (runtime.status() == MobilityStatus.FOLLOWING) {
            FollowResult result = pathFollower.tick(level, syna);
            if (result.type() == FollowResultType.PATH_COMPLETED) {
                runtime.complete();
                return MobilityTickResult.completed(result.reason());
            }
            if (result.type() == FollowResultType.FAILED || result.type() == FollowResultType.STUCK) {
                runtime.fail(result.reason());
                return MobilityTickResult.failed(result.reason());
            }
            return MobilityTickResult.running(result.reason());
        }
        if (runtime.status() == MobilityStatus.SUSPENDED) {
            return MobilityTickResult.needResource(runtime.snapshot().missingResource());
        }
        return MobilityTickResult.idle();
    }

    public MobilitySnapshot snapshot() {
        return runtime.snapshot();
    }
}