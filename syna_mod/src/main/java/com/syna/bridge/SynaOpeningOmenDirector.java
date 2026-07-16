package com.syna.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

final class SynaOpeningOmenDirector {
    private static final SynaOpeningOmenDirector INSTANCE = new SynaOpeningOmenDirector();
    private static final String TAG = "SynaOpeningOmen";
    private static final long FIRST_CUE_DELAY_TICKS = 20L * 20L;
    private static final long REVEAL_DELAY_TICKS = 20L * 3L;

    private SynaOpeningOmenDirector() {}

    static SynaOpeningOmenDirector get() {
        return INSTANCE;
    }

    void schedule(ServerPlayer player) {
        if (player == null) return;
        CompoundTag data = data(player);
        if (data.getBoolean("completed") || data.getInt("phase") > 0) return;
        data.putInt("phase", 1);
        data.putLong("dueTick", player.serverLevel().getGameTime() + FIRST_CUE_DELAY_TICKS);
        save(player, data);
        BridgeState.get().setLastEvent("opening_omen_scheduled:" + player.getGameProfile().getName());
    }

    void tick(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CompoundTag data = data(player);
            int phase = data.getInt("phase");
            if (phase <= 0 || data.getBoolean("completed")) continue;
            long now = player.serverLevel().getGameTime();
            if (now < data.getLong("dueTick")) continue;
            if (!player.isAlive() || player.isCreative() || player.isSpectator()
                    || !"calm".equals(SynaController.get().getHorrorStage())) {
                data.putLong("dueTick", now + 20L * 10L);
                save(player, data);
                continue;
            }
            if (phase == 1) triggerCue(player, data, now);
            else triggerReveal(player, data);
        }
    }

    void onClone(ServerPlayer original, ServerPlayer replacement) {
        if (original == null || replacement == null) return;
        replacement.getPersistentData().put(TAG, data(original).copy());
    }

    void debugTrigger(ServerPlayer player) {
        if (player == null) return;
        CompoundTag data = new CompoundTag();
        data.putInt("phase", 1);
        data.putLong("dueTick", player.serverLevel().getGameTime());
        save(player, data);
    }

    void debugReset(ServerPlayer player) {
        if (player != null) player.getPersistentData().remove(TAG);
    }

    private void triggerCue(ServerPlayer player, CompoundTag data, long now) {
        player.sendSystemMessage(Component.literal("[Syna] 别回头。")
                .withStyle(ChatFormatting.DARK_PURPLE));
        SynaVoiceNetwork.sendOpeningOmen(player, 1, 20 * 4);
        playBehind(player, SoundEvents.SCULK_CLICKING, 0.75F, 0.55F, 5.0D);
        data.putInt("phase", 2);
        data.putLong("dueTick", now + REVEAL_DELAY_TICKS);
        save(player, data);
        BridgeState.get().setLastEvent("opening_omen_cue:" + player.getGameProfile().getName());
    }

    private void triggerReveal(ServerPlayer player, CompoundTag data) {
        SynaVoiceNetwork.sendOpeningOmen(player, 2, 20 * 3);
        playBehind(player, SoundEvents.ENDERMAN_STARE, 0.82F, 0.48F, 4.0D);
        HorrorEntityEventDirector.ScheduleResult result = HorrorEntityEventDirector.get().schedule(
                player, "watcher", "minecraft:skeleton", "opening_omen");
        data.putInt("phase", 0);
        data.putBoolean("completed", true);
        save(player, data);
        SynaBoredomDirector.get().ensureFragment(player);
        BridgeState.get().setLastEvent("opening_omen_revealed:" + result.reason()
                + ":" + player.getGameProfile().getName());
    }

    private void playBehind(ServerPlayer player, net.minecraft.sounds.SoundEvent sound,
                            float volume, float pitch, double distance) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 behind = player.position().subtract(horizontal.normalize().scale(distance));
        player.serverLevel().playSound(null, behind.x, behind.y + 1.0D, behind.z,
                sound, SoundSource.AMBIENT, volume, pitch);
    }

    private CompoundTag data(ServerPlayer player) {
        CompoundTag persistent = player.getPersistentData();
        return persistent.contains(TAG) ? persistent.getCompound(TAG) : new CompoundTag();
    }

    private void save(ServerPlayer player, CompoundTag value) {
        player.getPersistentData().put(TAG, value);
    }
}
