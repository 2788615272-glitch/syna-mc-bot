package com.syna.bridge;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.*;

/**
 * Injects a Mojang-signed skin texture into bot players' GameProfile on login.
 *
 * Uses a pre-generated textures property from mineskin.org that has a valid
 * Mojang signature. This means ALL clients (including online-mode) will
 * render the skin correctly.
 *
 * To update the skin:
 * 1. Upload new PNG to https://mineskin.org/
 * 2. Copy the "value" and "signature" from the response
 * 3. Replace SKIN_VALUE and SKIN_SIGNATURE below
 * 4. Rebuild the mod
 */
public class BotSkinInjector {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ═══════════════════════════════════════════════════════════════════════
    // Mojang-signed skin data from mineskin.org (syna-skin.png, slim model)
    // Generated: 2026-05-17
    // Texture URL: https://textures.minecraft.net/texture/14138f98dd80a26f10754dee4178ce7f3970646fc611d720db181c50f55cef78
    // ═══════════════════════════════════════════════════════════════════════

    private static final String SKIN_VALUE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc3OTAxMTE4NDE1NCwKICAicHJvZmlsZUlkIiA6ICIxNzNkZTMxZjIzODE0YzY1YjVmZWMyMTAyYmIxZjExNSIsCiAgInByb2ZpbGVOYW1lIiA6ICJCSVROVyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8xNDEzOGY5OGRkODBhMjZmMTA3NTRkZWU0MTc4Y2U3ZjM5NzA2NDZmYzYxMWQ3MjBkYjE4MWM1MGY1NWNlZjc4IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=";

    private static final String SKIN_SIGNATURE =
            "O0M+z3DqKl/3sXXK7UWZXxSinEIXw9Kuv28bKbb26/7A7mBBDSbtrGWa+UOqOIMo5uGsJsqB" +
            "VpphRxNnfQn/flEoCUyJEq+kSaF4NzDLbDPetG4H+jJkxO6pF+JIrenT0FhN8/TsJQR0FfOX" +
            "2oVJRwEKVzqlWY1oxkqYali6LcT7y6aDkBjiNOgTuCi7L0UtOcxwG7yhcn34r2ZrO80GtA/D" +
            "OHn2kO6T47XY7ESgyELw2BU1P0OjOL0S6VZZo0/MZ1U7YjwtfmhajURi4GFdfBHtT32Izbo8" +
            "01dU472wY8Cuk0xg2UJS7ZHOp9lThl9Fc9JOCm/0O43IRRW1sDVNxUPGLyp041O/K+cDO7ns" +
            "m5Vi6WP+CQIjUjWiYRwW+d2KWhjzmv8zmG42cEGeFxw8qQpbkkECKGDchlkQWAmvk+O8ZVAr" +
            "bIEkT7ycLZOIhKgoVV1M9cRZAe/hdEXNabRRjTRcBKOgev8sREcnRFM7tMBnhtEBxk6lya27" +
            "wUimzCH4Vq5u4HuanvMDlyG9IVP/ppmF35naCR7liF2kzSgNQcuH+2a9gD89N4L56Q2qIZX8" +
            "fDRZ4CSNbPmbDe4wYgHP0aRTHhHL8jEpLIhjgz4T8piK9MTEFGAAb1wvIdyK4h6hLVDwJJpP" +
            "/V/N83HYKP2wTpeB9fLhTxrFmcw2QO8xFz8=";

    /**
     * Map of bot name (or prefix) → which skin to use.
     * Currently all bots use the same Syna skin.
     */
    private static final Set<String> BOT_PREFIXES = new HashSet<>();

    static {
        BOT_PREFIXES.add("Syna");
        BOT_PREFIXES.add("syna");
        BOT_PREFIXES.add("Andy");
        BOT_PREFIXES.add("Jill");
    }

    private static boolean isBot(String playerName) {
        for (String prefix : BOT_PREFIXES) {
            if (playerName.startsWith(prefix)) return true;
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.LOW) // Run after BotGuard's login handler
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        String playerName = player.getGameProfile().getName();
        if (isBot(playerName)) {
            try {
                injectSignedSkin(player);
                // Delay the refresh slightly to ensure the player is fully initialized.
                player.getServer().execute(() -> {
                    try {
                        refreshPlayerInfoForAll(player);
                        LOGGER.info("[BotSkinInjector] Successfully applied Mojang-signed skin to '{}'", playerName);
                    } catch (Exception e) {
                        LOGGER.warn("[BotSkinInjector] Failed to refresh skin for '{}': {}", playerName, e.getMessage());
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("[BotSkinInjector] Failed to inject skin for '{}': {}", playerName, e.getMessage());
            }
            return;
        }

        player.getServer().execute(() -> refreshExistingBotsFor(player));
    }
    /**
     * Injects the Mojang-signed "textures" property into the player's GameProfile.
     * Because this has a valid signature from Mojang's session server, all clients
     * will trust and render it.
     */
    private void injectSignedSkin(ServerPlayer player) {
        GameProfile profile = player.getGameProfile();

        // Remove existing textures property if any
        profile.getProperties().removeAll("textures");

        // Add the signed property
        profile.getProperties().put("textures",
                new Property("textures", SKIN_VALUE, SKIN_SIGNATURE));

        LOGGER.info("[BotSkinInjector] Injected signed textures into profile for '{}'", profile.getName());
    }

    /**
     * Re-sends player info to all online players so they pick up the new skin.
     * We remove then re-add the player entry in the tab list.
     */
    private void refreshPlayerInfoForAll(ServerPlayer botPlayer) {
        PlayerList playerList = botPlayer.getServer().getPlayerList();
        List<ServerPlayer> allPlayers = playerList.getPlayers();

        for (ServerPlayer recipient : allPlayers) {
            sendPlayerInfoRefresh(botPlayer, recipient);
        }

        LOGGER.info("[BotSkinInjector] Refreshed player info for '{}' to {} players",
                botPlayer.getGameProfile().getName(), allPlayers.size());
    }

    private void refreshExistingBotsFor(ServerPlayer recipient) {
        PlayerList playerList = recipient.getServer().getPlayerList();
        int refreshed = 0;
        for (ServerPlayer candidate : playerList.getPlayers()) {
            if (!isBot(candidate.getGameProfile().getName())) continue;
            try {
                injectSignedSkin(candidate);
                sendPlayerInfoRefresh(candidate, recipient);
                refreshed++;
            } catch (Exception e) {
                LOGGER.warn("[BotSkinInjector] Failed to refresh existing bot '{}' for '{}': {}",
                        candidate.getGameProfile().getName(), recipient.getGameProfile().getName(), e.getMessage());
            }
        }
        if (refreshed > 0) {
            LOGGER.info("[BotSkinInjector] Refreshed {} existing bot skin(s) for joining player '{}'",
                    refreshed, recipient.getGameProfile().getName());
        }
    }

    private void sendPlayerInfoRefresh(ServerPlayer botPlayer, ServerPlayer recipient) {
        ClientboundPlayerInfoRemovePacket removePacket =
                new ClientboundPlayerInfoRemovePacket(List.of(botPlayer.getUUID()));
        ClientboundPlayerInfoUpdatePacket addPacket =
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(botPlayer));
        recipient.connection.send(removePacket);
        recipient.connection.send(addPacket);
    }
}