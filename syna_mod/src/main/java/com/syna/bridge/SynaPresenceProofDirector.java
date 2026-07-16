package com.syna.bridge;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

final class SynaPresenceProofDirector {
    private SynaPresenceProofDirector() {}

    static JsonObject execute(ServerPlayer player) {
        if (player == null) return receipt(false, "presence_proof_no_player", "none");
        if (!"calm".equals(SynaController.get().getHorrorStage())) {
            return receipt(false, "presence_proof_horror_active", "none");
        }

        SynaStoryData data = SynaStoryData.get(player.server);
        if (!PresenceProofPolicy.canTrigger(data.storyTicks, data.lastPresenceProofTick)) {
            long remaining = PresenceProofPolicy.COOLDOWN_TICKS
                    - Math.max(0L, data.storyTicks - data.lastPresenceProofTick);
            JsonObject receipt = receipt(false, "presence_proof_cooldown", "none");
            receipt.addProperty("cooldownTicks", Math.max(0L, remaining));
            return receipt;
        }

        PresenceProofPolicy.Proof proof = PresenceProofPolicy.choose(data.chapter, data.presenceProofCount);
        boolean completed = apply(player, proof);
        if (!completed) return receipt(false, "presence_proof_failed", proofName(proof));

        // Zero means "never triggered", so preserve the cooldown even during the first server tick.
        data.lastPresenceProofTick = Math.max(1L, data.storyTicks);
        data.presenceProofCount++;
        data.pressure = Math.min(100, data.pressure + 2);
        data.lastReason = "presence_proof:" + proofName(proof);
        data.setDirty();
        BridgeState.get().setLastEvent("presence_proof:" + proofName(proof)
                + ":" + player.getGameProfile().getName());
        return receipt(true, "presence_proof_completed", proofName(proof));
    }

    private static boolean apply(ServerPlayer player, PresenceProofPolicy.Proof proof) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 behind = player.position().subtract(horizontal.normalize().scale(5.0D));
        return switch (proof) {
            case PHANTOM_STEPS -> {
                player.serverLevel().playSound(null, behind.x, behind.y, behind.z,
                        SoundEvents.WOOD_STEP, SoundSource.AMBIENT, 0.9F, 0.5F);
                yield true;
            }
            case BRIEF_DARKNESS -> {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0,
                        false, false, false));
                yield true;
            }
            case MANIFESTATION -> {
                SynaController.get().summonSilently(player, "presence_proof", true);
                yield SynaController.get().getSyna() != null;
            }
            case DISTANT_KNOCK -> {
                player.serverLevel().playSound(null, behind.x + 3.0D, behind.y, behind.z - 2.0D,
                        SoundEvents.STONE_HIT, SoundSource.AMBIENT, 0.85F, 0.42F);
                yield true;
            }
        };
    }

    private static JsonObject receipt(boolean accepted, String result, String proof) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("accepted", accepted);
        receipt.addProperty("completed", accepted);
        receipt.addProperty("result", result);
        receipt.addProperty("proof", proof);
        return receipt;
    }

    private static String proofName(PresenceProofPolicy.Proof proof) {
        return proof.name().toLowerCase(java.util.Locale.ROOT);
    }
}
