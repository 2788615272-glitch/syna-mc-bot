package com.syna.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client sync channel for blueprint ghosts.
 *
 * <p>Three packet types travel down this channel:
 * <ul>
 *   <li>{@link S2CBlueprintFull}   — replaces a single blueprint's state on the client</li>
 *   <li>{@link S2CBlueprintDelta}  — flips a single cell's done flag</li>
 *   <li>{@link S2CBlueprintRemove} — drops a blueprint from the client's view</li>
 * </ul>
 *
 * <p>Why "full + delta" instead of just full: most ticks only one cell
 * changes (place/break). Resending the entire cell list each time would
 * be wasteful for large blueprints (think 32×32×32 = 32k cells).
 *
 * <p>The change-listener hook into {@link BlueprintRegistry} runs
 * synchronously on whatever thread fires the event. We don't try to
 * detect "what kind of change" from the listener — instead we coalesce
 * all changes into a "dirty" flag and flush at the end of each server
 * tick via {@link #onServerTickEnd()}. This costs at most one full sync
 * per tick per blueprint, but the simplicity is worth it.
 */
public final class BlueprintNetwork {
    public static final String PROTOCOL_VERSION = "1";
    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(SynaBridgeMod.MOD_ID, "blueprint");

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID,
            () -> PROTOCOL_VERSION,
            s -> true,   // clientAcceptedVersions: allow vanilla / mineflayer clients (no mod)
            s -> true);  // serverAcceptedVersions: allow vanilla / mineflayer servers (no mod)

    private static volatile boolean dirty = false;
    /** Track the set of ids we've sent at least once — used to detect removals. */
    private static final java.util.Set<String> lastSentIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BlueprintNetwork() {}

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(S2CBlueprintFull.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CBlueprintFull::encode)
                .decoder(S2CBlueprintFull::decode)
                .consumerMainThread(S2CBlueprintFull::handle)
                .add();
        CHANNEL.messageBuilder(S2CBlueprintDelta.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CBlueprintDelta::encode)
                .decoder(S2CBlueprintDelta::decode)
                .consumerMainThread(S2CBlueprintDelta::handle)
                .add();
        CHANNEL.messageBuilder(S2CBlueprintRemove.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CBlueprintRemove::encode)
                .decoder(S2CBlueprintRemove::decode)
                .consumerMainThread(S2CBlueprintRemove::handle)
                .add();

        BlueprintRegistry.get().addChangeListener(BlueprintNetwork::markDirty);
        SynaBridgeMod.LOGGER.info("[BlueprintNetwork] channel registered ({} packet types)", id);
    }

    public static void markDirty() { dirty = true; }

    /** Called from {@link SynaBridgeMod#onServerTick}. */
    public static void onServerTickEnd() {
        if (!dirty) return;
        dirty = false;
        broadcastAll();
    }

    /** Broadcast every blueprint's full state + remove packets for any that vanished. */
    public static void broadcastAll() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        var blueprints = BlueprintRegistry.get().all();
        java.util.Set<String> currentIds = new java.util.HashSet<>();
        for (BlueprintRegistry.Blueprint bp : blueprints) {
            currentIds.add(bp.id);
            S2CBlueprintFull pkt = S2CBlueprintFull.fromBlueprint(bp);
            CHANNEL.send(PacketDistributor.ALL.noArg(), pkt);
        }
        // Anything we sent before but isn't here now → tell client to drop it
        for (String oldId : lastSentIds) {
            if (!currentIds.contains(oldId)) {
                CHANNEL.send(PacketDistributor.ALL.noArg(), new S2CBlueprintRemove(oldId));
            }
        }
        lastSentIds.clear();
        lastSentIds.addAll(currentIds);
    }

    /** Send all blueprints to one player (used on PlayerLoggedIn). */
    public static void sendAllTo(ServerPlayer player) {
        if (player == null) return;
        for (BlueprintRegistry.Blueprint bp : BlueprintRegistry.get().all()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), S2CBlueprintFull.fromBlueprint(bp));
        }
    }

    // =================================================================
    // Packet: full snapshot of one blueprint
    // =================================================================
    public static final class S2CBlueprintFull {
        public final String id;
        public final BlockPos origin;
        public final byte mode;       // 0=BUILD, 1=REMODEL, 2=LOCKED
        /** Block name dictionary, indexed by the per-packet ids used in cells. */
        public final List<String> palette;
        /** Per-cell payload: dx, dy, dz (relative to origin), paletteIndex, flags. */
        public final List<int[]> cells;
        /** flags bit 0 = done, bit 1 = isSurface */

        public S2CBlueprintFull(String id, BlockPos origin, byte mode,
                                List<String> palette, List<int[]> cells) {
            this.id = id;
            this.origin = origin;
            this.mode = mode;
            this.palette = palette;
            this.cells = cells;
        }

        public static S2CBlueprintFull fromBlueprint(BlueprintRegistry.Blueprint bp) {
            // Build per-packet palette from the cells encountered in iteration order.
            java.util.LinkedHashMap<String, Integer> palette = new java.util.LinkedHashMap<>();
            List<int[]> cells = new ArrayList<>(bp.cells.size());
            for (BlueprintRegistry.Cell c : bp.cells) {
                Integer idx = palette.get(c.blockName);
                if (idx == null) {
                    idx = palette.size();
                    palette.put(c.blockName, idx);
                }
                int dx = c.worldPos.getX() - bp.origin.getX();
                int dy = c.worldPos.getY() - bp.origin.getY();
                int dz = c.worldPos.getZ() - bp.origin.getZ();
                int flags = 0;
                if (Boolean.TRUE.equals(bp.done.get(c.worldPos))) flags |= 1;
                if (c.isSurface) flags |= 2;
                cells.add(new int[]{dx, dy, dz, idx, flags});
            }
            byte modeByte = switch (bp.mode) {
                case BUILD -> (byte) 0;
                case REMODEL -> (byte) 1;
                case LOCKED -> (byte) 2;
            };
            return new S2CBlueprintFull(bp.id, bp.origin, modeByte,
                    new ArrayList<>(palette.keySet()), cells);
        }

        public static void encode(S2CBlueprintFull pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.id, 64);
            buf.writeBlockPos(pkt.origin);
            buf.writeByte(pkt.mode);
            buf.writeVarInt(pkt.palette.size());
            for (String s : pkt.palette) buf.writeUtf(s, 64);
            buf.writeVarInt(pkt.cells.size());
            for (int[] c : pkt.cells) {
                // dx/dy/dz can be ±32k easily for distant blueprints, so VarInt > short
                buf.writeVarInt(c[0]);
                buf.writeVarInt(c[1]);
                buf.writeVarInt(c[2]);
                buf.writeVarInt(c[3]);
                buf.writeByte(c[4]);
            }
        }

        public static S2CBlueprintFull decode(FriendlyByteBuf buf) {
            String id = buf.readUtf(64);
            BlockPos origin = buf.readBlockPos();
            byte mode = buf.readByte();
            int paletteN = buf.readVarInt();
            List<String> palette = new ArrayList<>(paletteN);
            for (int i = 0; i < paletteN; i++) palette.add(buf.readUtf(64));
            int n = buf.readVarInt();
            List<int[]> cells = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int dx = buf.readVarInt();
                int dy = buf.readVarInt();
                int dz = buf.readVarInt();
                int pi = buf.readVarInt();
                int fl = buf.readByte() & 0xFF;
                cells.add(new int[]{dx, dy, dz, pi, fl});
            }
            return new S2CBlueprintFull(id, origin, mode, palette, cells);
        }

        public static void handle(S2CBlueprintFull pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            // Client-only dispatch — runs on the main thread thanks to consumerMainThread.
            net.minecraftforge.api.distmarker.Dist dist = net.minecraftforge.fml.loading.FMLEnvironment.dist;
            if (dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
                com.syna.bridge.client.BlueprintMirror.applyFull(pkt);
            }
            ctx.setPacketHandled(true);
        }
    }

    // =================================================================
    // Packet: single-cell delta
    // =================================================================
    public static final class S2CBlueprintDelta {
        public final String id;
        public final int dx, dy, dz;
        public final boolean done;

        public S2CBlueprintDelta(String id, int dx, int dy, int dz, boolean done) {
            this.id = id; this.dx = dx; this.dy = dy; this.dz = dz; this.done = done;
        }

        public static void encode(S2CBlueprintDelta pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.id, 64);
            buf.writeVarInt(pkt.dx);
            buf.writeVarInt(pkt.dy);
            buf.writeVarInt(pkt.dz);
            buf.writeBoolean(pkt.done);
        }

        public static S2CBlueprintDelta decode(FriendlyByteBuf buf) {
            return new S2CBlueprintDelta(buf.readUtf(64),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean());
        }

        public static void handle(S2CBlueprintDelta pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
                com.syna.bridge.client.BlueprintMirror.applyDelta(pkt);
            }
            ctx.setPacketHandled(true);
        }
    }

    // =================================================================
    // Packet: drop one blueprint
    // =================================================================
    public static final class S2CBlueprintRemove {
        public final String id;

        public S2CBlueprintRemove(String id) { this.id = id; }

        public static void encode(S2CBlueprintRemove pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.id, 64);
        }

        public static S2CBlueprintRemove decode(FriendlyByteBuf buf) {
            return new S2CBlueprintRemove(buf.readUtf(64));
        }

        public static void handle(S2CBlueprintRemove pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
                com.syna.bridge.client.BlueprintMirror.applyRemove(pkt.id);
            }
            ctx.setPacketHandled(true);
        }
    }
}
