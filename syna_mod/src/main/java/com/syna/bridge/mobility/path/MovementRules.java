package com.syna.bridge.mobility.path;

import com.syna.bridge.mobility.MobilityRequest;
import com.syna.bridge.mobility.MovementPermission;
import com.syna.bridge.mobility.ResourceRequirement;
import com.syna.bridge.mobility.ResourceType;
import com.syna.bridge.mobility.safety.BuildingProtectionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class MovementRules {
    private final CostModel costModel = new CostModel();
    private final BuildingProtectionPolicy protectionPolicy = new BuildingProtectionPolicy();

    public boolean canStandAt(ServerLevel level, BlockPos feet, EntityDimensions dimensions) {
        return level != null
                && feet != null
                && dimensions != null
                && hasSolidSupport(level, feet)
                && hasBodyClearance(level, feet, dimensions);
    }

    public boolean hasSolidSupport(ServerLevel level, BlockPos feet) {
        BlockState floor = level.getBlockState(feet.below());
        return !floor.isAir() && !floor.canBeReplaced() && floor.getFluidState().isEmpty();
    }

    public boolean hasBodyClearance(ServerLevel level, BlockPos feet, EntityDimensions dimensions) {
        for (int dy = 0; dy < 2; dy++) {
            BlockState state = level.getBlockState(feet.above(dy));
            if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public boolean canMoveDirect(ServerLevel level, BlockPos fromFeet, BlockPos toFeet, EntityDimensions dimensions) {
        return canStandAt(level, toFeet, dimensions) && Math.abs(toFeet.getY() - fromFeet.getY()) <= 1;
    }

    public List<NodeTransition> getNeighbors(PathFindingContext context, MobilityRequest request, PathNode node) {
        if (context == null || node == null || context.level() == null || context.entityDimensions() == null) {
            return List.of();
        }

        List<NodeTransition> neighbors = new ArrayList<>();
        ServerLevel level = context.level();
        EntityDimensions dimensions = context.entityDimensions();
        MovementPermission permission = request == null ? MovementPermission.DEFAULT_SURVIVAL : request.permission();
        BlockPos from = node.feet();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos horizontal = from.relative(direction);

            if (permission == null || permission.allowWalk()) {
                addWalkNeighbor(level, dimensions, node, horizontal, direction, neighbors);
                addDropDownNeighbor(level, dimensions, node, horizontal, direction, neighbors);
            }
            if (permission == null || permission.allowJump()) {
                addJumpUpNeighbor(level, dimensions, node, horizontal.above(), direction, neighbors);
            }
            if (permission != null && permission.allowDig()) {
                addDigStepNeighbor(level, dimensions, request, node, horizontal, direction, neighbors);
            }
            if (permission != null && permission.allowPlaceSupport()) {
                addPlaceSupportNeighbor(level, dimensions, request, node, horizontal, direction, neighbors);
            }
        }

        return neighbors;
    }

    private void addWalkNeighbor(ServerLevel level,
                                 EntityDimensions dimensions,
                                 PathNode fromNode,
                                 BlockPos targetFeet,
                                 Direction direction,
                                 List<NodeTransition> out) {
        if (!canStandAt(level, targetFeet, dimensions)) {
            return;
        }
        addTransition(fromNode, targetFeet, WaypointAction.WALK, direction, costModel.walkCost(), "walk", out);
    }

    private void addJumpUpNeighbor(ServerLevel level,
                                   EntityDimensions dimensions,
                                   PathNode fromNode,
                                   BlockPos targetFeet,
                                   Direction direction,
                                   List<NodeTransition> out) {
        if (!canStandAt(level, targetFeet, dimensions)) {
            return;
        }
        if (!hasBodyClearance(level, fromNode.feet().above(), dimensions)) {
            return;
        }
        if (!hasBodyClearance(level, targetFeet, dimensions)) {
            return;
        }
        addTransition(fromNode, targetFeet, WaypointAction.JUMP_UP, direction, costModel.jumpCost(), "jump_up", out);
    }

    private void addDropDownNeighbor(ServerLevel level,
                                     EntityDimensions dimensions,
                                     PathNode fromNode,
                                     BlockPos horizontal,
                                     Direction direction,
                                     List<NodeTransition> out) {
        if (!isPassable(level, horizontal) || !isPassable(level, horizontal.above())) {
            return;
        }
        for (int drop = 1; drop <= 3; drop++) {
            BlockPos targetFeet = horizontal.below(drop);
            if (!isPassable(level, targetFeet) || !isPassable(level, targetFeet.above())) {
                continue;
            }
            if (canStandAt(level, targetFeet, dimensions)) {
                addTransition(fromNode, targetFeet, WaypointAction.DROP_DOWN, direction,
                        costModel.walkCost() + drop * 4, "drop_down_" + drop, out);
                return;
            }
        }
    }

    private void addDigStepNeighbor(ServerLevel level,
                                    EntityDimensions dimensions,
                                    MobilityRequest request,
                                    PathNode fromNode,
                                    BlockPos targetFeet,
                                    Direction direction,
                                    List<NodeTransition> out) {
        if (!hasSolidSupport(level, targetFeet)) {
            return;
        }
        BlockPos feetBlock = targetFeet;
        BlockPos headBlock = targetFeet.above();
        BlockState feetState = level.getBlockState(feetBlock);
        BlockState headState = level.getBlockState(headBlock);
        boolean feetNeedsDig = !isPassable(level, feetBlock);
        boolean headNeedsDig = !isPassable(level, headBlock);
        if (!feetNeedsDig && !headNeedsDig) {
            return;
        }
        if ((feetNeedsDig && !canDigBlock(level, feetBlock, feetState, request))
                || (headNeedsDig && !canDigBlock(level, headBlock, headState, request))) {
            return;
        }

        BlockPos actionTarget = feetNeedsDig ? feetBlock : headBlock;
        int digCount = (feetNeedsDig ? 1 : 0) + (headNeedsDig ? 1 : 0);
        int cost = costModel.walkCost() + costModel.digCost() * digCount;
        addTransition(fromNode, targetFeet, WaypointAction.DIG_STEP, actionTarget, direction, cost,
                digCount == 1 ? "dig_step" : "dig_step_feet_head", null, out);
    }

    private void addPlaceSupportNeighbor(ServerLevel level,
                                         EntityDimensions dimensions,
                                         MobilityRequest request,
                                         PathNode fromNode,
                                         BlockPos targetFeet,
                                         Direction direction,
                                         List<NodeTransition> out) {
        BlockPos floor = targetFeet.below();
        if (!isPassable(level, floor) || !hasBodyClearance(level, targetFeet, dimensions)) {
            return;
        }
        BlockState belowFloor = level.getBlockState(floor.below());
        if (belowFloor.isAir() || belowFloor.canBeReplaced() || !belowFloor.getFluidState().isEmpty()) {
            return;
        }
        int cost = costModel.walkCost() + costModel.placeSupportCost();
        ResourceRequirement supportCost = new ResourceRequirement(ResourceType.SUPPORT_BLOCK, 1, "place_support");
        addTransition(fromNode, targetFeet, WaypointAction.PLACE_SUPPORT, floor, direction, cost,
                "place_support", supportCost, out);
    }

    private boolean canDigBlock(ServerLevel level, BlockPos pos, BlockState state, MobilityRequest request) {
        if (state == null || state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        if (request == null) {
            return true;
        }
        MovementPermission permission = request.permission();
        if (permission != null && !permission.allowUnsafeBreak() && protectionPolicy.isProtected(state, pos, request.protectionMode())) {
            return false;
        }
        return true;
    }

    private boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private void addTransition(PathNode fromNode,
                               BlockPos targetFeet,
                               WaypointAction action,
                               BlockPos actionTarget,
                               Direction direction,
                               int cost,
                               String reason,
                               ResourceRequirement resourceCost,
                               List<NodeTransition> out) {
        PathNode toNode = new PathNode(targetFeet.immutable(), fromNode.gCost() + cost, 0, 0);
        Waypoint waypoint = new Waypoint(targetFeet.immutable(), action,
                actionTarget == null ? null : actionTarget.immutable(), direction, reason, cost);
        out.add(new NodeTransition(fromNode, toNode, waypoint, cost, resourceCost, reason));
    }

    private void addTransition(PathNode fromNode,
                               BlockPos targetFeet,
                               WaypointAction action,
                               Direction direction,
                               int cost,
                               String reason,
                               List<NodeTransition> out) {
        addTransition(fromNode, targetFeet, action, null, direction, cost, reason, null, out);
    }
}