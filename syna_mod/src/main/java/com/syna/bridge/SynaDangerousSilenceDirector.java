package com.syna.bridge;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

final class SynaDangerousSilenceDirector {
    private static final SynaDangerousSilenceDirector INSTANCE = new SynaDangerousSilenceDirector();

    private SynaDangerousSilenceDirector() {}

    static SynaDangerousSilenceDirector get() {
        return INSTANCE;
    }

    JsonObject schedule(ServerPlayer player) {
        if (player == null) return receipt(false, "dangerous_silence_no_player");
        if (!"calm".equals(SynaController.get().getHorrorStage())) {
            return receipt(false, "dangerous_silence_horror_active");
        }
        SynaStoryData data = SynaStoryData.get(player.server);
        if (!DangerousSilencePolicy.canSchedule(data.storyTicks,
                data.dangerousSilenceDueTick, data.lastDangerousSilenceTick)) {
            return receipt(false, data.dangerousSilenceDueTick > data.storyTicks
                    ? "dangerous_silence_already_pending" : "dangerous_silence_cooldown");
        }

        data.dangerousSilenceSequence++;
        data.dangerousSilencePlayer = player.getGameProfile().getName();
        data.dangerousSilenceDueTick = data.storyTicks
                + DangerousSilencePolicy.delayTicks(data.chapter, data.dangerousSilenceSequence);
        data.lastReason = "dangerous_silence_scheduled";
        data.setDirty();
        BridgeState.get().setLastEvent("dangerous_silence_scheduled:"
                + player.getGameProfile().getName());
        JsonObject receipt = receipt(true, "dangerous_silence_scheduled");
        receipt.addProperty("delayTicks", data.dangerousSilenceDueTick - data.storyTicks);
        return receipt;
    }

    void tick(MinecraftServer server) {
        if (server == null) return;
        SynaStoryData data = SynaStoryData.get(server);
        if (data.dangerousSilenceDueTick <= 0L || data.storyTicks < data.dangerousSilenceDueTick) return;

        ServerPlayer player = findPlayer(server, data.dangerousSilencePlayer);
        if (player == null || !"calm".equals(SynaController.get().getHorrorStage())) {
            clearPending(data, "dangerous_silence_cancelled");
            return;
        }

        DangerousSilencePolicy.Outcome outcome = DangerousSilencePolicy.choose(
                data.chapter, data.dangerousSilenceSequence);
        apply(player, outcome);
        data.lastDangerousSilenceTick = Math.max(1L, data.storyTicks);
        data.dangerousSilenceDueTick = 0L;
        data.dangerousSilencePlayer = "";
        data.pressure = Math.min(100, data.pressure + 3);
        data.lastReason = "dangerous_silence_resolved:" + outcomeName(outcome);
        data.setDirty();
        BridgeState.get().setLastEvent("dangerous_silence_resolved:" + outcomeName(outcome)
                + ":" + player.getGameProfile().getName());
    }

    private void apply(ServerPlayer player, DangerousSilencePolicy.Outcome outcome) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 behind = player.position().subtract(horizontal.normalize().scale(6.0D));
        switch (outcome) {
            case PHANTOM_STEPS -> player.serverLevel().playSound(null, behind.x, behind.y, behind.z,
                    SoundEvents.WOOD_STEP, SoundSource.AMBIENT, 0.95F, 0.46F);
            case BRIEF_DARKNESS -> player.addEffect(new MobEffectInstance(MobEffects.DARKNESS,
                    60, 0, false, false, false));
            case MANIFESTATION -> SynaController.get().summonSilently(player,
                    "dangerous_silence", true);
            case DISTANT_KNOCK -> player.serverLevel().playSound(null,
                    behind.x + 4.0D, behind.y, behind.z - 3.0D,
                    SoundEvents.STONE_HIT, SoundSource.AMBIENT, 0.9F, 0.38F);
        }
    }

    private void clearPending(SynaStoryData data, String reason) {
        data.dangerousSilenceDueTick = 0L;
        data.dangerousSilencePlayer = "";
        data.lastReason = reason;
        data.setDirty();
        BridgeState.get().setLastEvent(reason);
    }

    private ServerPlayer findPlayer(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    private JsonObject receipt(boolean accepted, String result) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("accepted", accepted);
        receipt.addProperty("completed", accepted);
        receipt.addProperty("result", result);
        return receipt;
    }

    private String outcomeName(DangerousSilencePolicy.Outcome outcome) {
        return outcome.name().toLowerCase(java.util.Locale.ROOT);
    }
}
