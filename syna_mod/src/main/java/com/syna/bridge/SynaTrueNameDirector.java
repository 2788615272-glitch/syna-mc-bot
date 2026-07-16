package com.syna.bridge;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

final class SynaTrueNameDirector {
    private static final SynaTrueNameDirector INSTANCE = new SynaTrueNameDirector();

    private SynaTrueNameDirector() {}

    static SynaTrueNameDirector get() {
        return INSTANCE;
    }

    void recordRitualSite(ServerPlayer player) {
        if (player == null) return;
        SynaStoryData data = SynaStoryData.get(player.server);
        if (data.ritualSiteKnown) return;
        BlockPos pos = player.blockPosition();
        data.ritualSiteKnown = true;
        data.ritualDimension = player.level().dimension().location().toString();
        data.ritualX = pos.getX();
        data.ritualY = pos.getY();
        data.ritualZ = pos.getZ();
        data.lastReason = "true_name_ritual_site_recorded";
        data.setDirty();
        BridgeState.get().setLastEvent("true_name_ritual_site_recorded");
    }

    void awardCycleClue(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) return;
        SynaStoryData data = SynaStoryData.get(server);
        if (data.trueNameSealed || data.trueNameClues >= TrueNameMysteryPolicy.REQUIRED_CLUES) return;
        data.trueNameClues++;
        data.lastReason = "true_name_clue_awarded:" + data.trueNameClues;
        data.setDirty();
        SynaBoredomDirector.get().ensureFragment(player);
        player.sendSystemMessage(Component.literal("[残页] 第 " + data.trueNameClues
                + " 段字迹已经显现。").withStyle(ChatFormatting.DARK_PURPLE));
        BridgeState.get().setLastEvent("true_name_clue_awarded:" + data.trueNameClues);
    }

    boolean handleRitualSpeech(ServerPlayer player, String text) {
        if (player == null || !TrueNameMysteryPolicy.looksLikeRitual(text)) return false;
        SynaStoryData data = SynaStoryData.get(player.server);

        if (!TrueNameMysteryPolicy.hasValidRitualSyntax(text)) {
            ritualMessage(player, "仪式句式不完整。残页没有回应。");
            return true;
        }
        if (data.trueNameSealed) {
            ritualMessage(player, "这个名字已经不再回应你。");
            return true;
        }
        if (data.trueNameClues < TrueNameMysteryPolicy.REQUIRED_CLUES) {
            ritualMessage(player, "残页仍有缺口。现在读出名字没有意义。");
            return true;
        }
        if (!data.ritualSiteKnown) {
            ritualMessage(player, "你还没有找到让名字生效的地方。");
            return true;
        }
        if (!"hunting".equals(SynaController.get().getHorrorStage())) {
            ritualMessage(player, "这里太安静了。名字没有抓住任何东西。");
            return true;
        }
        if (!atRitualSite(player, data)) {
            ritualMessage(player, "残页上的字没有变化。这里不是那个地方。");
            return true;
        }
        if (!holdsFragment(player)) {
            ritualMessage(player, "你必须把残页拿在主手里，让它听见。");
            return true;
        }
        if (data.lastTrueNameAttemptTick > 0L
                && data.storyTicks - data.lastTrueNameAttemptTick < TrueNameMysteryPolicy.ATTEMPT_COOLDOWN_TICKS) {
            ritualMessage(player, "残页仍然冰冷。它拒绝回应连续的尝试。");
            return true;
        }

        String candidate = TrueNameMysteryPolicy.parseRitualCandidate(text);
        data.lastTrueNameAttemptTick = Math.max(1L, data.storyTicks);
        data.setDirty();
        if (!TrueNameMysteryPolicy.matches(player.serverLevel().getSeed(), candidate)) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false, false));
            ritualMessage(player, "名字落空了。她还在这里。");
            BridgeState.get().setLastEvent("true_name_attempt_wrong");
            return true;
        }

        data.trueNameSealed = true;
        data.outcome = "true_name_sealed";
        data.scene = "aftermath";
        data.lastReason = "true_name_sealed";
        data.setDirty();
        SynaBoredomData boredom = SynaBoredomData.get(player.server);
        boredom.phase = "sealed";
        boredom.ruleKind = "";
        boredom.ruleTarget = "";
        boredom.ruleClue = "";
        boredom.ruleRequired = 0;
        boredom.ruleSeconds = 0;
        boredom.setDirty();
        SynaController.get().sealByTrueName(player.getGameProfile().getName());
        player.server.getPlayerList().broadcastSystemMessage(Component.literal(
                "[Syna] 名字被完整读出。追逐停止了。").withStyle(ChatFormatting.DARK_PURPLE), false);
        BridgeState.get().setLastEvent("true_name_sealed");
        return true;
    }

    JsonObject toJson(MinecraftServer server) {
        JsonObject json = new JsonObject();
        if (server == null) {
            json.addProperty("available", false);
            return json;
        }
        SynaStoryData data = SynaStoryData.get(server);
        json.addProperty("available", true);
        json.addProperty("cluesFound", data.trueNameClues);
        json.addProperty("cluesRequired", TrueNameMysteryPolicy.REQUIRED_CLUES);
        json.addProperty("ritualSiteKnown", data.ritualSiteKnown);
        json.addProperty("sealed", data.trueNameSealed);
        return json;
    }

    String debugPrepare(ServerPlayer player) {
        if (player == null) return "no_player";
        SynaStoryData data = SynaStoryData.get(player.server);
        recordRitualSite(player);
        data.trueNameClues = TrueNameMysteryPolicy.REQUIRED_CLUES;
        data.trueNameSealed = false;
        data.lastTrueNameAttemptTick = 0L;
        data.lastReason = "true_name_debug_prepared";
        data.setDirty();
        SynaBoredomDirector.get().ensureFragment(player);
        return "clues=3/3, ritualSite=current_position, answer_written_in_fragment";
    }

    private boolean atRitualSite(ServerPlayer player, SynaStoryData data) {
        if (!player.level().dimension().location().toString().equals(data.ritualDimension)) return false;
        return player.distanceToSqr(data.ritualX + 0.5D, data.ritualY + 0.5D, data.ritualZ + 0.5D)
                <= TrueNameMysteryPolicy.RITUAL_RADIUS_SQR;
    }

    private boolean holdsFragment(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        return !stack.isEmpty() && stack.is(ModItems.SYNA_FRAGMENT.get());
    }

    private void ritualMessage(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[残页] " + text).withStyle(ChatFormatting.DARK_PURPLE));
    }
}
