package com.syna.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BridgeScanHandler implements HttpHandler {
    private static final int DEFAULT_RADIUS = 96;
    private static final int MAX_RADIUS = 160;
    private static final int DEFAULT_COUNT = 24;
    private static final int MAX_COUNT = 128;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeScanError(exchange, 405, "method_not_allowed", Map.of(), null);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            if (path.endsWith("/blocks")) {
                handleBlocks(exchange, query);
                return;
            }
            if (path.endsWith("/entities")) {
                handleEntities(exchange, query);
                return;
            }
            writeScanError(exchange, 404, "unknown_scan_route", query, null);
        } catch (Exception e) {
            SynaBridgeMod.LOGGER.warn("[BridgeScanHandler] error handling {}", path, e);
            writeScanError(exchange, 500, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), query, e);
        }
    }

    private void handleBlocks(HttpExchange exchange, Map<String, String> query) throws IOException {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            writeScanError(exchange, 503, "server_unavailable", query, null);
            return;
        }

        ServerLevel level = resolveLevel(server, query);
        BlockPos origin = resolveOrigin(server, level, query);
        if (origin == null) {
            writeScanError(exchange, 400, "missing_origin", query, null);
            return;
        }

        int radius = clamp(parseInt(query.get("radius")), DEFAULT_RADIUS, 1, MAX_RADIUS);
        int count = clamp(parseInt(query.get("count")), DEFAULT_COUNT, 1, MAX_COUNT);
        List<String> names = parseNames(query.get("name"), query.get("names"));
        if (names.isEmpty()) {
            writeScanError(exchange, 400, "missing_name", query, null);
            return;
        }

        int radiusSqr = radius * radius;
        int scanned = 0;
        int skippedUnloaded = 0;
        List<ScannedTarget> matches = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -radius; dy <= radius; dy++) {
            int y = origin.getY() + dy;
            if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distSqr = dx * dx + dy * dy + dz * dz;
                    if (distSqr > radiusSqr) continue;
                    pos.set(origin.getX() + dx, y, origin.getZ() + dz);
                    if (!level.hasChunkAt(pos)) {
                        skippedUnloaded++;
                        continue;
                    }
                    scanned++;
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    String fullName = key == null ? state.getBlock().toString() : key.toString();
                    String shortName = key == null ? fullName : key.getPath();
                    if (!matchesName(names, fullName, shortName)) continue;
                    matches.add(new ScannedTarget(pos.immutable(), fullName, distSqr));
                }
            }
        }
        matches.sort(Comparator.comparingInt(target -> target.distanceSqr));

        JsonObject root = baseResponse("blocks", level, origin, radius);
        root.addProperty("scanned", scanned);
        root.addProperty("skipped_unloaded", skippedUnloaded);
        root.add("query", namesToJson(names));
        root.add("matches", targetsToJson(matches, count));
        root.addProperty("matched", matches.size());
        root.addProperty("returned", Math.min(matches.size(), count));
        root.addProperty("truncated", matches.size() > count);
        writeJson(exchange, 200, root.toString());
    }

    private void handleEntities(HttpExchange exchange, Map<String, String> query) throws IOException {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            writeScanError(exchange, 503, "server_unavailable", query, null);
            return;
        }

        ServerLevel level = resolveLevel(server, query);
        BlockPos origin = resolveOrigin(server, level, query);
        if (origin == null) {
            writeScanError(exchange, 400, "missing_origin", query, null);
            return;
        }

        int radius = clamp(parseInt(query.get("radius")), DEFAULT_RADIUS, 1, MAX_RADIUS);
        int count = clamp(parseInt(query.get("count")), DEFAULT_COUNT, 1, MAX_COUNT);
        List<String> names = parseNames(query.get("name"), query.get("names"));
        double radiusSqr = radius * radius;
        AABB box = new AABB(origin).inflate(radius);
        List<ScannedTarget> matches = new ArrayList<>();
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, Entity::isAlive)) {
            double distSqr = entity.distanceToSqr(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D);
            if (distSqr > radiusSqr) continue;
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            String fullName = key == null ? entity.getType().toString() : key.toString();
            String shortName = key == null ? fullName : key.getPath();
            String display = entity.getName().getString();
            if (!names.isEmpty() && !matchesName(names, fullName, shortName) && !matchesName(names, display, display)) continue;
            matches.add(new ScannedTarget(entity.blockPosition(), fullName, (int) Math.round(distSqr), display));
        }
        matches.sort(Comparator.comparingInt(target -> target.distanceSqr));

        JsonObject root = baseResponse("entities", level, origin, radius);
        root.add("query", namesToJson(names));
        root.add("matches", targetsToJson(matches, count));
        root.addProperty("matched", matches.size());
        root.addProperty("returned", Math.min(matches.size(), count));
        root.addProperty("truncated", matches.size() > count);
        writeJson(exchange, 200, root.toString());
    }

    private ServerLevel resolveLevel(MinecraftServer server, Map<String, String> query) {
        ServerPlayer player = resolvePlayer(server, query.get("player"));
        if (player != null) return player.serverLevel();
        AliceEntity syna = SynaController.get().getSyna();
        if (syna != null && syna.level() instanceof ServerLevel synaLevel) return synaLevel;
        ServerPlayer bound = BridgeState.get().getBoundPlayer();
        if (bound != null) return bound.serverLevel();
        return server.overworld();
    }

    private BlockPos resolveOrigin(MinecraftServer server, ServerLevel level, Map<String, String> query) {
        Integer x = parseInt(query.get("x"));
        Integer y = parseInt(query.get("y"));
        Integer z = parseInt(query.get("z"));
        if (x != null && y != null && z != null) return new BlockPos(x, y, z);
        ServerPlayer player = resolvePlayer(server, query.get("player"));
        if (player != null) return player.blockPosition();
        AliceEntity syna = SynaController.get().getSyna();
        if (syna != null && syna.level() == level) return syna.blockPosition();
        ServerPlayer bound = BridgeState.get().getBoundPlayer();
        return bound == null ? null : bound.blockPosition();
    }

    private ServerPlayer resolvePlayer(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) return null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    private Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(urlDecode(pair), "");
            else out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return out;
    }

    private String urlDecode(String value) {
        if (value == null) return "";
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private List<String> parseNames(String name, String names) {
        String raw = (names != null && !names.isBlank()) ? names : name;
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String normalized = part.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("minecraft:")) normalized = normalized.substring("minecraft:".length());
            if (!normalized.isBlank()) out.add(normalized);
        }
        return out;
    }

    private boolean matchesName(List<String> names, String fullName, String shortName) {
        String full = (fullName == null ? "" : fullName).toLowerCase(Locale.ROOT);
        String shortN = (shortName == null ? "" : shortName).toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name.equals(full) || name.equals(shortN)) return true;
        }
        return false;
    }

    private JsonObject baseResponse(String kind, ServerLevel level, BlockPos origin, int radius) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("kind", kind);
        root.addProperty("dimension", level.dimension().location().toString());
        root.addProperty("radius", radius);
        JsonObject originJson = new JsonObject();
        originJson.addProperty("x", origin.getX());
        originJson.addProperty("y", origin.getY());
        originJson.addProperty("z", origin.getZ());
        root.add("origin", originJson);
        return root;
    }

    private JsonArray namesToJson(List<String> names) {
        JsonArray array = new JsonArray();
        for (String name : names) array.add(name);
        return array;
    }

    private JsonArray targetsToJson(List<ScannedTarget> targets, int count) {
        JsonArray array = new JsonArray();
        int limit = Math.min(targets.size(), count);
        for (int i = 0; i < limit; i++) {
            ScannedTarget target = targets.get(i);
            JsonObject object = new JsonObject();
            object.addProperty("name", target.name);
            if (target.displayName != null) object.addProperty("display", target.displayName);
            object.addProperty("x", target.pos.getX());
            object.addProperty("y", target.pos.getY());
            object.addProperty("z", target.pos.getZ());
            object.addProperty("distance", Math.sqrt(target.distanceSqr));
            array.add(object);
        }
        return array;
    }

    private int clamp(Integer value, int fallback, int min, int max) {
        int actual = value == null ? fallback : value;
        return Math.max(min, Math.min(max, actual));
    }

    private Integer parseInt(String value) {
        if (value == null) return null;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private void writeScanError(HttpExchange exchange, int code, String error, Map<String, String> query, Exception exception) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        root.addProperty("error", error == null ? "unknown_error" : error);
        writeScanLog(exchange, code, root, query, exception);
        writeJson(exchange, code, root.toString());
    }

    private void writeScanLog(HttpExchange exchange, int code, JsonObject response, Map<String, String> query, Exception exception) {
        try {
            JsonObject event = new JsonObject();
            event.addProperty("time", Instant.now().toString());
            event.addProperty("status", code);
            event.addProperty("method", exchange.getRequestMethod());
            event.addProperty("path", exchange.getRequestURI().getPath());
            event.addProperty("rawQuery", exchange.getRequestURI().getRawQuery());
            event.add("query", mapToJson(query));
            event.add("response", response);
            if (exception != null) {
                event.addProperty("exception", exception.getClass().getName());
                StringWriter writer = new StringWriter();
                exception.printStackTrace(new PrintWriter(writer));
                event.addProperty("stack", writer.toString());
            }
            Path dir = Path.of("synabridge-logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("scan_errors.jsonl"), event + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception logError) {
            SynaBridgeMod.LOGGER.warn("[BridgeScanHandler] failed to write scan error log", logError);
        }
    }

    private JsonObject mapToJson(Map<String, String> map) {
        JsonObject object = new JsonObject();
        if (map == null) {
            return object;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object;
    }
    private void writeJson(HttpExchange exchange, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static class ScannedTarget {
        final BlockPos pos;
        final String name;
        final int distanceSqr;
        final String displayName;

        ScannedTarget(BlockPos pos, String name, int distanceSqr) {
            this(pos, name, distanceSqr, null);
        }

        ScannedTarget(BlockPos pos, String name, int distanceSqr, String displayName) {
            this.pos = pos;
            this.name = name;
            this.distanceSqr = distanceSqr;
            this.displayName = displayName;
        }
    }
}
