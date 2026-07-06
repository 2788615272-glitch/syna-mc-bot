package com.syna.bridge.mobility.follow;

import com.syna.bridge.AliceEntity;
import com.syna.bridge.mobility.path.PathPlan;
import com.syna.bridge.mobility.path.Waypoint;
import net.minecraft.server.level.ServerLevel;

public final class BasicPathFollower implements PathFollower {
    private PathPlan plan;
    private int waypointIndex;
    private FollowerState state = FollowerState.IDLE;
    private String detail = "idle";

    @Override
    public void start(PathPlan plan) {
        this.plan = plan;
        this.waypointIndex = 0;
        this.state = FollowerState.MOVING;
        this.detail = "started";
    }

    @Override
    public FollowResult tick(ServerLevel level, AliceEntity syna) {
        if (plan == null || plan.isEmpty()) {
            state = FollowerState.DONE;
            detail = "empty_path";
            return FollowResult.completed(detail);
        }
        Waypoint waypoint = plan.waypointAtOrNull(waypointIndex);
        if (waypoint == null) {
            state = FollowerState.DONE;
            detail = "path_completed";
            return FollowResult.completed(detail);
        }

        // Skeleton executor: only WALK is actively delegated to vanilla navigation for now.
        if (waypoint.standPos() != null) {
            syna.getNavigation().moveTo(waypoint.standPos().getX() + 0.5D, waypoint.standPos().getY(), waypoint.standPos().getZ() + 0.5D, 1.05D);
            if (syna.blockPosition().closerThan(waypoint.standPos(), 1.5D)) {
                waypointIndex++;
            }
        }
        detail = "following_waypoint:" + waypointIndex + "/" + plan.waypoints().size();
        return waypointIndex >= plan.waypoints().size() ? FollowResult.completed("path_completed") : FollowResult.running(detail);
    }

    @Override
    public void cancel(String reason) {
        this.state = FollowerState.IDLE;
        this.detail = reason == null ? "cancelled" : reason;
        this.plan = null;
        this.waypointIndex = 0;
    }

    @Override
    public FollowerSnapshot snapshot() {
        return new FollowerSnapshot(state, waypointIndex, detail);
    }
}