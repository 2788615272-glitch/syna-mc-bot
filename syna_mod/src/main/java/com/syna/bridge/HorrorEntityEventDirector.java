package com.syna.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public final class HorrorEntityEventDirector {
    private static final HorrorEntityEventDirector INSTANCE = new HorrorEntityEventDirector();
    private static final int MAX_QUEUE = 4;
    private static final int MAX_RECEIPTS = 16;
    private final Deque<Request> queue = new ArrayDeque<>();
    private final Deque<Receipt> receipts = new ArrayDeque<>();
    private ActiveEvent active;
    private long nextId = 1L;

    private HorrorEntityEventDirector() {}

    public static HorrorEntityEventDirector get() {
        return INSTANCE;
    }

    public ScheduleResult schedule(ServerPlayer player, String templateName, String entityId, String reason) {
        Template template = Template.parse(templateName);
        String normalizedId = HorrorEntitySafety.normalizeAllowedId(entityId);
        if (player == null || template == null || normalizedId == null || queue.size() >= MAX_QUEUE) {
            return ScheduleResult.rejected("invalid_or_queue_full");
        }
        if (active != null && active.request.targetUuid.equals(player.getUUID())
                && active.request.template == template) return ScheduleResult.rejected("template_already_active");
        for (Request queued : queue) {
            if (queued.targetUuid.equals(player.getUUID()) && queued.template == template) {
                return ScheduleResult.rejected("template_already_scheduled");
            }
        }
        Request request = new Request(nextId++, template, normalizedId, player.getUUID(), safe(reason));
        queue.addLast(request);
        BridgeState.get().setLastEvent("horror_event_scheduled:" + template.id + ":" + normalizedId);
        return new ScheduleResult(true, request.id, "scheduled");
    }

    public EventStatus status(long eventId) {
        if (eventId <= 0) return new EventStatus(EventState.UNKNOWN, "invalid_id");
        if (active != null && active.request.id == eventId) {
            return new EventStatus(active.exposed ? EventState.EXPOSED : EventState.ACTIVE,
                    active.exposed ? "exposed" : "spawned");
        }
        for (Request request : queue) {
            if (request.id == eventId) return new EventStatus(EventState.SCHEDULED, "scheduled");
        }
        for (Receipt receipt : receipts) {
            if (receipt.id == eventId) {
                EventState state = "completed".equals(receipt.outcome) ? EventState.COMPLETED : EventState.ABORTED;
                return new EventStatus(state, receipt.reason);
            }
        }
        return new EventStatus(EventState.UNKNOWN, "not_found");
    }

    public void tick(MinecraftServer server) {
        if (server == null) return;
        if (active == null && !queue.isEmpty()) startNext(server);
        if (active != null) tickActive(server);
    }

    public boolean isDirectedEntity(Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean("SynaDirectedEntity");
    }

    public void reset(MinecraftServer server) {
        Mob mob = active == null || server == null ? null : findMob(server, active.entityUuid);
        if (mob != null) mob.discard();
        active = null;
        queue.clear();
        receipts.clear();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("queued", queue.size());
        JsonArray scheduled = new JsonArray();
        for (Request request : queue) {
            JsonObject item = new JsonObject();
            item.addProperty("id", request.id);
            item.addProperty("template", request.template.id);
            item.addProperty("entity", request.entityId);
            item.addProperty("state", "scheduled");
            item.addProperty("reason", request.reason);
            scheduled.add(item);
        }
        json.add("scheduled", scheduled);
        if (active == null) {
            json.add("active", null);
        } else {
            JsonObject current = new JsonObject();
            current.addProperty("id", active.request.id);
            current.addProperty("template", active.request.template.id);
            current.addProperty("entity", active.request.entityId);
            current.addProperty("state", active.exposed ? "exposed" : "active");
            current.addProperty("remainingTicks", Math.max(0, active.remainingTicks));
            current.addProperty("reason", active.request.reason);
            json.add("active", current);
        }
        JsonArray recent = new JsonArray();
        for (Receipt receipt : receipts) {
            JsonObject item = new JsonObject();
            item.addProperty("id", receipt.id);
            item.addProperty("template", receipt.template);
            item.addProperty("entity", receipt.entityId);
            item.addProperty("outcome", receipt.outcome);
            item.addProperty("reason", receipt.reason);
            recent.add(item);
        }
        json.add("receipts", recent);
        return json;
    }

    public String statusLine() {
        if (active != null) {
            return "entityEvent=" + active.request.id + ":" + active.request.template.id + ":"
                    + (active.exposed ? "exposed" : "active") + ":remaining=" + Math.max(0, active.remainingTicks);
        }
        if (!queue.isEmpty()) {
            Request request = queue.peekFirst();
            return "entityEvent=" + request.id + ":" + request.template.id + ":scheduled";
        }
        Receipt receipt = receipts.peekLast();
        return receipt == null ? "entityEvent=none"
                : "entityEvent=" + receipt.id + ":" + receipt.template + ":" + receipt.outcome + ":" + receipt.reason;
    }

    private void startNext(MinecraftServer server) {
        Request request = queue.removeFirst();
        ServerPlayer target = server.getPlayerList().getPlayer(request.targetUuid);
        if (target == null || !target.isAlive() || target.isCreative() || target.isSpectator()) {
            finish(request, "aborted", "target_unavailable");
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(request.entityId);
        EntityType<?> type = id == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(id);
        Entity created = type == null ? null : type.create(target.serverLevel());
        if (!(created instanceof Mob mob)) {
            if (created != null) created.discard();
            finish(request, "aborted", "entity_is_not_a_mob");
            return;
        }
        configure(mob, target, request.template);
        BlockPos spawn = findSpawn(target.serverLevel(), target, request.template, mob);
        if (spawn == null) {
            mob.discard();
            finish(request, "aborted", "no_safe_spawn");
            return;
        }
        mob.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                target.getYRot(), 0.0F);
        if (!target.serverLevel().addFreshEntity(mob)) {
            finish(request, "aborted", "spawn_rejected");
            return;
        }
        active = new ActiveEvent(request, mob.getUUID(), request.template.durationTicks);
        BridgeState.get().setLastEvent("horror_event_active:" + request.template.id + ":" + request.entityId);
    }

    private void tickActive(MinecraftServer server) {
        ActiveEvent event = active;
        ServerPlayer target = server.getPlayerList().getPlayer(event.request.targetUuid);
        Mob mob = findMob(server, event.entityUuid);
        if (target == null || !target.isAlive() || mob == null || !mob.isAlive() || target.level() != mob.level()) {
            completeActive("aborted", mob == null ? "entity_missing" : "target_unavailable", mob);
            return;
        }
        event.remainingTicks--;
        PlayerAttentionTracker.ActorSnapshot attention = PlayerAttentionTracker.get().observe(target, mob);
        boolean watched = attention.visible();
        if (watched && !event.exposed) {
            event.exposed = true;
            BridgeState.get().setLastEvent("horror_event_exposed:" + event.request.template.id);
        }
        switch (event.request.template) {
            case WATCHER -> {
                mob.getLookControl().setLookAt(target, 360.0F, 360.0F);
                mob.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
                event.observedTicks = watched ? event.observedTicks + 1 : 0;
                if (event.observedTicks >= 24) {
                    completeActive("completed", "watcher_discovered", mob);
                    return;
                }
            }
            case STALKER -> {
                mob.setTarget(null);
                if (watched) mob.getNavigation().stop();
                else mob.getNavigation().moveTo(target, 1.05D);
            }
            case AMBUSH, ENFORCER -> {
                tickMeleeActor(event, mob, target, attention);
            }
        }
        if (event.remainingTicks <= 0) completeActive("completed", "lifetime_elapsed", mob);
    }

    private void configure(Mob mob, ServerPlayer target, Template template) {
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
        mob.getPersistentData().putBoolean("SynaDirectedEntity", true);
        mob.getPersistentData().putString("SynaEventTemplate", template.id);
        mob.getPersistentData().putUUID("SynaEventTarget", target.getUUID());
        mob.goalSelector.removeAllGoals(goal -> true);
        mob.targetSelector.removeAllGoals(goal -> true);
        mob.setTarget(null);
        mob.setNoAi(template.actorKind == ActorKind.VISUAL);
        setAttribute(mob, Attributes.MAX_HEALTH, template.health);
        mob.setHealth((float) Math.min(template.health, mob.getMaxHealth()));
        setAttribute(mob, Attributes.ATTACK_DAMAGE, template.damage);
        setAttribute(mob, Attributes.MOVEMENT_SPEED, template.speed);
    }

    private void tickMeleeActor(ActiveEvent event, Mob mob, ServerPlayer target,
                                PlayerAttentionTracker.ActorSnapshot attention) {
        mob.setTarget(null);
        if (attention.distance() > 1.8D) {
            mob.getNavigation().moveTo(target, event.request.template.speedMultiplier);
            return;
        }
        mob.getNavigation().stop();
        if (event.attackCooldownTicks > 0) {
            event.attackCooldownTicks--;
            return;
        }
        target.hurt(mob.damageSources().mobAttack(mob), (float) event.request.template.damage);
        event.attackCooldownTicks = 20;
    }

    private void setAttribute(Mob mob, net.minecraft.world.entity.ai.attributes.Attribute attribute, double value) {
        if (mob.getAttribute(attribute) != null) mob.getAttribute(attribute).setBaseValue(value);
    }

    private BlockPos findSpawn(ServerLevel level, ServerPlayer player, Template template, Mob mob) {
        net.minecraft.core.Direction forward = player.getDirection();
        net.minecraft.core.Direction primary = template == Template.AMBUSH ? forward : forward.getOpposite();
        net.minecraft.core.Direction[] directions = {
                primary, primary.getClockWise(), primary.getCounterClockWise(), primary.getOpposite()
        };
        int minDistance = template == Template.WATCHER ? 6 : 3;
        int maxDistance = template == Template.WATCHER ? 12 : 8;
        for (net.minecraft.core.Direction direction : directions) {
            for (int distance = minDistance; distance <= maxDistance; distance++) {
                BlockPos base = player.blockPosition().relative(direction, distance);
                for (int yOffset : new int[]{0, 1, -1, 2, -2}) {
                    BlockPos pos = base.offset(0, yOffset, 0);
                    if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                            && level.getBlockState(pos.below()).isSolid()) {
                        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                                player.getYRot(), 0.0F);
                        if (level.noCollision(mob)) return pos;
                    }
                }
            }
        }
        return null;
    }

    private Mob findMob(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof Mob mob) return mob;
        }
        return null;
    }

    private void completeActive(String outcome, String reason, Mob mob) {
        Request request = active.request;
        if (mob != null) mob.discard();
        active = null;
        finish(request, outcome, reason);
    }

    private void finish(Request request, String outcome, String reason) {
        receipts.addFirst(new Receipt(request.id, request.template.id, request.entityId, outcome, reason));
        while (receipts.size() > MAX_RECEIPTS) receipts.removeLast();
        BridgeState.get().setLastEvent("horror_event_" + outcome + ":" + request.template.id + ":" + reason);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "director";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private enum Template {
        WATCHER("watcher", ActorKind.VISUAL, 20 * 18, 20.0D, 0.0D, 0.0D, 0.0D),
        STALKER("stalker", ActorKind.PUPPET, 20 * 20, 16.0D, 0.0D, 0.28D, 1.05D),
        AMBUSH("ambush", ActorKind.MELEE, 20 * 8, 14.0D, 3.0D, 0.30D, 1.10D),
        ENFORCER("enforcer", ActorKind.MELEE, 20 * 12, 18.0D, 5.0D, 0.32D, 1.15D);

        private final String id;
        private final ActorKind actorKind;
        private final int durationTicks;
        private final double health;
        private final double damage;
        private final double speed;
        private final double speedMultiplier;

        Template(String id, ActorKind actorKind, int durationTicks, double health, double damage,
                 double speed, double speedMultiplier) {
            this.id = id;
            this.actorKind = actorKind;
            this.durationTicks = durationTicks;
            this.health = health;
            this.damage = damage;
            this.speed = speed;
            this.speedMultiplier = speedMultiplier;
        }

        private static Template parse(String value) {
            if (value == null) return null;
            for (Template template : values()) if (template.id.equalsIgnoreCase(value.trim())) return template;
            return null;
        }
    }

    private enum ActorKind {
        VISUAL, PUPPET, MELEE
    }

    private record Request(long id, Template template, String entityId, UUID targetUuid, String reason) {}
    private record Receipt(long id, String template, String entityId, String outcome, String reason) {}

    public record ScheduleResult(boolean accepted, long eventId, String reason) {
        private static ScheduleResult rejected(String reason) {
            return new ScheduleResult(false, -1L, reason);
        }
    }

    public record EventStatus(EventState state, String reason) {}

    public enum EventState {
        SCHEDULED, ACTIVE, EXPOSED, COMPLETED, ABORTED, UNKNOWN
    }

    private static final class ActiveEvent {
        private final Request request;
        private final UUID entityUuid;
        private int remainingTicks;
        private int observedTicks;
        private int attackCooldownTicks;
        private boolean exposed;

        private ActiveEvent(Request request, UUID entityUuid, int remainingTicks) {
            this.request = request;
            this.entityUuid = entityUuid;
            this.remainingTicks = remainingTicks;
        }
    }
}
