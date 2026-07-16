package com.syna.bridge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

/** Broadcasts Syna voice playback events to every modded client. */
public final class SynaVoiceNetwork {
    public static final String PROTOCOL_VERSION = "4";
    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(SynaBridgeMod.MOD_ID, "voice");
    private static final int MAX_AUDIO_BYTES = 1024 * 1024;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID,
            () -> PROTOCOL_VERSION,
            s -> true,
            s -> true);

    private SynaVoiceNetwork() {}

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(S2CVoicePlay.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CVoicePlay::encode)
                .decoder(S2CVoicePlay::decode)
                .consumerMainThread(S2CVoicePlay::handle)
                .add();
        CHANNEL.messageBuilder(S2COpeningOmen.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2COpeningOmen::encode)
                .decoder(S2COpeningOmen::decode)
                .consumerMainThread(S2COpeningOmen::handle)
                .add();
        SynaBridgeMod.LOGGER.info("[SynaVoiceNetwork] channel registered ({} packet types)", id);
    }

    public static void broadcast(String voiceId, String speaker, String text, String url, byte[] audioBytes) {
        broadcast(voiceId, speaker, text, url, audioBytes, false, 0);
    }

    public static void broadcast(String voiceId, String speaker, String text, String url, byte[] audioBytes, boolean interrupt, int generation) {
        byte[] safeAudio = audioBytes == null ? new byte[0] : audioBytes;
        if (!interrupt && (url == null || url.isBlank()) && safeAudio.length == 0) return;
        if (safeAudio.length > MAX_AUDIO_BYTES) {
            SynaBridgeMod.LOGGER.warn("[SynaVoiceNetwork] inline audio is too large: {} bytes; truncating to {} bytes", safeAudio.length, MAX_AUDIO_BYTES);
        }
        SynaBridgeMod.LOGGER.info("[SynaVoiceNetwork] broadcasting voice id={} speaker={} interrupt={} generation={} inlineBytes={} urlFallback={}",
                voiceId, speaker, interrupt, generation, Math.min(safeAudio.length, MAX_AUDIO_BYTES), url != null && !url.isBlank());
        CHANNEL.send(PacketDistributor.ALL.noArg(), new S2CVoicePlay(voiceId, speaker, text, url, safeAudio, interrupt, generation));
    }

    public static void sendOpeningOmen(ServerPlayer player, int phase, int durationTicks) {
        if (player == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2COpeningOmen(phase, durationTicks));
    }

    public static final class S2COpeningOmen {
        public final int phase;
        public final int durationTicks;

        public S2COpeningOmen(int phase, int durationTicks) {
            this.phase = Math.max(1, Math.min(2, phase));
            this.durationTicks = Math.max(1, Math.min(20 * 10, durationTicks));
        }

        public static void encode(S2COpeningOmen packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.phase);
            buffer.writeVarInt(packet.durationTicks);
        }

        public static S2COpeningOmen decode(FriendlyByteBuf buffer) {
            return new S2COpeningOmen(buffer.readVarInt(), buffer.readVarInt());
        }

        public static void handle(S2COpeningOmen packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
                com.syna.bridge.client.SynaOpeningOmenClient.trigger(packet.phase, packet.durationTicks);
            }
            context.setPacketHandled(true);
        }
    }

    public static final class S2CVoicePlay {
        public final String voiceId;
        public final String speaker;
        public final String text;
        public final String url;
        public final byte[] audioBytes;
        public final boolean interrupt;
        public final int generation;

        public S2CVoicePlay(String voiceId, String speaker, String text, String url, byte[] audioBytes, boolean interrupt, int generation) {
            this.voiceId = safe(voiceId);
            this.speaker = safe(speaker);
            this.text = safe(text);
            this.url = safe(url);
            this.audioBytes = audioBytes == null ? new byte[0] : audioBytes;
            this.interrupt = interrupt;
            this.generation = generation;
        }

        public static void encode(S2CVoicePlay pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.voiceId, 128);
            buf.writeUtf(pkt.speaker, 64);
            buf.writeUtf(pkt.text, 512);
            buf.writeUtf(pkt.url, 2048);
            buf.writeBoolean(pkt.interrupt);
            buf.writeVarInt(pkt.generation);
            int len = Math.min(pkt.audioBytes.length, MAX_AUDIO_BYTES);
            buf.writeVarInt(len);
            buf.writeBytes(pkt.audioBytes, 0, len);
        }

        public static S2CVoicePlay decode(FriendlyByteBuf buf) {
            String voiceId = buf.readUtf(128);
            String speaker = buf.readUtf(64);
            String text = buf.readUtf(512);
            String url = buf.readUtf(2048);
            boolean interrupt = buf.readBoolean();
            int generation = buf.readVarInt();
            int len = Math.min(buf.readVarInt(), MAX_AUDIO_BYTES);
            byte[] audio = new byte[len];
            if (len > 0) buf.readBytes(audio);
            return new S2CVoicePlay(voiceId, speaker, text, url, audio, interrupt, generation);
        }

        public static void handle(S2CVoicePlay pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
                com.syna.bridge.client.SynaVoiceClient.play(pkt.voiceId, pkt.speaker, pkt.text, pkt.url, pkt.audioBytes, pkt.interrupt, pkt.generation);
            }
            ctx.setPacketHandled(true);
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
