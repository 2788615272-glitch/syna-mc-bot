package com.syna.bridge.mobility.path;

import com.syna.bridge.mobility.MobilityRequest;
import com.syna.bridge.mobility.ResourceRequirement;
import com.syna.bridge.mobility.ResourceType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class AStarPathFinder implements PathFinder {
    private final MovementRules movementRules = new MovementRules();

    @Override
    public PathFindingResult findPath(PathFindingContext context, MobilityRequest request) {
        if (context == null || request == null || request.target() == null) {
            return PathFindingResult.noPath("invalid_request", null, 0);
        }
        BlockPos goal = request.target().resolveGoal(context.level(), null);
        if (goal == null) {
            return PathFindingResult.noPath("no_goal", null, 0);
        }
        BlockPos start = context.startFeet();
        if (start == null) {
            return PathFindingResult.noPath("no_start", null, 0);
        }
        if (!movementRules.canStandAt(context.level(), start, context.entityDimensions())) {
            return PathFindingResult.noPath("start_not_standable", start, 0);
        }
        if (!movementRules.canStandAt(context.level(), goal, context.entityDimensions())
                && !canPotentiallyPrepareGoal(context, request, goal)) {
            return PathFindingResult.noPath("goal_not_standable", goal, 0);
        }

        int maxNodes = request.maxSearchNodes() <= 0 ? 6000 : request.maxSearchNodes();
        PriorityQueue<SearchNode> openSet = new PriorityQueue<>(Comparator
                .comparingInt(SearchNode::fCost)
                .thenComparingInt(SearchNode::hCost));
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, Integer> bestGCost = new HashMap<>();
        Map<BlockPos, NodeTransition> cameFrom = new HashMap<>();

        int startH = heuristic(start, goal);
        openSet.add(new SearchNode(start.immutable(), 0, startH));
        bestGCost.put(start.immutable(), 0);

        BlockPos nearest = start.immutable();
        int nearestH = startH;
        int expanded = 0;

        while (!openSet.isEmpty() && expanded < maxNodes) {
            SearchNode current = openSet.poll();
            Integer bestKnownG = bestGCost.get(current.pos());
            if (bestKnownG == null || current.gCost() != bestKnownG) {
                continue;
            }
            if (!closedSet.add(current.pos())) {
                continue;
            }
            expanded++;

            int currentH = heuristic(current.pos(), goal);
            if (currentH < nearestH) {
                nearestH = currentH;
                nearest = current.pos();
            }

            if (current.pos().equals(goal)) {
                PathPlan plan = buildPlan(start, goal, cameFrom, current.gCost(), expanded);
                ResourceRequirement missing = missingSupportRequirement(plan, context);
                if (missing != null) {
                    return PathFindingResult.needResource(missing, current.pos(), expanded);
                }
                return PathFindingResult.success(plan, expanded);
            }

            PathNode pathNode = new PathNode(current.pos(), current.gCost(), current.hCost(), 0);
            for (NodeTransition transition : movementRules.getNeighbors(context, request, pathNode)) {
                BlockPos next = transition.to().feet().immutable();
                if (closedSet.contains(next)) {
                    continue;
                }
                int tentativeG = current.gCost() + transition.cost();
                int previousBest = bestGCost.getOrDefault(next, Integer.MAX_VALUE);
                if (tentativeG >= previousBest) {
                    continue;
                }
                bestGCost.put(next, tentativeG);
                int h = heuristic(next, goal);
                cameFrom.put(next, transition);
                openSet.add(new SearchNode(next, tentativeG, h));
            }
        }

        String reason = expanded >= maxNodes ? "timeout_max_nodes" : "no_path";
        return PathFindingResult.noPath(reason, nearest, expanded);
    }

    private int heuristic(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return (dx + dy + dz) * 10;
    }

    private boolean canPotentiallyPrepareGoal(PathFindingContext context, MobilityRequest request, BlockPos goal) {
        if (request == null || request.permission() == null) {
            return false;
        }
        if (request.permission().allowPlaceSupport()
                && movementRules.hasBodyClearance(context.level(), goal, context.entityDimensions())) {
            BlockPos floor = goal.below();
            if (context.level().getBlockState(floor).canBeReplaced()
                    && movementRules.hasSolidSupport(context.level(), floor)) {
                return true;
            }
        }
        return request.permission().allowDig() && movementRules.hasSolidSupport(context.level(), goal);
    }

    private ResourceRequirement missingSupportRequirement(PathPlan plan, PathFindingContext context) {
        if (plan == null || plan.requiredResources().isEmpty()) {
            return null;
        }
        int requiredSupport = 0;
        for (ResourceRequirement requirement : plan.requiredResources()) {
            if (requirement != null && requirement.type() == ResourceType.SUPPORT_BLOCK) {
                requiredSupport += Math.max(0, requirement.count());
            }
        }
        int missing = requiredSupport - Math.max(0, context.supportBlockCount());
        if (missing <= 0) {
            return null;
        }
        return new ResourceRequirement(ResourceType.SUPPORT_BLOCK, missing, "astar_place_support");
    }

    private PathPlan buildPlan(BlockPos start,
                               BlockPos goal,
                               Map<BlockPos, NodeTransition> cameFrom,
                               int totalCost,
                               int expandedNodes) {
        List<Waypoint> waypoints = new ArrayList<>();
        int requiredSupportBlocks = 0;
        BlockPos cursor = goal.immutable();
        while (!cursor.equals(start)) {
            NodeTransition transition = cameFrom.get(cursor);
            if (transition == null) {
                break;
            }
            waypoints.add(transition.waypoint());
            if (transition.resourceCost() != null && transition.resourceCost().type() == ResourceType.SUPPORT_BLOCK) {
                requiredSupportBlocks += Math.max(0, transition.resourceCost().count());
            }
            cursor = transition.from().feet().immutable();
        }
        Collections.reverse(waypoints);
        List<ResourceRequirement> requiredResources = requiredSupportBlocks <= 0
                ? List.of()
                : List.of(new ResourceRequirement(ResourceType.SUPPORT_BLOCK, requiredSupportBlocks, "astar_path_supports"));
        return new PathPlan(waypoints, totalCost, Math.max(1, waypoints.size() * 10), requiredResources,
                "astar_basic nodes=" + expandedNodes + " waypoints=" + waypoints.size());
    }

    private record SearchNode(BlockPos pos, int gCost, int hCost) {
        int fCost() {
            return gCost + hCost;
        }
    }
}