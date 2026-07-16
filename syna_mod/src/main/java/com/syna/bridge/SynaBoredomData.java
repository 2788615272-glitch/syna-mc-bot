package com.syna.bridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.EnumMap;
import java.util.Map;

final class SynaBoredomData extends SavedData {
    private static final String DATA_NAME = "synabridge_boredom";

    int boredom = SynaBoredomPolicy.STARTING_BOREDOM;
    int cycleNumber = 1;
    long cycleTicks;
    long lastPassiveTick;
    long observedHorrorEpisode;
    String phase = "dormant";
    String ruleKind = "";
    String ruleTarget = "";
    String ruleClue = "";
    int ruleRequired;
    int ruleSeconds;
    String lastActivity = "none";
    int lastGain;
    int miningProgress;
    int buildingProgress;
    long lastConversationTick;
    int fragmentCycle;
    long lastOpportunityTick;
    long opportunityDay = -1L;
    int opportunityWindow;
    long opportunityId;
    String opportunityScene = "";
    boolean opportunityAccepted;
    long observationId;
    String observationType = "";
    String observationDetail = "";
    long observationTick;
    final Map<SynaBoredomPolicy.Activity, Integer> repetitions =
            new EnumMap<>(SynaBoredomPolicy.Activity.class);

    static SynaBoredomData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                SynaBoredomData::load, SynaBoredomData::new, DATA_NAME);
    }

    static SynaBoredomData load(CompoundTag tag) {
        SynaBoredomData data = new SynaBoredomData();
        data.boredom = Math.max(0, Math.min(100, tag.getInt("boredom")));
        data.cycleNumber = Math.max(1, tag.getInt("cycleNumber"));
        data.cycleTicks = Math.max(0L, tag.getLong("cycleTicks"));
        data.lastPassiveTick = Math.max(0L, tag.getLong("lastPassiveTick"));
        data.observedHorrorEpisode = Math.max(0L, tag.getLong("observedHorrorEpisode"));
        data.phase = nonBlank(tag.getString("phase"), "dormant");
        data.ruleKind = tag.getString("ruleKind");
        data.ruleTarget = tag.getString("ruleTarget");
        data.ruleClue = tag.getString("ruleClue");
        data.ruleRequired = Math.max(0, tag.getInt("ruleRequired"));
        data.ruleSeconds = Math.max(0, tag.getInt("ruleSeconds"));
        data.lastActivity = nonBlank(tag.getString("lastActivity"), "none");
        data.lastGain = Math.max(0, tag.getInt("lastGain"));
        data.miningProgress = Math.max(0, tag.getInt("miningProgress"));
        data.buildingProgress = Math.max(0, tag.getInt("buildingProgress"));
        data.lastConversationTick = Math.max(0L, tag.getLong("lastConversationTick"));
        data.fragmentCycle = Math.max(0, tag.getInt("fragmentCycle"));
        data.lastOpportunityTick = Math.max(0L, tag.getLong("lastOpportunityTick"));
        data.opportunityDay = tag.contains("opportunityDay") ? tag.getLong("opportunityDay") : -1L;
        data.opportunityWindow = Math.max(0, tag.getInt("opportunityWindow"));
        data.opportunityId = Math.max(0L, tag.getLong("opportunityId"));
        data.opportunityScene = tag.getString("opportunityScene");
        data.opportunityAccepted = tag.getBoolean("opportunityAccepted");
        data.observationId = Math.max(0L, tag.getLong("observationId"));
        data.observationType = tag.getString("observationType");
        data.observationDetail = tag.getString("observationDetail");
        data.observationTick = Math.max(0L, tag.getLong("observationTick"));
        CompoundTag counts = tag.getCompound("repetitions");
        for (SynaBoredomPolicy.Activity activity : SynaBoredomPolicy.Activity.values()) {
            data.repetitions.put(activity, Math.max(0, counts.getInt(activity.name())));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("boredom", boredom);
        tag.putInt("cycleNumber", cycleNumber);
        tag.putLong("cycleTicks", cycleTicks);
        tag.putLong("lastPassiveTick", lastPassiveTick);
        tag.putLong("observedHorrorEpisode", observedHorrorEpisode);
        tag.putString("phase", phase);
        tag.putString("ruleKind", ruleKind);
        tag.putString("ruleTarget", ruleTarget);
        tag.putString("ruleClue", ruleClue);
        tag.putInt("ruleRequired", ruleRequired);
        tag.putInt("ruleSeconds", ruleSeconds);
        tag.putString("lastActivity", lastActivity);
        tag.putInt("lastGain", lastGain);
        tag.putInt("miningProgress", miningProgress);
        tag.putInt("buildingProgress", buildingProgress);
        tag.putLong("lastConversationTick", lastConversationTick);
        tag.putInt("fragmentCycle", fragmentCycle);
        tag.putLong("lastOpportunityTick", lastOpportunityTick);
        tag.putLong("opportunityDay", opportunityDay);
        tag.putInt("opportunityWindow", opportunityWindow);
        tag.putLong("opportunityId", opportunityId);
        tag.putString("opportunityScene", opportunityScene);
        tag.putBoolean("opportunityAccepted", opportunityAccepted);
        tag.putLong("observationId", observationId);
        tag.putString("observationType", observationType);
        tag.putString("observationDetail", observationDetail);
        tag.putLong("observationTick", observationTick);
        CompoundTag counts = new CompoundTag();
        for (SynaBoredomPolicy.Activity activity : SynaBoredomPolicy.Activity.values()) {
            counts.putInt(activity.name(), repetitions.getOrDefault(activity, 0));
        }
        tag.put("repetitions", counts);
        return tag;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
