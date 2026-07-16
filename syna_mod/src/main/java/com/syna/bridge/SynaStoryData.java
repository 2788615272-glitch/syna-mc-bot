package com.syna.bridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class SynaStoryData extends SavedData {
    private static final String DATA_NAME = "synabridge_story";

    int chapter = 1;
    int trust;
    int pressure;
    int interactions;
    int dependency;
    int episodeId = 1;
    int proactiveHelpCount;
    int lastToolGiftCycle;
    long lastEligibleToolBreakTick = -1L;
    int blocksMinedThisEpisode;
    int stoneRevealThreshold = 6;
    long storyTicks;
    long nextSceneTick;
    long quietUntilTick;
    long observedHorrorEpisode;
    long lastPresenceProofTick;
    int presenceProofCount;
    long dangerousSilenceDueTick;
    long lastDangerousSilenceTick;
    int dangerousSilenceSequence;
    String dangerousSilencePlayer = "";
    int trueNameClues;
    boolean ritualSiteKnown;
    String ritualDimension = "";
    int ritualX;
    int ritualY;
    int ritualZ;
    long lastTrueNameAttemptTick;
    boolean trueNameSealed;
    int fearBudget = StoryPacingPolicy.INITIAL_BUDGET;
    String scene = "arrival";
    String outcome = "unresolved";
    String lastReason = "new_world";
    final Set<String> clues = new LinkedHashSet<>();
    final Set<String> episodeEvents = new LinkedHashSet<>();
    final Set<String> persistentEntityEvents = new LinkedHashSet<>();
    final Map<String, Long> lastEntityTemplateTicks = new LinkedHashMap<>();
    final Set<String> identityDisclosures = new LinkedHashSet<>();

    public static SynaStoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SynaStoryData::load, SynaStoryData::new, DATA_NAME);
    }

    public static SynaStoryData load(CompoundTag tag) {
        SynaStoryData data = new SynaStoryData();
        data.chapter = Math.max(1, Math.min(5, tag.getInt("chapter")));
        data.trust = tag.getInt("trust");
        data.pressure = tag.getInt("pressure");
        data.interactions = tag.getInt("interactions");
        data.dependency = tag.getInt("dependency");
        data.episodeId = Math.max(1, tag.getInt("episodeId"));
        data.proactiveHelpCount = tag.getInt("proactiveHelpCount");
        data.lastToolGiftCycle = Math.max(0, tag.getInt("lastToolGiftCycle"));
        data.lastEligibleToolBreakTick = tag.contains("lastEligibleToolBreakTick")
                ? Math.max(-1L, tag.getLong("lastEligibleToolBreakTick")) : -1L;
        data.blocksMinedThisEpisode = tag.getInt("blocksMinedThisEpisode");
        data.stoneRevealThreshold = tag.contains("stoneRevealThreshold")
                ? Math.max(5, Math.min(8, tag.getInt("stoneRevealThreshold"))) : 6;
        data.storyTicks = tag.getLong("storyTicks");
        data.nextSceneTick = tag.getLong("nextSceneTick");
        data.quietUntilTick = tag.getLong("quietUntilTick");
        data.observedHorrorEpisode = tag.getLong("observedHorrorEpisode");
        data.lastPresenceProofTick = Math.max(0L, tag.getLong("lastPresenceProofTick"));
        data.presenceProofCount = Math.max(0, tag.getInt("presenceProofCount"));
        data.dangerousSilenceDueTick = Math.max(0L, tag.getLong("dangerousSilenceDueTick"));
        data.lastDangerousSilenceTick = Math.max(0L, tag.getLong("lastDangerousSilenceTick"));
        data.dangerousSilenceSequence = Math.max(0, tag.getInt("dangerousSilenceSequence"));
        data.dangerousSilencePlayer = tag.getString("dangerousSilencePlayer");
        data.trueNameClues = Math.max(0, Math.min(TrueNameMysteryPolicy.REQUIRED_CLUES,
                tag.getInt("trueNameClues")));
        data.ritualSiteKnown = tag.getBoolean("ritualSiteKnown");
        data.ritualDimension = tag.getString("ritualDimension");
        data.ritualX = tag.getInt("ritualX");
        data.ritualY = tag.getInt("ritualY");
        data.ritualZ = tag.getInt("ritualZ");
        data.lastTrueNameAttemptTick = Math.max(0L, tag.getLong("lastTrueNameAttemptTick"));
        data.trueNameSealed = tag.getBoolean("trueNameSealed");
        data.fearBudget = tag.contains("fearBudget")
                ? Math.max(0, Math.min(StoryPacingPolicy.MAX_BUDGET, tag.getInt("fearBudget")))
                : StoryPacingPolicy.INITIAL_BUDGET;
        data.scene = tag.getString("scene");
        data.outcome = tag.getString("outcome");
        data.lastReason = tag.getString("lastReason");
        ListTag clues = tag.getList("clues", 8);
        for (int i = 0; i < clues.size(); i++) {
            data.clues.add(clues.getString(i));
        }
        ListTag episodeEvents = tag.getList("episodeEvents", 8);
        for (int i = 0; i < episodeEvents.size(); i++) {
            data.episodeEvents.add(episodeEvents.getString(i));
        }
        ListTag persistentEntityEvents = tag.getList("persistentEntityEvents", 8);
        for (int i = 0; i < persistentEntityEvents.size(); i++) {
            data.persistentEntityEvents.add(persistentEntityEvents.getString(i));
        }
        CompoundTag lastEntityTemplateTicks = tag.getCompound("lastEntityTemplateTicks");
        for (String template : lastEntityTemplateTicks.getAllKeys()) {
            data.lastEntityTemplateTicks.put(template, Math.max(0L, lastEntityTemplateTicks.getLong(template)));
        }
        ListTag identityDisclosures = tag.getList("identityDisclosures", 8);
        for (int i = 0; i < identityDisclosures.size(); i++) {
            data.identityDisclosures.add(identityDisclosures.getString(i));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("chapter", chapter);
        tag.putInt("trust", trust);
        tag.putInt("pressure", pressure);
        tag.putInt("interactions", interactions);
        tag.putInt("dependency", dependency);
        tag.putInt("episodeId", episodeId);
        tag.putInt("proactiveHelpCount", proactiveHelpCount);
        tag.putInt("lastToolGiftCycle", lastToolGiftCycle);
        tag.putLong("lastEligibleToolBreakTick", lastEligibleToolBreakTick);
        tag.putInt("blocksMinedThisEpisode", blocksMinedThisEpisode);
        tag.putInt("stoneRevealThreshold", stoneRevealThreshold);
        tag.putLong("storyTicks", storyTicks);
        tag.putLong("nextSceneTick", nextSceneTick);
        tag.putLong("quietUntilTick", quietUntilTick);
        tag.putLong("observedHorrorEpisode", observedHorrorEpisode);
        tag.putLong("lastPresenceProofTick", lastPresenceProofTick);
        tag.putInt("presenceProofCount", presenceProofCount);
        tag.putLong("dangerousSilenceDueTick", dangerousSilenceDueTick);
        tag.putLong("lastDangerousSilenceTick", lastDangerousSilenceTick);
        tag.putInt("dangerousSilenceSequence", dangerousSilenceSequence);
        tag.putString("dangerousSilencePlayer", dangerousSilencePlayer);
        tag.putInt("trueNameClues", trueNameClues);
        tag.putBoolean("ritualSiteKnown", ritualSiteKnown);
        tag.putString("ritualDimension", ritualDimension);
        tag.putInt("ritualX", ritualX);
        tag.putInt("ritualY", ritualY);
        tag.putInt("ritualZ", ritualZ);
        tag.putLong("lastTrueNameAttemptTick", lastTrueNameAttemptTick);
        tag.putBoolean("trueNameSealed", trueNameSealed);
        tag.putInt("fearBudget", fearBudget);
        tag.putString("scene", scene);
        tag.putString("outcome", outcome);
        tag.putString("lastReason", lastReason);
        ListTag clueTags = new ListTag();
        for (String clue : clues) clueTags.add(StringTag.valueOf(clue));
        tag.put("clues", clueTags);
        ListTag episodeEventTags = new ListTag();
        for (String event : episodeEvents) episodeEventTags.add(StringTag.valueOf(event));
        tag.put("episodeEvents", episodeEventTags);
        ListTag persistentEntityEventTags = new ListTag();
        for (String event : persistentEntityEvents) persistentEntityEventTags.add(StringTag.valueOf(event));
        tag.put("persistentEntityEvents", persistentEntityEventTags);
        CompoundTag lastEntityTemplateTickTags = new CompoundTag();
        for (Map.Entry<String, Long> entry : lastEntityTemplateTicks.entrySet()) {
            lastEntityTemplateTickTags.putLong(entry.getKey(), entry.getValue());
        }
        tag.put("lastEntityTemplateTicks", lastEntityTemplateTickTags);
        ListTag identityDisclosureTags = new ListTag();
        for (String disclosure : identityDisclosures) identityDisclosureTags.add(StringTag.valueOf(disclosure));
        tag.put("identityDisclosures", identityDisclosureTags);
        return tag;
    }
}
