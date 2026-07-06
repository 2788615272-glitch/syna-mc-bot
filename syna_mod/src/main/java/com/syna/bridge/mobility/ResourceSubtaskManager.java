package com.syna.bridge.mobility;

import com.syna.bridge.AliceEntity;
import com.syna.bridge.BridgeState;
import com.syna.bridge.SynaInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

public final class ResourceSubtaskManager {
    private static final int DEFAULT_SEARCH_RADIUS = 8;
    private static final int BREAK_TICKS_REQUIRED = 24;
    private static final double INTERACT_RANGE_SQR = 16.0D;
    private static final Set<Block> SUPPORT_SOURCE_BLOCKS = Set.of(
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.STONE,
            Blocks.ANDESITE,
            Blocks.DIORITE,
            Blocks.GRANITE
    );

    private boolean active;
    private ResourceRequirement requirement;
    private int targetSupportCount;
    private int searchRadius = DEFAULT_SEARCH_RADIUS;
    private BlockPos searchAnchor;
    private BlockPos targetBlock;
    private int breakTicks;
    private String detail = "idle";

    public boolean isActive() {
        return active;
    }

    public String detail() {
        return detail;
    }

    public void startSubtask(AliceEntity syna, ResourceRequirement requirement) {
        if (syna == null || requirement == null || requirement.type() != ResourceType.SUPPORT_BLOCK) {
            cancel("invalid_resource_subtask");
            return;
        }
        int current = countSupportBlocks(syna.getInventory());
        this.requirement = requirement;
        this.targetSupportCount = current + Math.max(1, requirement.count());
        this.searchRadius = DEFAULT_SEARCH_RADIUS;
        this.searchAnchor = syna.blockPosition().immutable();
        this.targetBlock = null;
        this.breakTicks = 0;
        this.active = true;
        this.detail = "collect_support_blocks:" + current + "/" + targetSupportCount;
        BridgeState.get().setLastEvent("resource_subtask_started:support_block:" + requirement.count());
    }

    public TickResult tick(AliceEntity syna) {
        if (!active) {
            return TickResult.idle(detail);
        }
        if (syna == null || !(syna.level() instanceof ServerLevel level)) {
            cancel("missing_syna_or_level");
            return TickResult.failed(detail);
        }

        int current = countSupportBlocks(syna.getInventory());
        if (current >= targetSupportCount) {
            active = false;
            clearBreakProgress(level);
            detail = "support_ready:" + current + "/" + targetSupportCount;
            BridgeState.get().setLastEvent("resource_subtask_completed:support_block:" + current);
            return TickResult.completed(detail);
        }

        if (targetBlock == null || !isValidSource(level, targetBlock)) {
            targetBlock = findNearestSource(level, syna.blockPosition());
            breakTicks = 0;
            if (targetBlock == null) {
                detail = "support_source_not_found:" + current + "/" + targetSupportCount;
                return TickResult.running(detail);
            }
        }

        if (!syna.blockPosition().closerThan(targetBlock, 4.0D)) {
            syna.getNavigation().moveTo(targetBlock.getX() + 0.5D, targetBlock.getY(), targetBlock.getZ() + 0.5D, 1.0D);
            detail = "moving_to_support_source:" + formatPos(targetBlock) + ":" + current + "/" + targetSupportCount;
            return TickResult.running(detail);
        }

        double dx = syna.getX() - (targetBlock.getX() + 0.5D);
        double dy = syna.getEyeY() - (targetBlock.getY() + 0.5D);
        double dz = syna.getZ() - (targetBlock.getZ() + 0.5D);
        if (dx * dx + dy * dy + dz * dz > INTERACT_RANGE_SQR) {
            detail = "aligning_support_source:" + formatPos(targetBlock);
            return TickResult.running(detail);
        }

        breakTicks++;
        int progress = Math.min(9, (int) ((breakTicks / (double) BREAK_TICKS_REQUIRED) * 10.0D));
        level.destroyBlockProgress(syna.getId(), targetBlock, progress);
        syna.swing(InteractionHand.MAIN_HAND);
        detail = "breaking_support_source:" + formatPos(targetBlock) + ":" + current + "/" + targetSupportCount;
        if (breakTicks < BREAK_TICKS_REQUIRED) {
            return TickResult.running(detail);
        }

        BlockState state = level.getBlockState(targetBlock);
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) {
            item = Items.DIRT;
        }
        level.destroyBlock(targetBlock, false, syna);
        clearBreakProgress(level);
        syna.getInventory().insert(new ItemStack(item, 1));
        targetBlock = null;
        breakTicks = 0;
        detail = "collected_support_block:" + (current + 1) + "/" + targetSupportCount;
        return TickResult.running(detail);
    }

    public void cancel(String reason) {
        active = false;
        requirement = null;
        targetBlock = null;
        breakTicks = 0;
        detail = reason == null ? "cancelled" : reason;
    }

    public static int countSupportBlocks(SynaInventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isSupportBlock(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean isSupportBlock(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        return blockItem.getBlock().defaultBlockState().isSolid();
    }

    private BlockPos findNearestSource(ServerLevel level, BlockPos origin) {
        BlockPos anchor = searchAnchor == null ? origin : searchAnchor;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -2; y <= 2; y++) {
            for (int x = -searchRadius; x <= searchRadius; x++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos pos = anchor.offset(x, y, z);
                    if (!isValidSource(level, pos)) {
                        continue;
                    }
                    double distance = pos.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean isValidSource(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return SUPPORT_SOURCE_BLOCKS.contains(state.getBlock())
                && state.getDestroySpeed(level, pos) >= 0.0F
                && level.getBlockState(pos.above()).canBeReplaced();
    }

    private void clearBreakProgress(ServerLevel level) {
        if (level != null && targetBlock != null) {
            level.destroyBlockProgress(-1, targetBlock, -1);
        }
    }

    private String formatPos(BlockPos pos) {
        if (pos == null) {
            return "null";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public record TickResult(Type type, String detail) {
        public static TickResult idle(String detail) {
            return new TickResult(Type.IDLE, detail);
        }

        public static TickResult running(String detail) {
            return new TickResult(Type.RUNNING, detail);
        }

        public static TickResult completed(String detail) {
            return new TickResult(Type.COMPLETED, detail);
        }

        public static TickResult failed(String detail) {
            return new TickResult(Type.FAILED, detail);
        }
    }

    public enum Type {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED
    }
}