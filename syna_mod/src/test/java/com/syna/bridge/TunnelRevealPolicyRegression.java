package com.syna.bridge;

public final class TunnelRevealPolicyRegression {
    public static void main(String[] args) {
        expect(2, BridgeProtocol.VERSION,
                "the launcher must be able to reject jars without the opening protocol");
        expect(true, TunnelRevealPolicy.qualifiesMining(30, 63, 0, 0),
                "ordinary forward mining below sea level must count");
        expect(false, TunnelRevealPolicy.qualifiesMining(60, 63, 0, 0),
                "surface mining must not count");
        expect(false, TunnelRevealPolicy.qualifiesMining(30, 63, 3, 0),
                "blocks far above or below the player must not count");
        expect(false, TunnelRevealPolicy.qualifiesMining(30, 63, 0, 3),
                "blocks away from the forward mining lane must not count");
        expect(4, TunnelRevealPolicy.advanceConsecutive(3, true, 20, 1),
                "nearby mining in the same direction must extend the trajectory");
        expect(1, TunnelRevealPolicy.advanceConsecutive(7, false, 20, 1),
                "turning into a new branch must reset the trajectory");
        expect(-1, TunnelRevealPolicy.scoreCandidate(false, true, true, true, true, 3, 4),
                "an exposed cavity must be rejected");
        expect(33, TunnelRevealPolicy.scoreCandidate(true, true, true, true, true, 3, 4),
                "a covered straight cavity must receive a high score");
        expect(true, AttentionPolicy.isSuddenTurn(AttentionPolicy.turnDegrees(0.0D)),
                "a ninety degree turn must register as sudden");
        expect(false, AttentionPolicy.isSuddenTurn(AttentionPolicy.turnDegrees(0.95D)),
                "small camera movement must not register as sudden");
        expect(true, AttentionPolicy.isInView(0.35D),
                "the configured field-of-view edge must count as in view");
        expect(false, AttentionPolicy.isInView(0.349D),
                "actors outside the field-of-view edge must not count as in view");
        expect(true, AttentionPolicy.isApproaching(8.0D, 7.9D),
                "a meaningful distance decrease must register as approaching");
        expect(false, AttentionPolicy.isApproaching(8.0D, 7.99D),
                "position jitter must not register as approaching");
        expect(false, StoryPacingPolicy.canStart(3, 0L, 100L, "ambush"),
                "high intensity scenes must wait for enough fear budget");
        expect(true, StoryPacingPolicy.canStart(5, 0L, 100L, "ambush"),
                "high intensity scenes may start when the budget is available");
        expect(false, StoryPacingPolicy.canStart(10, 101L, 100L, "footsteps"),
                "quiet periods must block even low intensity scenes");
        expect(0, StoryPacingPolicy.spend(5, "ambush"),
                "scene completion must spend its configured fear budget");
        expect(1000, (int) StoryPacingPolicy.quietUntil(0L, 100L, "ambush"),
                "high intensity scenes must create a forty-five second quiet period");
        expect(false, StoryEntityLedgerPolicy.canSchedule("stalker", 2, 12_000L,
                        java.util.Set.of(), java.util.Set.of("story:stalker"), java.util.Map.of()),
                "clearing short-lived episode events must not allow a second stalker in one story");
        expect(false, StoryEntityLedgerPolicy.canSchedule("enforcer", 3, 20_000L,
                        java.util.Set.of(), java.util.Set.of("horror:3:enforcer"), java.util.Map.of()),
                "an enforcer must run at most once in one horror episode");
        expect(false, StoryEntityLedgerPolicy.canSchedule("ambush", 4, 30_000L,
                        java.util.Set.of(), java.util.Set.of(), java.util.Map.of("ambush", 25_000L)),
                "the same entity template must wait five minutes before another schedule");
        expect(true, ToolGiftPolicy.canOffer(2, 20_000L, 1, 1_000L),
                "a low-tier tool gift may occur in a new cycle after fifteen minutes");
        expect(true, ToolGiftPolicy.canOffer(1, 0L, 0, -1L),
                "the first eligible break may happen at the start of a world");
        expect(false, ToolGiftPolicy.canOffer(2, 1_000L, 1, 0L),
                "a break at tick zero must still start the fifteen minute interval");
        expect(false, ToolGiftPolicy.canOffer(2, 20_000L, 2, 1_000L),
                "a story cycle must receive at most one automatic tool gift");
        expect(false, ToolGiftPolicy.canOffer(3, 10_000L, 2, 1_000L),
                "eligible tool breaks inside fifteen minutes must not trigger another gift");
        expect(0, FragmentPresentationPolicy.mood(20, "dormant", "calm"),
                "a calm fragment must keep its faint presentation");
        expect(2, FragmentPresentationPolicy.mood(75, "dormant", "calm"),
                "high boredom must visibly chill the fragment");
        expect(3, FragmentPresentationPolicy.mood(40, "countdown", "storm"),
                "the final cycle must overwrite the fragment with its rule presentation");
        expect("Syna 的残页·规则", FragmentPresentationPolicy.title(3),
                "the final fragment title must expose its stage in the inventory");
        BridgeConversation conversation = BridgeConversation.get();
        long beforeHelp = conversation.latestId();
        conversation.recordEpisodeHelp("Player", "tool_broke_minecraft:stone_pickaxe",
                "minecraft:stone_pickaxe", 1);
        BridgeConversation.Event help = conversation.after(beforeHelp).get(0);
        expect("episode_help", help.type(),
                "automatic gifts must enter the same ordered Bridge event stream as chat");
        expect("minecraft:stone_pickaxe", help.item(),
                "the Bridge gift event must preserve the confirmed item fact");
        expect(5, SynaBoredomPolicy.entertainmentGain(SynaBoredomPolicy.Activity.MINING, 0),
                "a fresh activity must grant its full entertainment value");
        expect(1, SynaBoredomPolicy.entertainmentGain(SynaBoredomPolicy.Activity.MINING, 8),
                "repeating one activity must reduce its value close to the minimum");
        expect(20, SynaBoredomPolicy.pressureFloor(SynaBoredomPolicy.PRESSURE_INTERVAL_TICKS * 20L, 1),
                "cycle pressure must rise with online cycle time");
        expect(20, SynaBoredomPolicy.afterEntertainment(24,
                        SynaBoredomPolicy.PRESSURE_INTERVAL_TICKS * 20L, 1,
                        SynaBoredomPolicy.Activity.DIMENSION_TRAVEL, 0),
                "entertainment must never reduce boredom below cycle pressure");
        expect(true, SynaBoredomPolicy.horrorChance(90) > SynaBoredomPolicy.horrorChance(20),
                "high boredom must increase horror opportunity probability");
        expect("minecraft:zombie", HorrorEntitySafety.normalizeAllowedId("Minecraft:Zombie"),
                "safe mob ids must normalize");
        expect(null, HorrorEntitySafety.normalizeAllowedId("minecraft:creeper"),
                "world-damaging mobs must be rejected");
        expect(true, ManifestationPolicy.shouldDepart(0, true, true),
                "an expired idle manifestation must depart even while visible");
        expect(false, ManifestationPolicy.shouldDepart(0, false, true),
                "horror manifestations must remain under the horror director");
        expect(false, ManifestationPolicy.shouldDepart(1, true, true),
                "an unexpired idle manifestation must remain");
        expect(true, PresenceProofPolicy.canTrigger(100L, 0L),
                "the first explicit dare may receive a proof");
        expect(false, PresenceProofPolicy.canTrigger(1000L, 500L),
                "repeated dares inside the cooldown must not spam effects");
        expect(true, PresenceProofPolicy.canTrigger(2000L, 500L),
                "a proof may run again after the cooldown");
        expect("PHANTOM_STEPS", PresenceProofPolicy.choose(1, 4).name(),
                "chapter one must stay with restrained footsteps");
        expect("MANIFESTATION", PresenceProofPolicy.choose(3, 2).name(),
                "later chapters may prove presence with a manifestation");
        expect(true, DangerousSilencePolicy.canSchedule(2000L, 0L, 0L),
                "the first dangerous silence may be scheduled");
        expect(false, DangerousSilencePolicy.canSchedule(2000L, 2100L, 0L),
                "a pending dangerous silence must not be duplicated");
        expect(false, DangerousSilencePolicy.canSchedule(2000L, 0L, 1000L),
                "resolved dangerous silence must enforce its cooldown");
        expect(true, DangerousSilencePolicy.delayTicks(3, 2) >= DangerousSilencePolicy.MIN_DELAY_TICKS,
                "dangerous silence must never resolve immediately");
        expect("MANIFESTATION", DangerousSilencePolicy.choose(3, 2).name(),
                "later chapters may resolve silence through manifestation");
        expect(1, IdentityLorePolicy.allowedVersion(IdentityLorePolicy.Topic.ORIGIN, 1, java.util.Set.of()),
                "the surface origin memory must be available from the beginning");
        expect(0, IdentityLorePolicy.allowedVersion(IdentityLorePolicy.Topic.NAME, 1, java.util.Set.of()),
                "name provenance must remain unavailable before chapter two");
        expect(1, IdentityLorePolicy.allowedVersion(IdentityLorePolicy.Topic.BELLS, 3, java.util.Set.of()),
                "the surface bell aversion may be discussed after the boundary chapter");
        expect(2, IdentityLorePolicy.allowedVersion(IdentityLorePolicy.Topic.ORIGIN, 4,
                        java.util.Set.of("identity_fragment")),
                "the identity fragment must unlock the revised origin account");
        String[] nameFragments = TrueNameMysteryPolicy.fragments(123456789L);
        expect(3, nameFragments.length,
                "each world true name must have exactly three clue fragments");
        expect(String.join("", nameFragments), TrueNameMysteryPolicy.trueName(123456789L),
                "the complete true name must preserve fragment order");
        expect(TrueNameMysteryPolicy.trueName(123456789L),
                TrueNameMysteryPolicy.parseRitualCandidate(
                        "我以你的真名封印你：" + TrueNameMysteryPolicy.trueName(123456789L)),
                "the exact ritual sentence must extract a normalized candidate");
        expect(null, TrueNameMysteryPolicy.parseRitualCandidate(
                        "你的真名是 " + TrueNameMysteryPolicy.trueName(123456789L)),
                "ordinary name guesses must not enter ritual validation");
        expect(true, TrueNameMysteryPolicy.hasValidRitualSyntax(
                        "我以你的真名封印你：" + TrueNameMysteryPolicy.trueName(123456789L)),
                "the complete ritual sentence must pass the syntax gate");
        expect(true, TrueNameMysteryPolicy.matches(123456789L,
                        TrueNameMysteryPolicy.trueName(123456789L).toUpperCase(java.util.Locale.ROOT)),
                "ritual comparison may ignore Latin letter case");
        expect(false, TrueNameMysteryPolicy.matches(123456789L, "wrongname"),
                "wrong names must receive only a uniform failure result");
        System.out.println("tunnel reveal policy regression checks passed");
    }

    private static void expect(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void expect(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void expect(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
