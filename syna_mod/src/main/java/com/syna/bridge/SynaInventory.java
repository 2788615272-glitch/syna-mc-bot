package com.syna.bridge;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class SynaInventory {
    public static final int MAIN_HAND_SLOT = 0;
    public static final int SLOT_COUNT = 36;

    private final SimpleContainer container = new SimpleContainer(SLOT_COUNT);

    public SimpleContainer container() {
        return container;
    }

    public int getContainerSize() {
        return container.getContainerSize();
    }

    public ItemStack getItem(int slot) {
        return container.getItem(slot);
    }

    public ItemStack getMainHandItem() {
        return getItem(MAIN_HAND_SLOT);
    }

    public boolean moveSlotToMainHand(int slot) {
        if (slot < 0 || slot >= container.getContainerSize()) {
            return false;
        }
        if (slot == MAIN_HAND_SLOT) {
            return true;
        }

        ItemStack selected = container.getItem(slot).copy();
        ItemStack mainHand = container.getItem(MAIN_HAND_SLOT).copy();
        container.setItem(MAIN_HAND_SLOT, selected);
        container.setItem(slot, mainHand);
        return true;
    }

    public boolean isEmpty() {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int getOccupiedSlots() {
        int used = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.getItem(i).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    public int getFreeSlots() {
        return getContainerSize() - getOccupiedSlots();
    }

    public int getTotalItemCount() {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }

    public int countItem(Item item) {
        if (item == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public boolean containsAtLeast(Item item, int count) {
        return countItem(item) >= Math.max(0, count);
    }

    public int removeItem(Item item, int count) {
        if (item == null || count <= 0) {
            return 0;
        }

        int remaining = count;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }

            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            container.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            remaining -= taken;
        }
        return count - remaining;
    }

    public boolean canAccept(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameTags(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public ItemStack insert(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack remaining = stack.copy();

        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (ItemStack.isSameItemSameTags(slot, remaining) && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(remaining.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(move);
                remaining.shrink(move);
                container.setItem(i, slot);
            }
        }

        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, remaining.copy());
                remaining = ItemStack.EMPTY;
            }
        }

        return remaining;
    }

    public int insertFromEntity(ItemEntity itemEntity) {
        if (itemEntity == null || !itemEntity.isAlive()) {
            return 0;
        }

        ItemStack original = itemEntity.getItem();
        if (original.isEmpty()) {
            return 0;
        }

        ItemStack remaining = insert(original.copy());
        int accepted = original.getCount() - remaining.getCount();
        if (accepted <= 0) {
            return 0;
        }

        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(remaining);
        }
        return accepted;
    }

    public List<ItemView> snapshot() {
        List<ItemView> items = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            items.add(new ItemView(i, stack.getItem().toString(), stack.getCount(), i == MAIN_HAND_SLOT));
        }
        return items;
    }

    public int dropAll(LivingEntity owner) {
        if (owner == null || owner.level().isClientSide) {
            return 0;
        }

        int droppedStacks = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            owner.spawnAtLocation(stack.copy());
            container.setItem(i, ItemStack.EMPTY);
            droppedStacks++;
        }
        return droppedStacks;
    }

    public record ItemView(int slot, String item, int count, boolean mainHand) {}
}