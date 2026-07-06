package com.syna.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Enforces blueprint integrity at the server level.
 *
 * Rules (per blueprint):
 *   - BUILD mode:
 *       * Bot may not break a cell that is currently "done" (matches plan).
 *       * Bot's place attempts at a plan cell are validated: wrong block →
 *         cancel so the bot's mineflayer place() throws and retries.
 *       * Players are unaffected — they can still tweak in BUILD mode.
 *   - REMODEL mode:
 *       * Protection is fully suspended. Bot can break/place freely so the
 *         LLM can edit the plan. Done-flags are cleared on break.
 *   - LOCKED mode:
 *       * Nobody (bot or player) can break done cells.
 *
 * Why "only restrict the bot in BUILD mode" — players are the source of
 * intent. If they break a wall they meant to. The bot, however, may path
 * through its own house and shred it; that's the failure mode we're
 * actually fixing.
 */
public class BlueprintProtection {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBreak(BlockEvent.BreakEvent event) {
        BlockPos pos = event.getPos();
        BlueprintRegistry.Blueprint bp = BlueprintRegistry.get().findOwning(pos);
        if (bp == null) return;

        // REMODEL: free-for-all. Side effect: if the broken cell was previously
        // marked done, clear the flag so it'll be rebuilt.
        if (bp.mode == BlueprintRegistry.Mode.REMODEL) {
            BlueprintRegistry.get().markBroken(pos);
            return;
        }

        boolean done = Boolean.TRUE.equals(bp.done.get(pos));

        if (bp.mode == BlueprintRegistry.Mode.LOCKED) {
            if (done) {
                event.setCanceled(true);
                if (event.getPlayer() instanceof ServerPlayer sp) {
                    sp.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§c[Blueprint] '" + bp.id + "' is locked."),
                            true);
                }
            }
            return;
        }

        // BUILD mode: only restrict bots, only on done cells. Players are free
        // to remodel mid-build.
        if (bp.mode == BlueprintRegistry.Mode.BUILD) {
            if (!done) return;
            Entity actor = event.getPlayer();
            if (actor instanceof ServerPlayer sp && BotIdentity.isBot(sp)) {
                event.setCanceled(true);
                SynaBridgeMod.LOGGER.info(
                        "[Blueprint] cancelled bot break at {} (id={}, BUILD)", pos, bp.id);
            } else {
                // Player tweak — allow, but unmark so the bot will rebuild it
                BlueprintRegistry.get().markBroken(pos);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        // Only care about cells the registry knows about
        BlockPos pos = event.getPos();
        BlueprintRegistry.Blueprint bp = BlueprintRegistry.get().findOwning(pos);
        if (bp == null) return;

        var key = ForgeRegistries.BLOCKS.getKey(event.getPlacedBlock().getBlock());
        String placedName = key == null ? "" : key.getPath();
        String expected = bp.cellByPos.get(pos);

        // REMODEL: registry doesn't care; let the new block stand and clear
        // any done-flag (it no longer matches the plan after this edit).
        if (bp.mode == BlueprintRegistry.Mode.REMODEL) {
            // Do nothing, REMODEL means human/bot is editing the plan content.
            return;
        }

        if (placedName.equals(expected)) {
            // Match — mark done.
            BlueprintRegistry.get().markPlaced(pos, placedName);
            return;
        }

        // Wrong block at a plan cell. If a bot did it, cancel so mineflayer's
        // place() throws and the build loop can retry/replan. If a player did
        // it, leave it alone — players are allowed to deviate.
        Entity actor = event.getEntity();
        if (actor instanceof ServerPlayer sp && BotIdentity.isBot(sp)) {
            event.setCanceled(true);
            SynaBridgeMod.LOGGER.info(
                    "[Blueprint] cancelled bot wrong-block place at {} expected={} got={} (id={})",
                    pos, expected, placedName, bp.id);
        }
    }

    /**
     * If the bot tries to right-click a plan cell with a wrong item (e.g.
     * place a torch on a wall slot), the EntityPlaceEvent above already
     * handles cancellation. We don't need a RightClickBlock pre-check.
     *
     * Kept as a stub-free reminder: we deliberately removed the chat-based
     * "soft warning" that the old version sent on right-click — it was
     * spammy and only useful when the bot couldn't read protection state.
     */
    @SuppressWarnings("unused")
    private void unusedRightClickHandler(PlayerInteractEvent.RightClickBlock event) {}
}
