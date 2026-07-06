package com.syna.bridge;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class AliceEntity extends PathfinderMob {
    private static final EntityDataAccessor<Boolean> DATA_MINING_SWING = SynchedEntityData.defineId(AliceEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_HORROR_STAGE = SynchedEntityData.defineId(AliceEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HORROR_COUNTDOWN = SynchedEntityData.defineId(AliceEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_HORROR_TARGET = SynchedEntityData.defineId(AliceEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_HORROR_CHALLENGE = SynchedEntityData.defineId(AliceEntity.class, EntityDataSerializers.STRING);

    private boolean miningSwing;
    private final SynaInventory inventory = new SynaInventory();

    public AliceEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setCanPickUpLoot(true);
        setPersistenceRequired();
        ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
        ((GroundPathNavigation) this.getNavigation()).setCanPassDoors(true);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_MINING_SWING, false);
        this.entityData.define(DATA_HORROR_STAGE, 0);
        this.entityData.define(DATA_HORROR_COUNTDOWN, 0);
        this.entityData.define(DATA_HORROR_TARGET, "");
        this.entityData.define(DATA_HORROR_CHALLENGE, "");
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, IronGolem.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && this.isAlive()) {
            this.setLastHurtByMob(null);
            this.setTarget(null);
            this.getNavigation().stop();
            SynaController.get().onSynaAttacked(this, source, amount);
        }
        return result;
    }

    @Override
    protected void customServerAiStep() {
        this.setLastHurtByMob(null);
        this.setTarget(null);
        super.customServerAiStep();
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    @Override
    public void die(DamageSource damageSource) {
        if (!this.level().isClientSide) {
            int droppedStacks = inventory.dropAll(this);
            if (droppedStacks > 0) {
                BridgeState.get().addDebug("inventory_drop_all:on_death stacks=" + droppedStacks + ",tick=" + this.tickCount);
            }
        }
        super.die(damageSource);
        if (!this.level().isClientSide && this.level() instanceof ServerLevel) {
            SynaController.get().onSynaDied(this, damageSource);
        }
    }

    @Override
    public boolean wantsToPickUp(ItemStack stack) {
        return inventory.canAccept(stack);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public void setMiningSwing(boolean miningSwing) {
        if (this.miningSwing != miningSwing && !this.level().isClientSide) {
            BridgeState.get().addDebug("miningSwing=" + miningSwing + ",tick=" + this.tickCount);
        }
        this.miningSwing = miningSwing;
        this.entityData.set(DATA_MINING_SWING, miningSwing);
    }

    public boolean isMiningSwing() {
        return this.entityData.get(DATA_MINING_SWING);
    }

    public int getHorrorStage() {
        return this.entityData.get(DATA_HORROR_STAGE);
    }

    public int getHorrorCountdownTicks() {
        return this.entityData.get(DATA_HORROR_COUNTDOWN);
    }

    public String getHorrorTargetName() {
        return this.entityData.get(DATA_HORROR_TARGET);
    }

    public String getHorrorChallengeText() {
        return this.entityData.get(DATA_HORROR_CHALLENGE);
    }

    public void setHorrorState(int stage, int countdownTicks, String targetName) {
        setHorrorState(stage, countdownTicks, targetName, "");
    }

    public void setHorrorState(int stage, int countdownTicks, String targetName, String challengeText) {
        this.entityData.set(DATA_HORROR_STAGE, Math.max(0, stage));
        this.entityData.set(DATA_HORROR_COUNTDOWN, Math.max(0, countdownTicks));
        this.entityData.set(DATA_HORROR_TARGET, targetName == null ? "" : targetName);
        this.entityData.set(DATA_HORROR_CHALLENGE, challengeText == null ? "" : challengeText);
    }

    public SynaInventory getInventory() {
        return inventory;
    }

    public ItemStack addToInventory(ItemStack stack) {
        return inventory.insert(stack);
    }

    public int getInventoryOccupiedSlots() {
        return inventory.getOccupiedSlots();
    }

    public int getInventoryTotalItemCount() {
        return inventory.getTotalItemCount();
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        if (this.level().isClientSide || itemEntity == null || !itemEntity.isAlive()) {
            return;
        }

        ItemStack pickedStack = itemEntity.getItem().copy();
        int accepted = inventory.insertFromEntity(itemEntity);
        if (accepted <= 0) {
            return;
        }

        this.take(itemEntity, accepted);
        BridgeState.get().addDebug("inventory_pickup:auto item=" + pickedStack.getItem() + ",accepted=" + accepted + ",occupied=" + inventory.getOccupiedSlots());
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            this.setItemInHand(InteractionHand.MAIN_HAND, inventory.getMainHandItem().copy());
        }
        if (!this.level().isClientSide && miningSwing && this.tickCount % 6 == 0) {
            BridgeState.get().addDebug("swing_main_hand,tick=" + this.tickCount + ",yRot=" + this.getYRot() + ",xRot=" + this.getXRot());
            this.swing(InteractionHand.MAIN_HAND, true);
        }
    }
}