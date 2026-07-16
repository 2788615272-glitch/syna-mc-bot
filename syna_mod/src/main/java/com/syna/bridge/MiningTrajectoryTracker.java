package com.syna.bridge;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MiningTrajectoryTracker {
    private static final MiningTrajectoryTracker INSTANCE = new MiningTrajectoryTracker();
    private final Map<UUID, Track> tracks = new HashMap<>();

    private MiningTrajectoryTracker() {}

    public static MiningTrajectoryTracker get() {
        return INSTANCE;
    }

    public Observation observe(ServerPlayer player, BlockPos brokenPos, BlockState broken) {
        if (player == null || brokenPos == null || broken == null || broken.isAir()) return Observation.REJECTED;
        Direction forward = player.getDirection();
        BlockPos expected = player.blockPosition().relative(forward);
        int verticalDelta = Math.abs(brokenPos.getY() - player.blockPosition().getY());
        int horizontalDelta = Math.abs(expected.getX() - brokenPos.getX())
                + Math.abs(expected.getZ() - brokenPos.getZ());
        if (!TunnelRevealPolicy.qualifiesMining((int) Math.floor(player.getY()),
                player.serverLevel().getSeaLevel(), verticalDelta, horizontalDelta)) return Observation.REJECTED;

        Track track = tracks.computeIfAbsent(player.getUUID(), ignored -> new Track());
        long tick = player.server.getTickCount();
        int gap = track.lastPos == null ? Integer.MAX_VALUE
                : Math.abs(track.lastPos.getX() - brokenPos.getX()) + Math.abs(track.lastPos.getZ() - brokenPos.getZ());
        boolean sameDirection = track.direction == forward;
        track.consecutive = track.lastPos == null ? 1 : TunnelRevealPolicy.advanceConsecutive(
                track.consecutive, sameDirection, (int) Math.min(Integer.MAX_VALUE, tick - track.lastTick), gap);
        track.directionChanges += track.lastPos != null && !sameDirection ? 1 : 0;
        track.direction = forward;
        track.lastPos = brokenPos.immutable();
        track.lastTick = tick;
        return new Observation(true, track.consecutive, track.directionChanges, forward);
    }

    public Candidate findBestCandidate(ServerPlayer player, BlockPos brokenPos) {
        if (player == null || brokenPos == null) return null;
        Track track = tracks.get(player.getUUID());
        Direction forward = track == null || track.direction == null ? player.getDirection() : track.direction;
        Direction side = forward.getClockWise();
        ServerLevel level = player.serverLevel();
        Candidate best = null;
        for (int distance = 2; distance <= 8; distance++) {
            for (int sideOffset = -1; sideOffset <= 1; sideOffset++) {
                for (int yOffset : new int[]{0, -1, 1}) {
                    BlockPos pos = brokenPos.relative(forward, distance)
                            .relative(side, sideOffset).offset(0, yOffset, 0);
                    BlockPos cover = pos.relative(forward.getOpposite());
                    boolean covered = level.getBlockState(cover).isSolid();
                    boolean floor = level.getBlockState(pos.below()).isSolid();
                    boolean lower = canCarve(level, pos);
                    boolean upper = canCarve(level, pos.above());
                    int adjacent = 0;
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        if (level.getBlockState(pos.relative(direction)).isSolid()) adjacent++;
                    }
                    int score = TunnelRevealPolicy.scoreCandidate(covered, floor, lower, upper,
                            sideOffset == 0, adjacent, distance);
                    if (score >= 0 && (best == null || score > best.score)) {
                        best = new Candidate(pos.immutable(), score, distance, sideOffset);
                    }
                }
            }
        }
        if (track != null) track.lastCandidateScore = best == null ? -1 : best.score;
        return best;
    }

    public JsonObject toJson(ServerPlayer player) {
        JsonObject json = new JsonObject();
        Track track = player == null ? null : tracks.get(player.getUUID());
        if (track == null) {
            json.addProperty("consecutive", 0);
            json.addProperty("directionChanges", 0);
            json.addProperty("candidateScore", -1);
            return json;
        }
        json.addProperty("consecutive", track.consecutive);
        json.addProperty("direction", track.direction == null ? "none" : track.direction.getName());
        json.addProperty("directionChanges", track.directionChanges);
        json.addProperty("candidateScore", track.lastCandidateScore);
        return json;
    }

    private boolean canCarve(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return level.getBlockEntity(pos) == null
                && !state.is(Tags.Blocks.ORES)
                && state.getDestroySpeed(level, pos) >= 0.0F
                && state.getFluidState().isEmpty();
    }

    public record Observation(boolean accepted, int consecutive, int directionChanges, Direction direction) {
        private static final Observation REJECTED = new Observation(false, 0, 0, null);
    }

    public record Candidate(BlockPos pos, int score, int distance, int sideOffset) {}

    private static final class Track {
        private BlockPos lastPos;
        private Direction direction;
        private long lastTick;
        private int consecutive;
        private int directionChanges;
        private int lastCandidateScore = -1;
    }
}
