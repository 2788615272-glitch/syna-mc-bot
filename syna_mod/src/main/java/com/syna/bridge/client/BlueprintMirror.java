package com.syna.bridge.client;

import com.syna.bridge.BlueprintNetwork;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the server's blueprint registry.
 *
 * Receives {@link BlueprintNetwork} packets and exposes a snapshot
 * suitable for the renderer. Only loaded on the physical client; the
 * server side never references this class so it's safe to live under
 * the {@code .client} sub-package.
 *
 * Concurrency note: packets are dispatched on the client main thread
 * (via {@code consumerMainThread}), so all mutations happen there.
 * The renderer also runs on the main thread (RenderLevelStageEvent),
 * so we don't need explicit synchronization. The {@code ConcurrentHashMap}
 * is just defensive in case of future refactors.
 */
@OnlyIn(Dist.CLIENT)
public final class BlueprintMirror {

    public static final class ClientCell {
        public final BlockPos worldPos;
        public final String blockName;
        public final boolean isSurface;
        public boolean done;

        ClientCell(BlockPos worldPos, String blockName, boolean isSurface, boolean done) {
            this.worldPos = worldPos;
            this.blockName = blockName;
            this.isSurface = isSurface;
            this.done = done;
        }
    }

    public static final class ClientBlueprint {
        public final String id;
        public final BlockPos origin;
        public final byte mode;
        public final Map<BlockPos, ClientCell> cells;

        ClientBlueprint(String id, BlockPos origin, byte mode, Map<BlockPos, ClientCell> cells) {
            this.id = id;
            this.origin = origin;
            this.mode = mode;
            this.cells = cells;
        }
    }

    private static final Map<String, ClientBlueprint> BLUEPRINTS = new ConcurrentHashMap<>();
    /** Per-player visibility toggle. Defaults true; B key flips it. */
    private static volatile boolean visible = true;

    private BlueprintMirror() {}

    public static boolean isVisible() { return visible; }

    public static void setVisible(boolean v) { visible = v; }

    public static void toggleVisible() { visible = !visible; }

    public static List<ClientBlueprint> snapshot() {
        return new ArrayList<>(BLUEPRINTS.values());
    }

    public static void applyFull(BlueprintNetwork.S2CBlueprintFull pkt) {
        Map<BlockPos, ClientCell> map = new HashMap<>(pkt.cells.size() * 2);
        for (int[] arr : pkt.cells) {
            int dx = arr[0], dy = arr[1], dz = arr[2];
            int paletteIdx = arr[3];
            int flags = arr[4];
            if (paletteIdx < 0 || paletteIdx >= pkt.palette.size()) continue;
            BlockPos world = new BlockPos(
                    pkt.origin.getX() + dx,
                    pkt.origin.getY() + dy,
                    pkt.origin.getZ() + dz);
            boolean done = (flags & 1) != 0;
            boolean surface = (flags & 2) != 0;
            map.put(world, new ClientCell(world, pkt.palette.get(paletteIdx), surface, done));
        }
        BLUEPRINTS.put(pkt.id, new ClientBlueprint(pkt.id, pkt.origin, pkt.mode, map));
    }

    public static void applyDelta(BlueprintNetwork.S2CBlueprintDelta pkt) {
        ClientBlueprint bp = BLUEPRINTS.get(pkt.id);
        if (bp == null) return;
        BlockPos world = new BlockPos(
                bp.origin.getX() + pkt.dx,
                bp.origin.getY() + pkt.dy,
                bp.origin.getZ() + pkt.dz);
        ClientCell cell = bp.cells.get(world);
        if (cell != null) cell.done = pkt.done;
    }

    public static void applyRemove(String id) {
        BLUEPRINTS.remove(id);
    }
}
