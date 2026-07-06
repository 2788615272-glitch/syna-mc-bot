package com.syna.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry of "ghost blueprints".
 *
 * Each blueprint is a flat list of (relative block, expected block name)
 * cells anchored at a world origin. The JS side does the palette/layers
 * → flat list translation; the mod just holds the result and answers
 * three questions:
 *
 *   1. "Bot, where should I place the next block?"   (getNextMissing)
 *   2. "Server, is this position protected?"          (getProtectedPositions)
 *   3. "Players, what does the plan look like?"       (full snapshot for client renderer)
 *
 * Modes:
 *   BUILD    — actively being built. Bot is allowed to place; players & bots
 *              cannot break completed cells.
 *   REMODEL  — the LLM is editing the plan. Protection is suspended so the
 *              bot can break old blocks freely.
 *   LOCKED   — finished; no one (bot or player) can break completed cells.
 *
 * Bottom-up + nearest neighbor ordering: the bot asks for the next missing
 * cell starting from its current position. We sort by Y first (so the
 * floor is laid before the walls), then by 2D distance within the same
 * Y layer. This avoids both "place wall in mid-air" and "ping-pong across
 * the layer" failure modes.
 *
 * Persistence: not yet wired (planned for SavedData in phase 2). The
 * registry currently lives only in memory until the JVM exits.
 */
public final class BlueprintRegistry {
    private static final BlueprintRegistry INSTANCE = new BlueprintRegistry();

    public static BlueprintRegistry get() {
        return INSTANCE;
    }

    public enum Mode {
        BUILD,
        REMODEL,
        LOCKED
    }

    public static final class Cell {
        public final BlockPos worldPos;
        public final String blockName;
        /**
         * True if this cell sits on the outer shell of the blueprint volume
         * (i.e. at least one of the 6 axis-aligned neighbors is NOT also a
         * cell in the same blueprint). Used by the client renderer to skip
         * interior cells when the blueprint is huge or far away — interior
         * cells will be hidden by their neighbors anyway.
         *
         * Computed once at upload time, never mutated.
         */
        public final boolean isSurface;

        public Cell(BlockPos worldPos, String blockName, boolean isSurface) {
            this.worldPos = worldPos;
            this.blockName = blockName;
            this.isSurface = isSurface;
        }
    }

    public static final class Blueprint {
        public final String id;
        public final BlockPos origin;
        public final List<Cell> cells;
        public final Map<BlockPos, String> cellByPos;
        /** Mutable: which cells the world currently matches the plan at. */
        public final Map<BlockPos, Boolean> done = new ConcurrentHashMap<>();
        public volatile Mode mode = Mode.BUILD;
        public volatile boolean autoClear = false;

        Blueprint(String id, BlockPos origin, List<Cell> cells) {
            this.id = id;
            this.origin = origin;
            this.cells = Collections.unmodifiableList(cells);
            Map<BlockPos, String> idx = new HashMap<>(cells.size() * 2);
            for (Cell c : cells) idx.put(c.worldPos, c.blockName);
            this.cellByPos = Collections.unmodifiableMap(idx);
        }
    }

    private final Map<String, Blueprint> blueprints = new ConcurrentHashMap<>();
    /**
     * Listeners (e.g. BlueprintNetwork) get notified on any registry change so
     * they can broadcast incremental sync packets to clients. Decoupled to
     * avoid a hard dependency on the network layer (which doesn't exist yet
     * in phase 1).
     */
    private final List<Runnable> changeListeners = new ArrayList<>();

    public synchronized void addChangeListener(Runnable r) {
        if (r != null) changeListeners.add(r);
    }

    private void fireChange() {
        for (Runnable r : changeListeners) {
            try { r.run(); } catch (Throwable t) {
                SynaBridgeMod.LOGGER.warn("[Blueprint] change listener failed", t);
            }
        }
    }

    /**
     * Upload a flat blueprint. {@code blocks} is a list of (relative dx/dy/dz,
     * blockName) triples. air-like names are filtered out. Existing blueprint
     * with the same id is replaced.
     *
     * @return the number of non-air cells stored
     */
    public int upload(String id,
                      int ox, int oy, int oz,
                      List<int[]> relCoords,
                      List<String> blockNames,
                      Mode mode,
                      boolean autoClear) {
        if (id == null || id.isBlank() || relCoords == null || blockNames == null) return 0;
        if (relCoords.size() != blockNames.size()) {
            SynaBridgeMod.LOGGER.warn("[Blueprint] upload size mismatch coords={} names={}",
                    relCoords.size(), blockNames.size());
            return 0;
        }

        // First pass: collect (pos, name) pairs so we can compute the
        // "is surface" flag in a second pass without paying O(n²).
        List<BlockPos> tempPositions = new ArrayList<>(relCoords.size());
        List<String> tempNames = new ArrayList<>(relCoords.size());
        Set<BlockPos> posSet = new HashSet<>(relCoords.size() * 2);
        for (int i = 0; i < relCoords.size(); i++) {
            int[] r = relCoords.get(i);
            String name = blockNames.get(i);
            if (r == null || r.length < 3 || name == null || name.isBlank()) continue;
            String n = name.toLowerCase();
            if (n.equals("air") || n.equals("cave_air") || n.equals("void_air")) continue;
            BlockPos p = new BlockPos(ox + r[0], oy + r[1], oz + r[2]);
            if (posSet.add(p)) {
                tempPositions.add(p);
                tempNames.add(n);
            }
        }

        // Second pass: a cell is "surface" iff at least one of its 6
        // axis-aligned neighbors is missing from posSet. Cheap O(n).
        List<Cell> cells = new ArrayList<>(tempPositions.size());
        for (int i = 0; i < tempPositions.size(); i++) {
            BlockPos p = tempPositions.get(i);
            boolean surface =
                    !posSet.contains(p.above())
                 || !posSet.contains(p.below())
                 || !posSet.contains(p.north())
                 || !posSet.contains(p.south())
                 || !posSet.contains(p.east())
                 || !posSet.contains(p.west());
            cells.add(new Cell(p, tempNames.get(i), surface));
        }

        // Stable ordering for snapshots; bot's "next missing" lookup has its
        // own dynamic sort (see getNextMissing).
        cells.sort((a, b) -> {
            int c = Integer.compare(a.worldPos.getY(), b.worldPos.getY());
            if (c != 0) return c;
            c = Integer.compare(a.worldPos.getZ(), b.worldPos.getZ());
            if (c != 0) return c;
            return Integer.compare(a.worldPos.getX(), b.worldPos.getX());
        });

        Blueprint bp = new Blueprint(id, new BlockPos(ox, oy, oz), cells);
        bp.mode = mode == null ? Mode.BUILD : mode;
        bp.autoClear = autoClear;
        blueprints.put(id, bp);
        fireChange();
        SynaBridgeMod.LOGGER.info("[Blueprint] uploaded id={} cells={} origin=({},{},{}) mode={}",
                id, cells.size(), ox, oy, oz, bp.mode);
        return cells.size();
    }

    public boolean remove(String id) {
        boolean removed = blueprints.remove(id) != null;
        if (removed) fireChange();
        return removed;
    }

    public Blueprint get(String id) {
        return blueprints.get(id);
    }

    public Collection<Blueprint> all() {
        return blueprints.values();
    }

    public boolean setMode(String id, Mode mode) {
        Blueprint bp = blueprints.get(id);
        if (bp == null || mode == null) return false;
        bp.mode = mode;
        fireChange();
        return true;
    }

    /**
     * The set of world positions any blueprint cares about. Used by
     * BlueprintProtection's BreakEvent handler.
     */
    public Set<BlockPos> getProtectedPositions() {
        Set<BlockPos> out = new HashSet<>();
        for (Blueprint bp : blueprints.values()) {
            if (bp.mode == Mode.REMODEL) continue; // protection suspended
            out.addAll(bp.cellByPos.keySet());
        }
        return out;
    }

    /**
     * Find which blueprint owns a given position, if any.
     */
    public Blueprint findOwning(BlockPos pos) {
        for (Blueprint bp : blueprints.values()) {
            if (bp.cellByPos.containsKey(pos)) return bp;
        }
        return null;
    }

    /**
     * The bot's main query: which cell should I place next?
     *
     * Strategy: lowest Y first (foundations before walls before roof);
     * within the same Y, the cell closest to {@code from} wins — this
     * trades a tiny bit of "perfect" foundation order for far less
     * pathfinding ping-pong.
     *
     * Skips cells whose world state already matches the plan (auto
     * mark-done as a side effect).
     *
     * If {@code id} is null, search across all BUILD-mode blueprints.
     *
     * @return null if everything is built, or the next target cell
     */
    public Cell getNextMissing(String id, BlockPos from, Level level) {
        if (id != null) {
            Blueprint bp = blueprints.get(id);
            return bp == null ? null : nextMissingIn(bp, from, level);
        }
        Cell best = null;
        int bestY = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Blueprint bp : blueprints.values()) {
            if (bp.mode != Mode.BUILD) continue;
            Cell c = nextMissingIn(bp, from, level);
            if (c == null) continue;
            int y = c.worldPos.getY();
            double d = from == null ? 0 : c.worldPos.distSqr(from);
            if (y < bestY || (y == bestY && d < bestDist)) {
                best = c; bestY = y; bestDist = d;
            }
        }
        return best;
    }

    private Cell nextMissingIn(Blueprint bp, BlockPos from, Level level) {
        if (bp.mode != Mode.BUILD) return null;

        int lowestPendingY = Integer.MAX_VALUE;
        Cell pickInLayer = null;
        double pickDist = Double.MAX_VALUE;

        for (Cell c : bp.cells) {
            if (Boolean.TRUE.equals(bp.done.get(c.worldPos))) continue;
            // Auto-verify: if the world already matches, mark done and skip
            if (level != null) {
                BlockState st = level.getBlockState(c.worldPos);
                var key = ForgeRegistries.BLOCKS.getKey(st.getBlock());
                if (key != null && key.getPath().equals(c.blockName)) {
                    bp.done.put(c.worldPos, Boolean.TRUE);
                    continue;
                }
            }
            int y = c.worldPos.getY();
            if (y > lowestPendingY) continue; // strictly bottom-up across layers
            if (y < lowestPendingY) {
                lowestPendingY = y;
                pickInLayer = c;
                pickDist = from == null ? 0 : c.worldPos.distSqr(from);
                continue;
            }
            // y == lowestPendingY → pick the one closer to bot
            double d = from == null ? 0 : c.worldPos.distSqr(from);
            if (d < pickDist) {
                pickInLayer = c;
                pickDist = d;
            }
        }
        return pickInLayer;
    }

    /**
     * Mark a position as done if it matches the expected block. Returns the
     * expected block name if this position belongs to any blueprint (used by
     * EntityPlaceEvent to know whether to validate), else null.
     */
    public String markPlaced(BlockPos pos, String placedBlock) {
        for (Blueprint bp : blueprints.values()) {
            String expected = bp.cellByPos.get(pos);
            if (expected != null) {
                if (expected.equals(placedBlock)) {
                    bp.done.put(pos, Boolean.TRUE);
                    fireChange();
                }
                return expected;
            }
        }
        return null;
    }

    /**
     * When the world's block at a known cell changes away from the plan
     * (e.g. allowed REMODEL break), un-mark the cell as done so the bot
     * will rebuild it.
     */
    public void markBroken(BlockPos pos) {
        for (Blueprint bp : blueprints.values()) {
            if (bp.cellByPos.containsKey(pos)) {
                bp.done.remove(pos);
                fireChange();
                return;
            }
        }
    }

    /**
     * Force-skip a position regardless of block name match.
     * Used by the /skip endpoint so the bot can skip unplaceable blocks
     * (blacklisted, non-existent in game, etc.) without getting stuck.
     *
     * @return true if the position belonged to a blueprint and was marked done
     */
    public boolean forceSkip(BlockPos pos) {
        for (Blueprint bp : blueprints.values()) {
            if (bp.cellByPos.containsKey(pos)) {
                bp.done.put(pos, Boolean.TRUE);
                fireChange();
                SynaBridgeMod.LOGGER.info("[Blueprint] forceSkip pos=({},{},{}) in blueprint={}",
                        pos.getX(), pos.getY(), pos.getZ(), bp.id);
                return true;
            }
        }
        return false;
    }

    public int doneCount(Blueprint bp) {
        int n = 0;
        for (Cell c : bp.cells) if (Boolean.TRUE.equals(bp.done.get(c.worldPos))) n++;
        return n;
    }
}
