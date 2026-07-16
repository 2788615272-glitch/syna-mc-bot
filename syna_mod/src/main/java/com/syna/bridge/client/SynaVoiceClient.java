package com.syna.bridge.client;

import com.mojang.logging.LogUtils;
import com.syna.bridge.AliceEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-side WAV player for Syna voice broadcasts. */
public final class SynaVoiceClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> RECENT = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final int MAX_RECENT = 64;
    private static final AtomicInteger PLAY_GENERATION = new AtomicInteger();
    private static volatile SourceDataLine currentLine;

    private SynaVoiceClient() {}

    public static void interrupt() {
        PLAY_GENERATION.incrementAndGet();
        SourceDataLine line = currentLine;
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {
            }
        }
        LOGGER.info("[SynaVoiceClient] interrupted playback");
    }

    public static void play(String voiceId, String speaker, String text, String url, byte[] audioBytes, boolean interrupt, int generation) {
        if (interrupt) {
            interrupt();
            return;
        }
        String key = voiceId == null || voiceId.isBlank() ? url : voiceId;
        if ((key == null || key.isBlank()) && (audioBytes == null || audioBytes.length == 0)) return;
        synchronized (RECENT) {
            if (RECENT.contains(key)) return;
            RECENT.add(key);
            while (RECENT.size() > MAX_RECENT) {
                String first = RECENT.iterator().next();
                RECENT.remove(first);
            }
        }

        int playGeneration = PLAY_GENERATION.incrementAndGet();
        stopCurrentLine();
        CompletableFuture.runAsync(() -> {
            if (audioBytes != null && audioBytes.length > 0) {
                LOGGER.info("[SynaVoiceClient] playing inline voice id={} speaker={} bytes={}", voiceId, speaker, audioBytes.length);
                playWav(new ByteArrayInputStream(audioBytes), "inline audio", speaker, text, playGeneration);
            } else {
                LOGGER.info("[SynaVoiceClient] playing URL voice id={} speaker={} url={}", voiceId, speaker, url);
                playWavFromUrl(url, speaker, text, playGeneration);
            }
        });
    }

    private static void stopCurrentLine() {
        SourceDataLine line = currentLine;
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void playWavFromUrl(String urlText, String speaker, String text, int playGeneration) {
        if (urlText == null || urlText.isBlank()) return;
        try {
            URL url = URI.create(urlText).toURL();
            playWav(new BufferedInputStream(url.openStream()), urlText, speaker, text, playGeneration);
        } catch (Exception e) {
            if (PLAY_GENERATION.get() == playGeneration) {
                LOGGER.warn("[SynaVoiceClient] Failed to open voice URL {}: {}", urlText, e.getMessage());
            }
        }
    }

    private static void playWav(InputStream input, String sourceLabel, String speaker, String text, int playGeneration) {
        try (InputStream raw = input;
             AudioInputStream sourceStream = AudioSystem.getAudioInputStream(raw)) {
            AudioFormat sourceFormat = sourceStream.getFormat();
            AudioFormat playFormat = toPlayableFormat(sourceFormat);
            try (AudioInputStream audio = AudioSystem.getAudioInputStream(playFormat, sourceStream)) {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, playFormat);
                try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                    currentLine = line;
                    line.open(playFormat);
                    applySpatialControls(line);
                    line.start();
                    byte[] buffer = new byte[8192];
                    int read;
                    while (PLAY_GENERATION.get() == playGeneration && (read = audio.read(buffer, 0, buffer.length)) >= 0) {
                        if (read > 0) line.write(buffer, 0, read);
                    }
                    if (PLAY_GENERATION.get() == playGeneration) {
                        line.drain();
                    }
                } finally {
                    if (currentLine != null && PLAY_GENERATION.get() == playGeneration) {
                        currentLine = null;
                    }
                }
            }
        } catch (Exception e) {
            if (PLAY_GENERATION.get() == playGeneration) {
                LOGGER.warn("[SynaVoiceClient] Failed to play voice from {}: {}", sourceLabel, e.getMessage());
            }
        }
    }

    private static void applySpatialControls(SourceDataLine line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Entity source = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof AliceEntity) {
                source = entity;
                break;
            }
        }
        if (source == null) return;

        double dx = source.getX() - mc.player.getX();
        double dz = source.getZ() - mc.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        float volume = (float) Math.max(0.08D, Math.min(1.0D, 1.0D - distance / 32.0D));
        double yaw = Math.toRadians(mc.player.getYRot());
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double length = Math.max(0.001D, distance);
        float pan = (float) Math.max(-1.0D, Math.min(1.0D, (dx * rightX + dz * rightZ) / length));

        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float db = (float) (20.0D * Math.log10(volume));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
        }
        if (line.isControlSupported(FloatControl.Type.PAN)) {
            FloatControl panControl = (FloatControl) line.getControl(FloatControl.Type.PAN);
            panControl.setValue(Math.max(panControl.getMinimum(), Math.min(panControl.getMaximum(), pan)));
        }
    }

    private static AudioFormat toPlayableFormat(AudioFormat source) {
        if (AudioFormat.Encoding.PCM_SIGNED.equals(source.getEncoding())) return source;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                source.getSampleRate(),
                16,
                source.getChannels(),
                source.getChannels() * 2,
                source.getSampleRate(),
                false);
    }
}
