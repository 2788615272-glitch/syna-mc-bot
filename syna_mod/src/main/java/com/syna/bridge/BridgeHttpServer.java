package com.syna.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public final class BridgeHttpServer {
    private HttpServer server;

    public void start() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
            server.createContext("/health", new JsonHandler(exchange -> "{\"ok\":true,\"service\":\"synabridge\"}"));
            server.createContext("/state", new JsonHandler(exchange -> BridgeState.get().toJson()));
            server.createContext("/command", new CommandHandler());
            server.createContext("/blueprint", new BlueprintHandler());
            server.createContext("/scan", new BridgeScanHandler());
            server.createContext("/chunk_reload", new ChunkReloadHandler());
            server.createContext("/voice/broadcast", new VoiceBroadcastHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            SynaBridgeMod.LOGGER.info("SynaBridge HTTP server started on 127.0.0.1:8765");
        } catch (IOException e) {
            SynaBridgeMod.LOGGER.error("Failed to start SynaBridge HTTP server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private interface BodySupplier {
        String get(HttpExchange exchange) throws IOException;
    }

    private static class JsonHandler implements HttpHandler {
        private final BodySupplier supplier;

        private JsonHandler(BodySupplier supplier) {
            this.supplier = supplier;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, supplier.get(exchange));
        }
    }

    private static class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }

            String raw = readBody(exchange.getRequestBody());
            String type = jsonValue(raw, "type");
            String text = jsonValue(raw, "text");
            String item = jsonValue(raw, "item");
            String player = jsonValue(raw, "player");
            String reason = jsonValue(raw, "reason");
            String owner = jsonValue(raw, "owner");
            Double x = jsonNumber(raw, "x");
            Double y = jsonNumber(raw, "y");
            Double z = jsonNumber(raw, "z");
            Integer count = jsonInteger(raw, "count");
            Integer seconds = jsonInteger(raw, "seconds");

            var mcServer = ServerLifecycleHooks.getCurrentServer();
            if (mcServer == null) {
                writeJson(exchange, 503, "{\"ok\":false,\"error\":\"server_unavailable\"}");
                return;
            }

            BridgeCommandQueue.get().offer(new BridgeCommand(type, text, item, player, reason, owner, x, y, z, count, seconds));
            writeJson(exchange, 200, "{\"ok\":true,\"accepted\":true}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Blueprint REST API
    //
    //   POST   /blueprint/upload          {id, ox,oy,oz, blocks:[{dx,dy,dz,name}], mode?, auto_clear?}
    //   GET    /blueprint/list
    //   GET    /blueprint/{id}/status
    //   GET    /blueprint/{id}/next?fx=&fy=&fz=
    //   POST   /blueprint/{id}/mode       {mode: "build"|"remodel"|"locked"}
    //   DELETE /blueprint/{id}
    //
    // Routing is path-based so multiple ids are possible without the LLM
    // having to URL-encode anything fancy.
    // ═══════════════════════════════════════════════════════════════════════

    private static class BlueprintHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            String path = uri.getPath(); // "/blueprint/..."
            String method = exchange.getRequestMethod();

            String[] parts = path.split("/");
            // parts[0]="" parts[1]="blueprint" parts[2]=action_or_id parts[3]=sub
            try {
                if (parts.length >= 3 && "list".equalsIgnoreCase(parts[2]) && "GET".equalsIgnoreCase(method)) {
                    handleList(exchange);
                    return;
                }
                if (parts.length >= 3 && "upload".equalsIgnoreCase(parts[2]) && "POST".equalsIgnoreCase(method)) {
                    handleUpload(exchange);
                    return;
                }
                if (parts.length >= 4) {
                    String id = parts[2];
                    String sub = parts[3];
                    if ("status".equalsIgnoreCase(sub) && "GET".equalsIgnoreCase(method)) {
                        handleStatus(exchange, id);
                        return;
                    }
                    if ("next".equalsIgnoreCase(sub) && "GET".equalsIgnoreCase(method)) {
                        handleNext(exchange, id);
                        return;
                    }
                    if ("mode".equalsIgnoreCase(sub) && "POST".equalsIgnoreCase(method)) {
                        handleSetMode(exchange, id);
                        return;
                    }
                    if ("skip".equalsIgnoreCase(sub) && "POST".equalsIgnoreCase(method)) {
                        handleSkip(exchange, id);
                        return;
                    }
                }
                if (parts.length >= 3 && "DELETE".equalsIgnoreCase(method)) {
                    String id = parts[2];
                    boolean removed = BlueprintRegistry.get().remove(id);
                    writeJson(exchange, 200, "{\"ok\":true,\"removed\":" + removed + "}");
                    return;
                }

                writeJson(exchange, 404, "{\"ok\":false,\"error\":\"unknown_route\"}");
            } catch (Exception e) {
                SynaBridgeMod.LOGGER.warn("[BlueprintHandler] error handling {} {}", method, path, e);
                writeJson(exchange, 500, "{\"ok\":false,\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }

        private void handleList(HttpExchange exchange) throws IOException {
            JsonArray arr = new JsonArray();
            for (BlueprintRegistry.Blueprint bp : BlueprintRegistry.get().all()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", bp.id);
                o.addProperty("origin_x", bp.origin.getX());
                o.addProperty("origin_y", bp.origin.getY());
                o.addProperty("origin_z", bp.origin.getZ());
                o.addProperty("cells", bp.cells.size());
                o.addProperty("done", BlueprintRegistry.get().doneCount(bp));
                o.addProperty("mode", bp.mode.name().toLowerCase(Locale.ROOT));
                o.addProperty("auto_clear", bp.autoClear);
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.add("blueprints", arr);
            writeJson(exchange, 200, root.toString());
        }

        private void handleUpload(HttpExchange exchange) throws IOException {
            String body = readBody(exchange.getRequestBody());
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"body_not_json_object\"}");
                return;
            }
            JsonObject obj = el.getAsJsonObject();
            String id = obj.has("id") ? obj.get("id").getAsString() : null;
            if (id == null || id.isBlank()) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"missing_id\"}");
                return;
            }
            int ox = obj.has("ox") ? obj.get("ox").getAsInt() : 0;
            int oy = obj.has("oy") ? obj.get("oy").getAsInt() : 0;
            int oz = obj.has("oz") ? obj.get("oz").getAsInt() : 0;
            boolean autoClear = obj.has("auto_clear") && obj.get("auto_clear").getAsBoolean();

            BlueprintRegistry.Mode mode = BlueprintRegistry.Mode.BUILD;
            if (obj.has("mode")) {
                try { mode = BlueprintRegistry.Mode.valueOf(obj.get("mode").getAsString().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) {}
            }

            JsonArray blocks = obj.has("blocks") && obj.get("blocks").isJsonArray()
                    ? obj.getAsJsonArray("blocks") : null;
            if (blocks == null) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"missing_blocks_array\"}");
                return;
            }

            List<int[]> coords = new ArrayList<>(blocks.size());
            List<String> names = new ArrayList<>(blocks.size());
            for (JsonElement b : blocks) {
                if (!b.isJsonObject()) continue;
                JsonObject c = b.getAsJsonObject();
                if (!c.has("dx") || !c.has("dy") || !c.has("dz") || !c.has("name")) continue;
                coords.add(new int[]{
                        c.get("dx").getAsInt(),
                        c.get("dy").getAsInt(),
                        c.get("dz").getAsInt()
                });
                names.add(c.get("name").getAsString());
            }

            int stored = BlueprintRegistry.get().upload(id, ox, oy, oz, coords, names, mode, autoClear);
            writeJson(exchange, 200,
                    "{\"ok\":true,\"id\":\"" + escape(id) + "\",\"cells\":" + stored + "}");
        }

        private void handleStatus(HttpExchange exchange, String id) throws IOException {
            BlueprintRegistry.Blueprint bp = BlueprintRegistry.get().get(id);
            if (bp == null) {
                writeJson(exchange, 404, "{\"ok\":false,\"error\":\"not_found\"}");
                return;
            }
            int total = bp.cells.size();
            int done = BlueprintRegistry.get().doneCount(bp);
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("id", bp.id);
            root.addProperty("origin_x", bp.origin.getX());
            root.addProperty("origin_y", bp.origin.getY());
            root.addProperty("origin_z", bp.origin.getZ());
            root.addProperty("total", total);
            root.addProperty("done", done);
            root.addProperty("remaining", total - done);
            root.addProperty("mode", bp.mode.name().toLowerCase(Locale.ROOT));
            root.addProperty("auto_clear", bp.autoClear);
            writeJson(exchange, 200, root.toString());
        }

        private void handleNext(HttpExchange exchange, String id) throws IOException {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            Integer fx = parseInt(q.get("fx"));
            Integer fy = parseInt(q.get("fy"));
            Integer fz = parseInt(q.get("fz"));
            BlockPos from = (fx != null && fy != null && fz != null)
                    ? new BlockPos(fx, fy, fz) : null;

            var mcServer = ServerLifecycleHooks.getCurrentServer();
            var level = mcServer == null ? null : mcServer.overworld();

            // id can be empty/"any" to scan across all
            String useId = (id == null || id.isBlank() || id.equalsIgnoreCase("any")) ? null : id;
            BlueprintRegistry.Cell c = BlueprintRegistry.get().getNextMissing(useId, from, level);

            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            if (c == null) {
                root.addProperty("done", true);
            } else {
                root.addProperty("done", false);
                root.addProperty("x", c.worldPos.getX());
                root.addProperty("y", c.worldPos.getY());
                root.addProperty("z", c.worldPos.getZ());
                root.addProperty("name", c.blockName);
                BlueprintRegistry.Blueprint owner = BlueprintRegistry.get().findOwning(c.worldPos);
                if (owner != null) root.addProperty("blueprint_id", owner.id);
            }
            writeJson(exchange, 200, root.toString());
        }

        private void handleSetMode(HttpExchange exchange, String id) throws IOException {
            String body = readBody(exchange.getRequestBody());
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject() || !el.getAsJsonObject().has("mode")) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"missing_mode\"}");
                return;
            }
            String modeStr = el.getAsJsonObject().get("mode").getAsString();
            BlueprintRegistry.Mode mode;
            try { mode = BlueprintRegistry.Mode.valueOf(modeStr.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"unknown_mode\"}");
                return;
            }
            boolean changed = BlueprintRegistry.get().setMode(id, mode);
            writeJson(exchange, changed ? 200 : 404,
                    "{\"ok\":" + changed + ",\"mode\":\"" + mode.name().toLowerCase(Locale.ROOT) + "\"}");
        }

        /**
         * POST /blueprint/{id}/skip  body: {"x":int,"y":int,"z":int,"name":"block_name"}
         * Force-marks a cell as done so getNextMissing won't return it again.
         * Used by the JS bot when a block is in the blacklist or cannot be placed.
         * Unlike markPlaced, this does NOT require the name to match — it always skips.
         */
        private void handleSkip(HttpExchange exchange, String id) throws IOException {
            String body = readBody(exchange.getRequestBody());
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"body_not_json_object\"}");
                return;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("x") || !obj.has("y") || !obj.has("z")) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"missing_coordinates\"}");
                return;
            }
            int x = obj.get("x").getAsInt();
            int y = obj.get("y").getAsInt();
            int z = obj.get("z").getAsInt();
            BlockPos pos = new BlockPos(x, y, z);
            // Use forceSkip: unconditionally marks the cell as done regardless of block name
            boolean skipped = BlueprintRegistry.get().forceSkip(pos);
            JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("skipped", skipped);
            if (skipped) {
                BlueprintRegistry.Blueprint owner = BlueprintRegistry.get().findOwning(pos);
                if (owner != null) root.addProperty("blueprint_id", owner.id);
            }
            writeJson(exchange, 200, root.toString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Chunk Reload API
    //
    //   POST /chunk_reload  {player: "botName"}
    //
    // Called by the JS bot when ChunkWait times out (nearbyBlocks = "none").
    // Triggers ForgeSpawnHelper.forceChunkReload() to resend chunks.
    // ═══════════════════════════════════════════════════════════════════════

    private static class ChunkReloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            String raw = readBody(exchange.getRequestBody());
            String player = jsonValue(raw, "player");
            String reason = jsonValue(raw, "reason");
            if (player == null || player.isBlank()) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"missing_player\"}");
                return;
            }
            ForgeSpawnHelper.forceChunkReload(player);
            writeJson(exchange, 200, "{\"ok\":true,\"triggered\":true}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Syna Voice Broadcast API
    //
    //   POST /voice/broadcast {id,speaker,text,url}
    //
    // Called by the local Python TTS bridge after it has synthesized a WAV and
    // exposed it over HTTP. The Forge packet only carries metadata + URL; each
    // modded client downloads and plays the audio locally.
    // ═══════════════════════════════════════════════════════════════════════

    private static class VoiceBroadcastHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            String raw = readBody(exchange.getRequestBody());
            JsonElement el = JsonParser.parseString(raw);
            if (!el.isJsonObject()) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"body_not_json_object\"}");
                return;
            }
            JsonObject obj = el.getAsJsonObject();
            String id = obj.has("id") ? obj.get("id").getAsString() : null;
            String speaker = obj.has("speaker") ? obj.get("speaker").getAsString() : null;
            String text = obj.has("text") ? obj.get("text").getAsString() : "";
            String url = obj.has("url") ? obj.get("url").getAsString() : "";
            byte[] audioBytes = new byte[0];
            if (obj.has("audio_b64")) {
                try {
                    audioBytes = Base64.getDecoder().decode(obj.get("audio_b64").getAsString());
                } catch (IllegalArgumentException ex) {
                    writeJson(exchange, 400, "{\"ok\":false,\"error\":\"bad_audio_b64\"}");
                    return;
                }
            }
            if ((url == null || url.isBlank()) && audioBytes.length == 0) {
                writeJson(exchange, 400, "{\"ok\":false,\"error\":\"missing_audio\"}");
                return;
            }
            if (id == null || id.isBlank()) id = "syna-" + System.currentTimeMillis();
            if (speaker == null || speaker.isBlank()) speaker = "Syna";
            SynaVoiceNetwork.broadcast(id, speaker, text, url, audioBytes);
            writeJson(exchange, 200, "{\"ok\":true,\"broadcast\":true,\"inline_audio\":" + (audioBytes.length > 0) + "}");
        }
    }
    // ─── Utilities ─────────────────────────────────────────────────────────

    private static void writeJson(HttpExchange exchange, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(urlDecode(pair), "");
            else out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String urlDecode(String s) {
        if (s == null) return "";
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static Integer parseInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // Legacy hand-rolled JSON parsers — kept for /command which still uses
    // them. New handlers use Gson.

    private static String jsonValue(String raw, String key) {
        String needle = "\"" + key + "\"";
        int start = raw.indexOf(needle);
        if (start < 0) return null;
        int colon = raw.indexOf(':', start + needle.length());
        if (colon < 0) return null;
        int firstQuote = raw.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int endQuote = raw.indexOf('"', firstQuote + 1);
        if (endQuote < 0) return null;
        return raw.substring(firstQuote + 1, endQuote);
    }

    private static Double jsonNumber(String raw, String key) {
        String needle = "\"" + key + "\"";
        int start = raw.indexOf(needle);
        if (start < 0) return null;
        int colon = raw.indexOf(':', start + needle.length());
        if (colon < 0) return null;

        int valueStart = colon + 1;
        while (valueStart < raw.length() && Character.isWhitespace(raw.charAt(valueStart))) {
            valueStart++;
        }

        int valueEnd = valueStart;
        while (valueEnd < raw.length()) {
            char c = raw.charAt(valueEnd);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.') {
                valueEnd++;
            } else {
                break;
            }
        }

        if (valueEnd <= valueStart) return null;
        try {
            return Double.parseDouble(raw.substring(valueStart, valueEnd));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer jsonInteger(String raw, String key) {
        Double value = jsonNumber(raw, key);
        return value == null ? null : value.intValue();
    }
}



