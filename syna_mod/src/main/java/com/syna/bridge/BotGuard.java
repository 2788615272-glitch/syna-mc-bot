package com.syna.bridge;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * Server-side guard for mineflayer bot players — NUCLEAR edition v2.
 *
 * Three layers of defense + controlled knockback via teleport:
 * 1. LivingAttackEvent (HIGHEST) — cancels the ENTIRE damage pipeline before
 *    knockback() is even called. Then manually applies HP reduction + hurt animation
 *    + controlled teleport-based knockback.
 * 2. LivingHurtEvent + LivingKnockBackEvent — safety nets in case anything bypasses layer 1.
 * 3. Network-level outbound packet filter — intercepts ClientboundSetEntityMotionPacket
 *    and ClientboundExplodePacket destined for bot players.
 *
 * The controlled knockback uses server-authoritative teleport instead of velocity packets,
 * so mineflayer never receives unexpected motion data that could cause disconnection.
 */
public class BotGuard {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final java.util.Map<java.util.UUID, Long> LAST_ENV_DAMAGE_TICK = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Bot identity is shared with the rest of the mod via {@link BotIdentity}.
     * BotGuard exposes addBotName for backward compatibility with anything
     * that already calls BotGuard.addBotName(...) — it just delegates.
     */
    public static void addBotName(String name) {
        BotIdentity.addBotName(name);
        LOGGER.info("[BotGuard] Added exact bot name: {}", name);
    }

    private static boolean isBot(String playerName) {
        return BotIdentity.isBot(playerName);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 1: LivingAttackEvent — earliest possible interception
    // This fires BEFORE knockback() is called in LivingEntity.hurt()
    // ═══════════════════════════════════════════════════════════════════════

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String name = player.getGameProfile().getName();
        if (!isBot(name)) return;

        float incomingDamage = event.getAmount();
        DamageSource source = event.getSource();
        String attackerName = describeAttacker(source);
        String sourceKind = describeSourceKind(source);
        String causeName = describeDamageCause(source);

        // Cancel the entire vanilla damage pipeline — no knockback, no velocity packet
        event.setCanceled(true);
        if (shouldThrottleEnvironmentDamage(player, source, sourceKind)) {
            LOGGER.info("[BotGuard] Throttled repeated environment damage for {} from {}", name, causeName);
            return;
        }

        float damage = calculateMitigatedDamage(player, source, incomingDamage);

        // Manually apply damage after armor/resistance mitigation.
        float currentHealth = player.getHealth();
        float newHealth = Math.max(0.0f, currentHealth - damage);
        player.setHealth(newHealth);

        broadcastHurtAnimation(player);
        player.invulnerableTime = 20;
        player.hurtTime = 10;
        player.hurtDuration = 10;

        try {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sounds.SoundEvents.PLAYER_HURT, player.getSoundSource(), 1.0f, 1.0f);
        } catch (Exception e) {
            // Ignore
        }

        applyControlledKnockback(player, source);

        BridgeState.get().setLastEvent("syna_attacked:" + attackerName + ":" + Math.round(damage) + ":bot_player:" + name + ":" + causeName + ":" + sourceKind);

        LOGGER.info("[BotGuard] ATTACK intercepted: {} took damage from {} ({}) | raw={} mitigated={} | HP: {} -> {}",
                name, source.type().msgId(), attackerName, String.format("%.1f", incomingDamage), String.format("%.1f", damage),
                String.format("%.1f", currentHealth), String.format("%.1f", newHealth));

        if (newHealth <= 0.0f) {
            LOGGER.info("[BotGuard] Bot '{}' died from {} (damage={})", name, source.type().msgId(), damage);
            player.die(source);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HURT ANIMATION — sends ClientboundHurtAnimationPacket to all players
    // This makes the bot model flash red when hit, visible to everyone.
    // ═══════════════════════════════════════════════════════════════════════

    private String describeAttacker(DamageSource source) {
        if (source == null) {
            return "unknown";
        }
        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        return attacker == null ? "unknown" : attacker.getName().getString();
    }

    private String describeDamageCause(DamageSource source) {
        if (source == null || source.type() == null) {
            return "unknown";
        }
        return safeEventToken(source.type().msgId());
    }

    private String describeSourceKind(DamageSource source) {
        if (source == null) {
            return "unknown";
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer) {
            return "player";
        }
        if (attacker != null || source.getDirectEntity() != null) {
            return "mob";
        }
        return "environment";
    }

    private boolean shouldThrottleEnvironmentDamage(ServerPlayer player, DamageSource source, String sourceKind) {
        if (!"environment".equals(sourceKind)) {
            return false;
        }
        String cause = describeDamageCause(source);
        if (!("in_wall".equals(cause) || "drown".equals(cause) || "starve".equals(cause) || "cactus".equals(cause) || "sweet_berry_bush".equals(cause))) {
            return false;
        }
        long now = player.level().getGameTime();
        Long last = LAST_ENV_DAMAGE_TICK.get(player.getUUID());
        if (last != null && now - last < 20L) {
            return true;
        }
        LAST_ENV_DAMAGE_TICK.put(player.getUUID(), now);
        return false;
    }

    private String safeEventToken(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace(':', '_').replace(' ', '_');
    }
    private float calculateMitigatedDamage(ServerPlayer player, DamageSource source, float amount) {
        if (amount <= 0.0f || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR)) {
            return Math.max(0.0f, amount);
        }
        float damage = CombatRules.getDamageAfterAbsorb(
                amount,
                (float) player.getArmorValue(),
                (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        if (player.hasEffect(MobEffects.DAMAGE_RESISTANCE) && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_RESISTANCE)) {
            int amplifier = player.getEffect(MobEffects.DAMAGE_RESISTANCE).getAmplifier();
            int remaining = 25 - ((amplifier + 1) * 5);
            damage = Math.max(damage * remaining / 25.0f, 0.0f);
        }
        return Math.max(0.0f, damage);
    }
    private void broadcastHurtAnimation(ServerPlayer bot) {
        // broadcastEntityEvent (byte 2) is the legacy method; also send the dedicated packet
        bot.level().broadcastEntityEvent(bot, (byte) 2);

        // ClientboundHurtAnimationPacket(entityId, yaw) — the yaw is the direction of the hit
        ClientboundHurtAnimationPacket hurtPacket = new ClientboundHurtAnimationPacket(bot);

        // Send to all players in the same level who can see this bot
        var server = bot.getServer();
        if (server != null) {
            for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
                recipient.connection.send(hurtPacket);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTROLLED KNOCKBACK — teleport-based, no velocity packets involved.
    //
    // Instead of sending a velocity packet (which mineflayer mishandles),
    // we compute the knockback destination and teleport the bot there.
    // The server's authoritative position stays in sync with the client.
    //
    // Supports:
    //   - Basic melee knockback (direction = attacker → bot)
    //   - Knockback enchantment (each level adds strength)
    //   - Explosion knockback (direction = explosion center → bot)
    //   - Sprinting bonus
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Apply controlled knockback to a bot player using teleport.
     *
     * @param bot    The bot player being knocked back
     * @param source The damage source (used to determine direction and type)
     */
    private void applyControlledKnockback(ServerPlayer bot, DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker == null) return;

        double dx = bot.getX() - attacker.getX();
        double dz = bot.getZ() - attacker.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < 0.01) {
            dx = -Math.sin(Math.toRadians(attacker.getYRot()));
            dz = Math.cos(Math.toRadians(attacker.getYRot()));
            horizontalDist = 1.0;
        }

        double dirX = dx / horizontalDist;
        double dirZ = dz / horizontalDist;

        double baseStrength = 0.4;
        int knockbackLevel = 0;
        if (attacker instanceof ServerPlayer attackerPlayer) {
            ItemStack weapon = attackerPlayer.getMainHandItem();
            knockbackLevel = weapon.getEnchantmentLevel(Enchantments.KNOCKBACK);
            if (attackerPlayer.isSprinting()) knockbackLevel += 1;
        }

        double totalHorizontal = baseStrength + (knockbackLevel * 0.4);
        double verticalLift = 0.15;
        double[] safe = findSafeKnockbackPosition(bot, dirX, dirZ, totalHorizontal, verticalLift);
        if (safe == null) {
            LOGGER.info("[BotGuard] Knockback for '{}' blocked by collision; staying put", bot.getGameProfile().getName());
            return;
        }

        bot.connection.teleport(safe[0], safe[1], safe[2], bot.getYRot(), bot.getXRot());
        LOGGER.info("[BotGuard] Applied controlled knockback to '{}': dir=({}, {}), strength={}, enchLvl={}, newPos=({}, {}, {})",
                bot.getGameProfile().getName(),
                String.format("%.2f", dirX), String.format("%.2f", dirZ),
                String.format("%.2f", totalHorizontal), knockbackLevel,
                String.format("%.1f", safe[0]), String.format("%.1f", safe[1]), String.format("%.1f", safe[2]));
    }

    /**
     * Apply explosion knockback to a bot player using teleport.
     * Called externally when an explosion affects a bot.
     *
     * @param bot             The bot player
     * @param explosionX      Explosion center X
     * @param explosionY      Explosion center Y
     * @param explosionZ      Explosion center Z
     * @param explosionPower  Explosion power (TNT=4, Creeper=3, Charged Creeper=6)
     */
    public static void applyExplosionKnockback(ServerPlayer bot, double explosionX, double explosionY, double explosionZ, float explosionPower) {
        double dx = bot.getX() - explosionX;
        double dy = bot.getY() - explosionY;
        double dz = bot.getZ() - explosionZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 0.1) dist = 0.1;

        double maxRadius = explosionPower * 2.0;
        double falloff = Math.max(0, 1.0 - (dist / maxRadius));
        double strength = falloff * explosionPower * 0.3;

        double dirX = dx / dist;
        double dirY = dy / dist;
        double dirZ = dz / dist;
        double horizontal = Math.sqrt(dirX * dirX + dirZ * dirZ);

        double[] safe = null;
        if (horizontal > 0.001) {
            safe = findSafeKnockbackPosition(bot, dirX / horizontal, dirZ / horizontal, strength, Math.max(dirY * strength, 0.2));
        }
        if (safe == null) {
            LOGGER.info("[BotGuard] Explosion knockback for '{}' blocked by collision; staying put", bot.getGameProfile().getName());
            return;
        }

        bot.connection.teleport(safe[0], safe[1], safe[2], bot.getYRot(), bot.getXRot());
        LOGGER.info("[BotGuard] Applied explosion knockback to '{}': power={}, dist={}, strength={}, newPos=({}, {}, {})",
                bot.getGameProfile().getName(), explosionPower,
                String.format("%.1f", dist), String.format("%.2f", strength),
                String.format("%.1f", safe[0]), String.format("%.1f", safe[1]), String.format("%.1f", safe[2]));
    }

    private static double[] findSafeKnockbackPosition(ServerPlayer bot, double dirX, double dirZ, double horizontal, double verticalLift) {
        double startX = bot.getX();
        double startY = bot.getY();
        double startZ = bot.getZ();
        for (double dist = horizontal; dist >= 0.05; dist -= 0.1) {
            double x = startX + dirX * dist;
            double y = startY + verticalLift;
            double z = startZ + dirZ * dist;
            double[] safe = adjustToSafeStandingSpot(bot, x, y, z);
            if (safe != null) return safe;
        }
        return null;
    }

    private static double[] adjustToSafeStandingSpot(ServerPlayer bot, double x, double y, double z) {
        double baseY = Mth.floor(y);
        for (int dy = 1; dy >= -2; dy--) {
            double candidateY = baseY + dy;
            AABB box = bot.getDimensions(bot.getPose()).makeBoundingBox(x, candidateY, z);
            if (bot.level().noCollision(bot, box)) {
                return new double[] { x, candidateY, z };
            }
        }
        return null;
    }
    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 2: Safety nets — in case something bypasses LivingAttackEvent
    // ═══════════════════════════════════════════════════════════════════════

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String name = player.getGameProfile().getName();
        if (!isBot(name)) return;

        // If we get here, something bypassed our AttackEvent handler. Still publish the event
        // so the LLM can react instead of denying that Syna was hit.
        String attackerName = describeAttacker(event.getSource());
        String sourceKind = describeSourceKind(event.getSource());
        String causeName = describeDamageCause(event.getSource());
        BridgeState.get().setLastEvent("syna_attacked:" + attackerName + ":" + Math.round(event.getAmount()) + ":bot_player:" + name + ":" + causeName + ":" + sourceKind);
        LOGGER.warn("[BotGuard] WARNING: LivingHurtEvent reached for {} (should have been caught at AttackEvent level!) Cancelling anyway. Source: {}, Attacker: {}, Amount: {}",
                name, event.getSource().type().msgId(), attackerName, event.getAmount());
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onKnockback(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String name = player.getGameProfile().getName();
        if (isBot(name)) {
            event.setCanceled(true);
            LOGGER.info("[BotGuard] Cancelled knockback event for {}", name);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 3: Network packet filter — absolute last line of defense
    // Injects a Netty handler into the bot's connection pipeline that
    // swallows velocity/explosion packets before they reach the wire.
    // ═══════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String name = player.getGameProfile().getName();
        if (!isBot(name)) return;

        LOGGER.info("[BotGuard] Bot '{}' logged in — installing 3-layer protection + controlled knockback", name);

        // Install netty channel handler to filter outbound packets
        try {
            ServerGamePacketListenerImpl connection = player.connection;
            var channel = connection.connection.channel();
            
            // Remove old handler if reconnecting
            try {
                channel.pipeline().remove("botguard_velocity_filter");
            } catch (Exception ignored) {}

            channel.pipeline().addBefore("packet_handler", "botguard_velocity_filter",
                new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        if (msg instanceof ClientboundSetEntityMotionPacket velPkt) {
                            // Only block velocity packets targeting the bot itself
                            if (velPkt.getId() == player.getId()) {
                                LOGGER.info("[BotGuard] BLOCKED velocity packet to {} | entityId={}", name, velPkt.getId());
                                promise.setSuccess(); // Silently consume
                                return;
                            }
                        }
                        if (msg instanceof ClientboundExplodePacket) {
                            // Explosion packets always carry player motion
                            LOGGER.info("[BotGuard] BLOCKED explosion packet to {}", name);
                            promise.setSuccess();
                            return;
                        }
                        super.write(ctx, msg, promise);
                    }
                });

            LOGGER.info("[BotGuard] Netty velocity filter installed for '{}'", name);
        } catch (Exception e) {
            LOGGER.error("[BotGuard] Failed to install netty filter for '{}': {}", name, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Anti-AFK: Reset idle timer every tick
    // ═══════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isBot(player.getGameProfile().getName())) {
                player.resetLastActionTime();
            }
        }
    }
}
