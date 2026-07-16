package com.syna.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class SynaManifestationDirector {
    private static final SynaManifestationDirector INSTANCE = new SynaManifestationDirector();
    private static final int DEFAULT_LIFETIME = ManifestationPolicy.IDLE_LIFETIME_TICKS;
    private static final int GIFT_LIFETIME = ManifestationPolicy.GIFT_LIFETIME_TICKS;

    private int lifetimeTicks;
    private int unseenTicks;
    private int relocateCooldown;
    private String reason = "none";
    private String lastLine = "";
    private boolean currentlyVisible;
    private boolean vanishWhenDiscovered;
    private int discoveredTicks;
    private UUID lookBehindTarget;
    private int lookBehindTicks;
    private int lookBehindVisibleTicks;
    private boolean lookBehindCueActive;

    private SynaManifestationDirector() {}

    public static SynaManifestationDirector get() {
        return INSTANCE;
    }

    public void onManifested(String reason, boolean shortVisit) {
        clearLookBehindPrank();
        this.reason = reason == null ? "manifested" : reason;
        lifetimeTicks = shortVisit ? GIFT_LIFETIME : DEFAULT_LIFETIME;
        unseenTicks = 0;
        discoveredTicks = 0;
        vanishWhenDiscovered = "tunnel_reveal".equals(this.reason);
        relocateCooldown = 20 * 12;
        BridgeState.get().setLastEvent("manifested:" + this.reason);
    }

    public void tick(MinecraftServer server) {
        if (server == null) return;
        AliceEntity syna = SynaController.get().getSyna();
        if (syna == null) {
            lifetimeTicks = 0;
            clearLookBehindPrank();
            return;
        }
        if (!"calm".equals(SynaController.get().getHorrorStage()) || !"idle".equals(SynaController.get().getCurrentTask())) {
            unseenTicks = 0;
            return;
        }
        syna.getNavigation().stop();
        syna.setDeltaMovement(0.0D, syna.getDeltaMovement().y, 0.0D);

        if (lookBehindTarget != null && tickLookBehindPrank(server, syna)) return;

        if (lifetimeTicks > 0) lifetimeTicks--;
        if (relocateCooldown > 0) relocateCooldown--;
        boolean visible = isVisibleToAnyPlayer(server, syna);
        currentlyVisible = visible;
        unseenTicks = visible ? 0 : unseenTicks + 1;

        if (vanishWhenDiscovered && visible) {
            discoveredTicks++;
            if (discoveredTicks >= 3) {
                SynaController.get().handle(new BridgeCommand("despawn_syna", null, null, null, null, null,
                        null, null, null, null, null));
                BridgeState.get().setLastEvent("manifest_tunnel_discovered");
                vanishWhenDiscovered = false;
                return;
            }
        }

        SynaStoryData data = SynaStoryData.get(server);
        if (unseenTicks >= 20 && relocateCooldown <= 0 && data.episodeEvents.add("manifest:relocate")) {
            ServerPlayer player = nearestPlayer(server, syna);
            if (player != null && relocateBehindPlayer(player, syna)) {
                relocateCooldown = 20 * 15;
                unseenTicks = 0;
                data.setDirty();
                BridgeState.get().setLastEvent("manifest_relocated:unseen");
                return;
            }
        }

        if (ManifestationPolicy.shouldDepart(lifetimeTicks, true, true)) {
            leaveTrace(server, syna, data);
            SynaController.get().handle(new BridgeCommand("despawn_syna", null, null, null, null, null,
                    null, null, null, null, null));
            BridgeState.get().setLastEvent("manifest_departed:" + reason);
        }
    }

    public void shortenVisit() {
        if (lifetimeTicks <= 0 || lifetimeTicks > GIFT_LIFETIME) lifetimeTicks = GIFT_LIFETIME;
    }

    public void playArrival(AliceEntity syna) {
        if (syna != null && syna.level() instanceof ServerLevel level) {
            playManifestEffect(level, syna.position(), true);
        }
    }

    public void playDeparture(AliceEntity syna) {
        if (syna != null && syna.level() instanceof ServerLevel level) {
            playManifestEffect(level, syna.position(), false);
        }
    }

    private void playManifestEffect(ServerLevel level, Vec3 position, boolean arrival) {
        Vec3 center = position.add(0.0D, 1.0D, 0.0D);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                center.x, center.y, center.z, arrival ? 32 : 24, 0.4D, 0.8D, 0.4D, 0.04D);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLASH,
                center.x, center.y, center.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.playSound(null, position.x, position.y, position.z, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.AMBIENT, arrival ? 0.55F : 0.7F, arrival ? 1.35F : 0.65F);
    }

    public void rememberLine(String text) {
        if (text == null || text.isBlank()) return;
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        lastLine = clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    public boolean beginLookBehindPrank(ServerPlayer player) {
        if (player == null) return false;
        AliceEntity syna = SynaController.get().getSyna();
        if (syna == null) {
            SynaController.get().summonSilently(player, "look_behind_prank", true);
            syna = SynaController.get().getSyna();
        }
        if (syna == null) return false;
        lookBehindTarget = player.getUUID();
        lookBehindTicks = 20 * 10;
        lookBehindVisibleTicks = 0;
        lookBehindCueActive = false;
        lifetimeTicks = Math.max(lifetimeTicks, lookBehindTicks);
        BridgeState.get().setLastEvent("prank_look_behind_armed:" + player.getGameProfile().getName());
        return true;
    }

    public boolean isDirectingLookAway() {
        return lookBehindTarget != null && lookBehindCueActive;
    }

    public boolean revealAfterMining(ServerPlayer player, BlockPos brokenPos) {
        if (player == null || brokenPos == null) return false;
        if (SynaController.get().getSyna() != null) return false;
        ServerLevel level = player.serverLevel();
        MiningTrajectoryTracker.Candidate candidate = MiningTrajectoryTracker.get()
                .findBestCandidate(player, brokenPos);
        if (candidate == null) {
            BridgeState.get().setLastEvent("manifest_tunnel_blocked:no_safe_candidate");
            return false;
        }
        BlockPos pos = candidate.pos();
        BlockState lower = level.getBlockState(pos);
        BlockState upper = level.getBlockState(pos.above());
        if (!canCarve(level, pos, lower) || !canCarve(level, pos.above(), upper)) return false;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
        AliceEntity syna = SynaController.get().summonSilentlyAt(player, pos, "tunnel_reveal", true);
        if (syna == null) {
            level.setBlock(pos, lower, 3);
            level.setBlock(pos.above(), upper, 3);
            return false;
        }
        syna.getNavigation().stop();
        BridgeState.get().setLastEvent("manifest_tunnel_prepared:" + brokenPos.toShortString()
                + ":score=" + candidate.score() + ":distance=" + candidate.distance()
                + ":side=" + candidate.sideOffset());
        return true;
    }

    public boolean debugRevealNearby(ServerPlayer player) {
        if (player == null) return false;
        if (SynaController.get().getSyna() != null) {
            SynaController.get().handle(new BridgeCommand("despawn_syna", null, null, null, null, null,
                    null, null, null, null, null));
        }
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        horizontal = horizontal.normalize();
        Vec3 side = new Vec3(-horizontal.z, 0.0D, horizontal.x);
        Vec3[] candidates = {
                player.position().subtract(horizontal.scale(4.0D)),
                player.position().subtract(horizontal.scale(3.0D)).add(side.scale(2.0D)),
                player.position().subtract(horizontal.scale(3.0D)).subtract(side.scale(2.0D)),
                player.position().add(side.scale(3.0D)),
                player.position().subtract(side.scale(3.0D))
        };
        for (Vec3 candidate : candidates) {
            BlockPos base = BlockPos.containing(candidate);
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos pos = base.offset(0, dy, 0);
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
                        || !level.getBlockState(pos.below()).isSolid()) continue;
                AliceEntity syna = SynaController.get().summonSilentlyAt(player, pos, "tunnel_reveal", true);
                if (syna != null) {
                    BridgeState.get().setLastEvent("debug_tunnel_reveal:" + pos.toShortString());
                    return true;
                }
            }
        }
        BridgeState.get().setLastEvent("debug_tunnel_reveal_failed:no_safe_space");
        return false;
    }

    private boolean canCarve(ServerLevel level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) == null
                && state.getDestroySpeed(level, pos) >= 0.0F
                && state.getFluidState().isEmpty();
    }

    private boolean tickLookBehindPrank(MinecraftServer server, AliceEntity syna) {
        ServerPlayer player = server.getPlayerList().getPlayer(lookBehindTarget);
        if (player == null || player.level() != syna.level() || --lookBehindTicks <= 0) {
            clearLookBehindPrank();
            BridgeState.get().setLastEvent("prank_look_behind_expired");
            return false;
        }

        if (!lookBehindCueActive) {
            PlayerAttentionTracker.Snapshot attention = PlayerAttentionTracker.get().snapshot(player);
            lookBehindVisibleTicks = attention.lookingAtSyna() ? attention.lookingTicks() : 0;
            if (lookBehindVisibleTicks < 12) return false;
            lookBehindCueActive = true;
            Vec3 behind = behindPlayer(player, 5.0D);
            player.serverLevel().playSound(null, behind.x, behind.y, behind.z,
                    SoundEvents.AMBIENT_CAVE.get(), SoundSource.AMBIENT, 0.85F, 0.65F);
            BridgeState.get().setLastEvent("prank_look_behind_cue:" + player.getGameProfile().getName());
            return false;
        }

        Vec3 behind = behindPlayer(player, 6.0D).add(0.0D, 1.4D, 0.0D);
        syna.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, behind);
        if (PlayerAttentionTracker.get().snapshot(player).awayTicks() < 2) return false;

        clearLookBehindPrank();
        SynaController.get().handle(new BridgeCommand("despawn_syna", null, null, null, null, null,
                null, null, null, null, null));
        BridgeState.get().setLastEvent("prank_look_behind_vanished:" + player.getGameProfile().getName());
        return true;
    }

    private Vec3 behindPlayer(ServerPlayer player, double distance) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        return player.position().subtract(horizontal.normalize().scale(distance));
    }

    private void clearLookBehindPrank() {
        lookBehindTarget = null;
        lookBehindTicks = 0;
        lookBehindVisibleTicks = 0;
        lookBehindCueActive = false;
    }

    public int getLifetimeTicks() {
        return Math.max(0, lifetimeTicks);
    }

    public int getUnseenTicks() {
        return Math.max(0, unseenTicks);
    }

    public boolean isCurrentlyVisible() {
        return currentlyVisible;
    }

    public String getReason() {
        return reason;
    }

    private boolean isVisibleToAnyPlayer(MinecraftServer server, AliceEntity syna) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != syna.level() || player.distanceToSqr(syna) > 48.0D * 48.0D) continue;
            Vec3 eye = player.getEyePosition();
            Vec3 target = syna.getEyePosition();
            Vec3 direction = target.subtract(eye);
            double distance = direction.length();
            if (distance < 0.01D) return true;
            if (player.getLookAngle().dot(direction.normalize()) < 0.45D) continue;
            BlockHitResult hit = player.level().clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(eye) >= distance * distance - 0.5D) {
                return true;
            }
        }
        return false;
    }

    private boolean relocateBehindPlayer(ServerPlayer player, AliceEntity syna) {
        ServerLevel level = player.serverLevel();
        SynaStoryData data = SynaStoryData.get(player.server);
        if (data.episodeEvents.add("manifest:stone_reveal") && relocateBehindStone(level, player, syna)) {
            data.setDirty();
            return true;
        }
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        horizontal = horizontal.normalize();
        Vec3 side = new Vec3(-horizontal.z, 0.0D, horizontal.x);
        Vec3[] candidates = {
                player.position().subtract(horizontal.scale(3.0D)),
                player.position().subtract(horizontal.scale(3.0D)).add(side.scale(1.5D)),
                player.position().subtract(horizontal.scale(3.0D)).subtract(side.scale(1.5D)),
                player.position().add(side.scale(2.5D)),
                player.position().subtract(side.scale(2.5D))
        };
        for (Vec3 candidate : candidates) {
            BlockPos base = BlockPos.containing(candidate);
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos pos = base.offset(0, dy, 0);
                if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).isSolid()) {
                    playDeparture(syna);
                    syna.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
                    playArrival(syna);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean relocateBehindStone(ServerLevel level, ServerPlayer player, AliceEntity syna) {
        BlockPos origin = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -3, -8), origin.offset(8, 4, 8))) {
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) continue;
            if (!level.getBlockState(pos.below()).isSolid()) continue;
            boolean stoneNeighbor = false;
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(pos.relative(direction)).is(Blocks.STONE)
                        || level.getBlockState(pos.relative(direction)).is(Blocks.DEEPSLATE)) {
                    stoneNeighbor = true;
                    break;
                }
            }
            if (!stoneNeighbor) continue;
            Vec3 toCandidate = Vec3.atCenterOf(pos).subtract(player.getEyePosition());
            if (player.getLookAngle().dot(toCandidate.normalize()) > 0.25D) continue;
            syna.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            return true;
        }
        return false;
    }

    private void leaveTrace(MinecraftServer server, AliceEntity syna, SynaStoryData data) {
        if (!data.episodeEvents.add("manifest:trace")) return;
        if (syna.level() instanceof ServerLevel level) {
            BlockPos center = syna.blockPosition();
            if (!lastLine.isBlank() && data.episodeEvents.add("manifest:sign")) {
                BlockPos signPos = findSignPosition(level, center);
                if (signPos != null) {
                    level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState(), 3);
                    if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
                        sign.setText(sign.getFrontText().setMessage(0,
                                net.minecraft.network.chat.Component.literal(lastLine)), true);
                        sign.setChanged();
                    }
                }
            }
            int[][] eye = {{-1, 0}, {0, -1}, {0, 0}, {0, 1}, {1, 0}};
            for (int[] offset : eye) {
                BlockPos pos = center.offset(offset[0], 0, offset[1]);
                if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isSolid()) {
                    level.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
                }
            }
        }
        data.setDirty();
    }

    private BlockPos findSignPosition(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 1, 2))) {
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isSolid()) return pos.immutable();
        }
        return null;
    }

    private ServerPlayer nearestPlayer(MinecraftServer server, AliceEntity syna) {
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != syna.level()) continue;
            double distance = player.distanceToSqr(syna);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }
}
