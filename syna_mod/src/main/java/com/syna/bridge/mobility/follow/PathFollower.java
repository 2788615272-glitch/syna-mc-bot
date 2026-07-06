package com.syna.bridge.mobility.follow;

import com.syna.bridge.AliceEntity;
import com.syna.bridge.mobility.path.PathPlan;
import net.minecraft.server.level.ServerLevel;

public interface PathFollower {
    void start(PathPlan plan);

    FollowResult tick(ServerLevel level, AliceEntity syna);

    void cancel(String reason);

    FollowerSnapshot snapshot();
}