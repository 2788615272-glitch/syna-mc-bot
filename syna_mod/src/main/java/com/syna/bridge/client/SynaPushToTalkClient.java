package com.syna.bridge.client;

import com.syna.bridge.SynaBridgeMod;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends ordered push-to-talk edges to the local ASR service off the render thread. */
public final class SynaPushToTalkClient {
    private static final String CONTROL_URL = "http://127.0.0.1:8089";
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "syna-ptt-control");
        thread.setDaemon(true);
        return thread;
    });

    private SynaPushToTalkClient() {
    }

    public static void start() {
        post("/ptt/start");
    }

    public static void stop() {
        post("/ptt/stop");
    }

    private static void post(String path) {
        REQUESTS.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(CONTROL_URL + path).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(700);
                connection.setReadTimeout(700);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    SynaBridgeMod.LOGGER.warn("[SynaPTT] {} returned HTTP {}", path, status);
                }
            } catch (Exception e) {
                SynaBridgeMod.LOGGER.warn("[SynaPTT] failed to call {}: {}", path, e.toString());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }
}
