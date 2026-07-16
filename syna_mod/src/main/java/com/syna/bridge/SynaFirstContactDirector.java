package com.syna.bridge;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class SynaFirstContactDirector {
    private static final SynaFirstContactDirector INSTANCE = new SynaFirstContactDirector();
    private static final String TAG = "SynaFirstContact";
    private static final long GRACE_TICKS = 20L * 180L;
    private final Map<UUID, PendingIntro> pending = new HashMap<>();

    private SynaFirstContactDirector() {}

    static SynaFirstContactDirector get() {
        return INSTANCE;
    }

    void onLogin(ServerPlayer player) {
        if (player == null) return;
        if (completed(player)) {
            SynaBoredomDirector.get().ensureFragment(player);
            return;
        }
        CompoundTag data = data(player);
        long now = player.serverLevel().getGameTime();
        data.putLong("graceUntil", Math.max(data.getLong("graceUntil"), now + GRACE_TICKS));
        save(player, data);
        pending.put(player.getUUID(), new PendingIntro(now + 60L, data.getBoolean("announced") ? 1 : 0));
    }

    void onLogout(ServerPlayer player) {
        if (player != null) pending.remove(player.getUUID());
    }

    void onClone(ServerPlayer original, ServerPlayer replacement) {
        if (original == null || replacement == null) return;
        replacement.getPersistentData().put(TAG, data(original).copy());
    }

    void tick(MinecraftServer server) {
        if (server == null || pending.isEmpty()) return;
        Iterator<Map.Entry<UUID, PendingIntro>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingIntro> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || completed(player)) {
                iterator.remove();
                continue;
            }
            PendingIntro intro = entry.getValue();
            long now = player.serverLevel().getGameTime();
            if (now < intro.dueTick) continue;
            CompoundTag data = data(player);
            if (!data.getBoolean("announced")) {
                player.sendSystemMessage(Component.literal("??? 加入了游戏").withStyle(ChatFormatting.YELLOW));
                data.putBoolean("announced", true);
                save(player, data);
            }
            player.displayClientMessage(Component.literal("按住 V 与未知访客说话")
                    .withStyle(ChatFormatting.DARK_PURPLE), true);
            if (intro.hintsShown >= 1) iterator.remove();
            else entry.setValue(new PendingIntro(now + 20L * 10L, intro.hintsShown + 1));
        }
    }

    boolean interceptFirstAttack(AliceEntity syna, DamageSource source) {
        if (!(source.getEntity() instanceof ServerPlayer player) || completed(player)) return false;
        CompoundTag data = data(player);
        long now = player.serverLevel().getGameTime();
        if (now > data.getLong("graceUntil") || data.getBoolean("graceAttackUsed")) return false;
        data.putBoolean("graceAttackUsed", true);
        save(player, data);
        player.sendSystemMessage(Component.literal("[Syna] 你总是先动手，再问名字吗？")
                .withStyle(ChatFormatting.DARK_PURPLE));
        BridgeState.get().setLastEvent("first_contact_attack_warning:" + player.getGameProfile().getName());
        return true;
    }

    JsonObject recordCompleted(ServerPlayer player) {
        JsonObject receipt = new JsonObject();
        if (player == null) {
            receipt.addProperty("accepted", false);
            receipt.addProperty("completed", false);
            receipt.addProperty("result", "first_contact_no_player");
            return receipt;
        }
        CompoundTag data = data(player);
        data.putBoolean("completed", true);
        data.putInt("factsDelivered", 15);
        save(player, data);
        pending.remove(player.getUUID());
        SynaOpeningOmenDirector.get().schedule(player);
        receipt.addProperty("accepted", true);
        receipt.addProperty("completed", true);
        receipt.addProperty("result", "first_contact_completed");
        BridgeState.get().setLastEvent("first_contact_completed:" + player.getGameProfile().getName());
        return receipt;
    }

    String debugReset(ServerPlayer player) {
        if (player == null) return "no_player";
        player.getPersistentData().remove(TAG);
        pending.remove(player.getUUID());
        SynaOpeningOmenDirector.get().debugReset(player);
        onLogin(player);
        return "first_contact_reset";
    }

    JsonObject toJson(MinecraftServer server) {
        JsonObject json = new JsonObject();
        ServerPlayer player = BridgeState.get().getBoundPlayer();
        if (player == null && server != null && !server.getPlayerList().getPlayers().isEmpty()) {
            player = server.getPlayerList().getPlayers().get(0);
        }
        if (player == null) {
            json.addProperty("available", false);
            return json;
        }
        CompoundTag data = data(player);
        json.addProperty("available", true);
        json.addProperty("active", !data.getBoolean("completed"));
        json.addProperty("announced", data.getBoolean("announced"));
        json.addProperty("factsDelivered", data.getInt("factsDelivered"));
        json.addProperty("graceAttackAvailable", !data.getBoolean("graceAttackUsed"));
        return json;
    }

    private boolean completed(ServerPlayer player) {
        return data(player).getBoolean("completed");
    }

    private CompoundTag data(ServerPlayer player) {
        CompoundTag persistent = player.getPersistentData();
        return persistent.contains(TAG) ? persistent.getCompound(TAG) : new CompoundTag();
    }

    private void save(ServerPlayer player, CompoundTag value) {
        player.getPersistentData().put(TAG, value);
    }

    private record PendingIntro(long dueTick, int hintsShown) {}
}
