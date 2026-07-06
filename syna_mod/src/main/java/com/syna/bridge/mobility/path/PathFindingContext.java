package com.syna.bridge.mobility.path;

import com.syna.bridge.AliceEntity;
import com.syna.bridge.SynaInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

public record PathFindingContext(
        ServerLevel level,
        BlockPos startFeet,
        EntityDimensions entityDimensions,
        int supportBlockCount
) {
    public static PathFindingContext from(ServerLevel level, AliceEntity syna) {
        return new PathFindingContext(level, syna.blockPosition(), syna.getDimensions(syna.getPose()),
                countSupportBlocks(syna.getInventory()));
    }

    private static int countSupportBlocks(SynaInventory inventory) {
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
}