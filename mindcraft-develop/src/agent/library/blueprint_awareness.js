/**
 * Blueprint Awareness Module
 * 
 * Automatically checks the SynaBridge mod for unfinished blueprints when the bot spawns,
 * and injects awareness into the AI's history so it knows to continue building.
 * Also provides periodic polling so the AI stays aware of blueprint progress.
 */

import { getModBlueprintClient } from './mod_blueprint_client.js';

let _pollInterval = null;

/**
 * Check mod for active blueprints and return a summary string for the AI.
 * Returns null if no blueprints or mod unreachable.
 */
export async function checkActiveBlueprints() {
    const client = getModBlueprintClient();
    try {
        const res = await client.list();
        if (!res) return null;
        const items = res.blueprints || res.items || [];
        if (!items.length) return null;

        // Java mod /list returns: { id, cells (total), done (placed), mode, origin_x/y/z }
        const unfinished = items.filter(b => {
            const total = b.cells ?? b.total ?? 0;
            const done = b.done ?? b.placed ?? 0;
            return total - done > 0;
        });
        if (!unfinished.length) return null;

        const lines = unfinished.map(b => {
            const total = b.cells ?? b.total ?? 0;
            const done = b.done ?? b.placed ?? 0;
            const remaining = total - done;
            return `  - "${b.id}": ${done}/${total} 已完成, 剩余 ${remaining} 方块, 模式=${b.mode}, 原点=(${b.origin_x},${b.origin_y},${b.origin_z})`;
        });
        return `【蓝图感知】服务器上有 ${unfinished.length} 个未完成的蓝图:\n${lines.join('\n')}\n你可以用 !buildGoal "<id>" 继续建造，或用 !modBlueprintStatus "<id>" 查看详情。`;
    } catch (e) {
        console.warn('[BlueprintAwareness] check failed:', e.message);
        return null;
    }
}

/**
 * Attach blueprint awareness to an agent. Call this after spawn.
 * - Immediately checks for unfinished blueprints and tells the AI
 * - Sets up periodic polling (every 2 minutes) to remind the AI if it's idle
 */
export async function attachBlueprintAwareness(agent) {
    // Initial check after a short delay (let mod HTTP server be ready)
    setTimeout(async () => {
        try {
            const msg = await checkActiveBlueprints();
            if (msg) {
                console.log('[BlueprintAwareness] Found unfinished blueprints, injecting awareness.');
                agent.history.add('system', msg);
                // Store on agent for other systems to query
                agent._activeBlueprintSummary = msg;
            } else {
                console.log('[BlueprintAwareness] No unfinished blueprints found (or mod unreachable).');
                agent._activeBlueprintSummary = null;
            }
        } catch (e) {
            console.warn('[BlueprintAwareness] initial check error:', e.message);
        }
    }, 5000); // 5s delay to let mod HTTP be ready

    // Periodic polling: every 2 minutes, if the AI is idle and there are unfinished blueprints,
    // remind it. This ensures the AI doesn't "forget" about blueprints after long conversations.
    if (_pollInterval) clearInterval(_pollInterval);
    _pollInterval = setInterval(async () => {
        try {
            // Only remind if the AI is not currently in an action
            if (agent.actions.currentActionLabel) return;
            // Only remind if self_prompter is not active (don't interrupt active goals)
            if (agent.self_prompter.isActive()) return;

            const msg = await checkActiveBlueprints();
            if (msg) {
                agent._activeBlueprintSummary = msg;
                // Don't spam - only inject if the last reminder was >5 min ago
                const now = Date.now();
                if (!agent._lastBlueprintReminder || (now - agent._lastBlueprintReminder) > 5 * 60 * 1000) {
                    agent.history.add('system', msg + '\n（提醒：如果玩家之前让你建造，你应该继续。用 !buildGoal 恢复建造。）');
                    agent._lastBlueprintReminder = now;
                }
            } else {
                agent._activeBlueprintSummary = null;
            }
        } catch (e) {
            // silent
        }
    }, 2 * 60 * 1000); // every 2 minutes
}

/**
 * Stop the periodic polling (call on disconnect/cleanup).
 */
export function detachBlueprintAwareness() {
    if (_pollInterval) {
        clearInterval(_pollInterval);
        _pollInterval = null;
    }
}
