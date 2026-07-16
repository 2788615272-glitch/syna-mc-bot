package com.syna.bridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.syna.bridge.AliceEntity;
import com.syna.bridge.ModEntities;
import com.syna.bridge.SynaBridgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SynaBridgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SynaHorrorClientEffects {
    private static int lastStage;
    private static long lastAmbientTick = Long.MIN_VALUE;
    private static AliceEntity apparition;
    private static Object apparitionLevel;
    private static boolean suppressVanillaMusic;

    private SynaHorrorClientEffects() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            reset();
            return;
        }

        AliceEntity syna = findSyna(mc);
        if (syna == null || !isLocalTarget(mc, syna) || !syna.isHorrorFxEnabled()) {
            lastStage = 0;
            suppressVanillaMusic = false;
            return;
        }

        int stage = syna.getHorrorStage();
        long tick = mc.level.getGameTime();
        suppressVanillaMusic = stage >= 2;
        if (suppressVanillaMusic) mc.getMusicManager().stopPlaying();
        if (stage != lastStage) {
            playStageCue(mc, stage);
            lastStage = stage;
            lastAmbientTick = tick;
        }

        int interval = switch (stage) {
            case 1 -> 220;
            case 2 -> 140;
            case 3 -> 80;
            case 4 -> 46;
            default -> Integer.MAX_VALUE;
        };
        if (stage > 0 && tick - lastAmbientTick >= interval) {
            playAmbientLayer(mc, stage, tick);
            lastAmbientTick = tick;
        }
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (suppressVanillaMusic && event.getOriginalSound().getSource() == SoundSource.MUSIC) {
            event.setSound(null);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        AliceEntity syna = findSyna(mc);
        if (syna == null || syna.getHorrorStage() < 2 || !isLocalTarget(mc, syna) || !syna.isHorrorFxEnabled()) return;

        long tick = mc.level.getGameTime();
        int stage = syna.getHorrorStage();
        int cycle = stage >= 4 ? 90 : stage == 3 ? 150 : 240;
        int visibleTicks = stage >= 4 ? 9 : stage == 3 ? 13 : 16;
        long phase = Math.floorMod(tick + stableOffset(syna.getHorrorTargetName()), cycle);
        if (phase >= visibleTicks) return;

        Vec3 position = apparitionPosition(mc, tick, stage);
        if (position == null) return;
        renderApparition(event, mc, syna, position);
    }

    private static void playStageCue(Minecraft mc, int stage) {
        if (stage <= 0) return;
        SoundEvent sound = switch (stage) {
            case 1 -> SoundEvents.AMBIENT_CAVE.value();
            case 2 -> SoundEvents.SCULK_SHRIEKER_SHRIEK;
            case 3 -> SoundEvents.WARDEN_HEARTBEAT;
            default -> SoundEvents.ENDERMAN_STARE;
        };
        float volume = stage >= 3 ? 0.8F : 0.5F;
        float pitch = stage >= 4 ? 0.55F : 0.72F;
        playAt(mc, mc.player.position(), sound, volume, pitch);
    }

    private static void playAmbientLayer(Minecraft mc, int stage, long tick) {
        Vec3 look = mc.player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.001D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        horizontal = horizontal.normalize();
        Vec3 right = new Vec3(-horizontal.z, 0.0D, horizontal.x);
        double side = ((tick / 20L) & 1L) == 0L ? 1.0D : -1.0D;
        Vec3 source = mc.player.position().add(horizontal.scale(-3.0D)).add(right.scale(5.5D * side));

        if (stage >= 4) {
            playAt(mc, source, SoundEvents.WARDEN_HEARTBEAT, 0.58F, 0.64F);
            playAt(mc, source.add(0.0D, 1.0D, 0.0D), SoundEvents.ENDERMAN_AMBIENT, 0.28F, 0.45F);
        } else if (stage == 3) {
            playAt(mc, source, SoundEvents.WARDEN_HEARTBEAT, 0.44F, 0.72F);
        } else if (stage == 2) {
            playAt(mc, source, SoundEvents.WOOD_STEP, 0.46F, 0.58F);
        } else {
            playAt(mc, source, SoundEvents.AMBIENT_CAVE.value(), 0.34F, 0.62F);
        }
    }

    private static void playAt(Minecraft mc, Vec3 pos, SoundEvent sound, float volume, float pitch) {
        if (mc.level == null) return;
        mc.level.playLocalSound(pos.x, pos.y, pos.z, sound, SoundSource.AMBIENT, volume, pitch, false);
    }

    private static Vec3 apparitionPosition(Minecraft mc, long tick, int stage) {
        Vec3 look = mc.player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);
        if (forward.lengthSqr() < 0.001D) return null;
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        double side = ((tick / 120L) & 1L) == 0L ? 1.0D : -1.0D;
        double lateral = stage >= 4 ? 5.2D : 7.2D;
        double ahead = stage >= 4 ? 5.0D : 3.5D;
        Vec3 desired = mc.player.position().add(forward.scale(ahead)).add(right.scale(lateral * side));
        return findFloorNear(mc, desired);
    }

    private static Vec3 findFloorNear(Minecraft mc, Vec3 desired) {
        BlockPos base = BlockPos.containing(desired.x, mc.player.getY(), desired.z);
        for (int offset = 3; offset >= -8; offset--) {
            BlockPos feet = base.offset(0, offset, 0);
            BlockState floor = mc.level.getBlockState(feet.below());
            if (!floor.isAir()
                    && mc.level.getBlockState(feet).isAir()
                    && mc.level.getBlockState(feet.above()).isAir()) {
                return new Vec3(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
            }
        }
        return null;
    }

    private static void renderApparition(RenderLevelStageEvent event, Minecraft mc, AliceEntity source, Vec3 pos) {
        if (apparition == null || apparitionLevel != mc.level) {
            apparition = ModEntities.ALICE.get().create(mc.level);
            apparitionLevel = mc.level;
        }
        if (apparition == null) return;

        float yaw = (float) (Mth.atan2(mc.player.getZ() - pos.z, mc.player.getX() - pos.x) * (180.0D / Math.PI)) - 90.0F;
        apparition.setPos(pos.x, pos.y, pos.z);
        apparition.setYRot(yaw);
        apparition.setYHeadRot(yaw);
        apparition.yBodyRot = yaw;
        apparition.tickCount = (int) mc.level.getGameTime();
        apparition.setHorrorState(Math.max(3, source.getHorrorStage()), 0, source.getHorrorTargetName());
        apparition.setCustomNameVisible(false);

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        pose.pushPose();
        try {
            dispatcher.render(apparition,
                    pos.x - camera.x,
                    pos.y - camera.y,
                    pos.z - camera.z,
                    yaw,
                    event.getPartialTick(),
                    pose,
                    buffer,
                    LightTexture.FULL_BRIGHT);
            buffer.endBatch();
        } finally {
            pose.popPose();
            dispatcher.setRenderShadow(true);
        }
    }

    private static AliceEntity findSyna(Minecraft mc) {
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof AliceEntity alice && alice.isAlive()) return alice;
        }
        return null;
    }

    private static boolean isLocalTarget(Minecraft mc, AliceEntity syna) {
        String target = syna.getHorrorTargetName();
        return target != null
                && !target.isBlank()
                && target.equalsIgnoreCase(mc.player.getGameProfile().getName());
    }

    private static int stableOffset(String value) {
        return Math.floorMod(value == null ? 0 : value.hashCode(), 71);
    }

    private static void reset() {
        lastStage = 0;
        lastAmbientTick = Long.MIN_VALUE;
        apparition = null;
        apparitionLevel = null;
        suppressVanillaMusic = false;
    }
}
