package com.syna.bridge;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerAttentionTracker {
    private static final PlayerAttentionTracker INSTANCE = new PlayerAttentionTracker();
    private final Map<UUID, AttentionState> states = new HashMap<>();
    private final Map<ObservationKey, ActorAttentionState> actorStates = new HashMap<>();

    private PlayerAttentionTracker() {}

    public static PlayerAttentionTracker get() {
        return INSTANCE;
    }

    public void tick(MinecraftServer server) {
        if (server == null) return;
        AliceEntity syna = SynaController.get().getSyna();
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            AttentionState state = states.computeIfAbsent(player.getUUID(), ignored -> new AttentionState());
            state.update(player);
            if (syna != null && syna.isAlive()) observe(player, syna);
        }
        states.keySet().removeIf(uuid -> !online.contains(uuid));
        long now = server.overworld().getGameTime();
        actorStates.entrySet().removeIf(entry -> !online.contains(entry.getKey().playerUuid)
                || now - entry.getValue().lastObservedGameTime > 200L);
    }

    public boolean isLookingAt(ServerPlayer player, AliceEntity syna) {
        return observe(player, syna).visible();
    }

    public ActorSnapshot observe(ServerPlayer player, Entity actor) {
        if (player == null || actor == null || !actor.isAlive()) return ActorSnapshot.EMPTY;
        AttentionState playerState = states.computeIfAbsent(player.getUUID(), ignored -> new AttentionState());
        ObservationKey key = new ObservationKey(player.getUUID(), actor.getUUID());
        ActorAttentionState actorState = actorStates.computeIfAbsent(key, ignored -> new ActorAttentionState());
        return actorState.update(player, actor, playerState);
    }

    public Snapshot snapshot(ServerPlayer player) {
        AttentionState state = player == null ? null : states.get(player.getUUID());
        return state == null ? Snapshot.EMPTY : state.snapshot();
    }

    public JsonObject toJson(ServerPlayer player) {
        Snapshot snapshot = snapshot(player);
        JsonObject json = new JsonObject();
        json.addProperty("lookingAtSyna", snapshot.lookingAtSyna());
        json.addProperty("lookingTicks", snapshot.lookingTicks());
        json.addProperty("awayTicks", snapshot.awayTicks());
        json.addProperty("turnDegrees", snapshot.turnDegrees());
        json.addProperty("suddenTurn", snapshot.suddenTurn());
        json.addProperty("moving", snapshot.moving());
        json.addProperty("stillTicks", snapshot.stillTicks());
        return json;
    }

    private static Visibility visibility(ServerPlayer player, Entity actor) {
        if (player.level() != actor.level() || player.distanceToSqr(actor) > 64.0D * 64.0D) {
            double distance = player.level() == actor.level()
                    ? Math.sqrt(player.distanceToSqr(actor)) : Double.POSITIVE_INFINITY;
            return new Visibility(false, false, distance);
        }
        Vec3 eye = player.getEyePosition();
        Vec3 target = actor.getEyePosition();
        Vec3 direction = target.subtract(eye);
        double distance = direction.length();
        if (distance < 0.01D) return new Visibility(true, true, distance);
        boolean inView = AttentionPolicy.isInView(player.getLookAngle().dot(direction.normalize()));
        if (!inView) return new Visibility(false, false, distance);
        BlockHitResult hit = player.level().clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        boolean visible = hit.getType() == HitResult.Type.MISS
                || hit.getLocation().distanceToSqr(eye) >= distance * distance - 0.5D;
        return new Visibility(true, visible, distance);
    }

    public record Snapshot(boolean lookingAtSyna, int lookingTicks, int awayTicks, double turnDegrees,
                           boolean suddenTurn, boolean moving, int stillTicks) {
        private static final Snapshot EMPTY = new Snapshot(false, 0, 0, 0.0D, false, false, 0);
    }

    public record ActorSnapshot(boolean inView, boolean visible, int lookingTicks, int unseenTicks,
                                double distance, boolean suddenTurn, boolean approaching) {
        private static final ActorSnapshot EMPTY = new ActorSnapshot(false, false, 0, 0,
                Double.POSITIVE_INFINITY, false, false);
    }

    private static final class AttentionState {
        private Vec3 lastLook;
        private Vec3 lastPosition;
        private boolean lookingAtSyna;
        private int lookingTicks;
        private int awayTicks;
        private double turnDegrees;
        private boolean moving;
        private int stillTicks;

        private void update(ServerPlayer player) {
            Vec3 look = player.getLookAngle().normalize();
            Vec3 position = player.position();
            turnDegrees = lastLook == null ? 0.0D : AttentionPolicy.turnDegrees(lastLook.dot(look));
            moving = lastPosition != null && lastPosition.distanceToSqr(position) > 0.0009D;
            stillTicks = moving ? 0 : stillTicks + 1;
            lastLook = look;
            lastPosition = position;
        }

        private void updateSyna(ActorSnapshot observation) {
            lookingAtSyna = observation.visible();
            lookingTicks = observation.lookingTicks();
            awayTicks = observation.unseenTicks();
        }

        private Snapshot snapshot() {
            return new Snapshot(lookingAtSyna, lookingTicks, awayTicks, turnDegrees,
                    AttentionPolicy.isSuddenTurn(turnDegrees), moving, stillTicks);
        }
    }
    private static final class ActorAttentionState {
        private int lastSampleTick = Integer.MIN_VALUE;
        private int lookingTicks;
        private int unseenTicks;
        private double lastDistance = Double.POSITIVE_INFINITY;
        private long lastObservedGameTime;
        private ActorSnapshot snapshot = ActorSnapshot.EMPTY;

        private ActorSnapshot update(ServerPlayer player, Entity actor, AttentionState playerState) {
            if (lastSampleTick == player.tickCount) return snapshot;
            Visibility visibility = visibility(player, actor);
            boolean approaching = AttentionPolicy.isApproaching(lastDistance, visibility.distance);
            lookingTicks = visibility.visible ? lookingTicks + 1 : 0;
            unseenTicks = visibility.visible ? 0 : unseenTicks + 1;
            lastDistance = visibility.distance;
            lastSampleTick = player.tickCount;
            lastObservedGameTime = player.serverLevel().getGameTime();
            snapshot = new ActorSnapshot(visibility.inView, visibility.visible, lookingTicks, unseenTicks,
                    visibility.distance, AttentionPolicy.isSuddenTurn(playerState.turnDegrees), approaching);
            if (actor instanceof AliceEntity) playerState.updateSyna(snapshot);
            return snapshot;
        }
    }

    private record ObservationKey(UUID playerUuid, UUID actorUuid) {}
    private record Visibility(boolean inView, boolean visible, double distance) {}
}
