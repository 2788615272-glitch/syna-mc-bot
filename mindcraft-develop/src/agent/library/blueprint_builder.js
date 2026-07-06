/**
 * Blueprint Builder - Builds structures from JSON blueprint definitions.
 * Integrates with mineflayer's placeBlock via skills.js.
 */

import * as skills from './skills.js';
import { writeBugReport } from '../debug_journal.js';
import { bpLog } from './bp_log.js';
import pf from 'mineflayer-pathfinder';
const { Movements, goals: { GoalNear } } = pf;
import Vec3 from 'vec3';
import { readFileSync, readdirSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const BLUEPRINTS_DIR = join(__dirname, 'blueprints');

// Cache loaded blueprints
const blueprintCache = new Map();

/**
 * Load all available blueprint names from the blueprints directory.
 * @returns {string[]} Array of blueprint names (without .json extension)
 */
export function listBlueprints() {
    try {
        return readdirSync(BLUEPRINTS_DIR)
            .filter(f => f.endsWith('.json'))
            .map(f => f.replace('.json', ''));
    } catch (e) {
        return [];
    }
}

/**
 * Load a blueprint by name from the blueprints directory.
 * @param {string} name - Blueprint name (e.g. "cabin", "tower")
 * @returns {object|null} The parsed blueprint object, or null if not found
 */
export function loadBlueprint(name) {
    if (blueprintCache.has(name)) return blueprintCache.get(name);
    
    const filePath = join(BLUEPRINTS_DIR, `${name}.json`);
    try {
        const data = JSON.parse(readFileSync(filePath, 'utf8'));
        blueprintCache.set(name, data);
        return data;
    } catch (e) {
        return null;
    }
}

/**
 * Parse a blueprint into a flat list of block placements sorted bottom-up.
 * @param {object} blueprint - The blueprint object
 * @returns {Array<{x:number, y:number, z:number, block:string, placeOn:string}>}
 */
export function flattenBlueprint(blueprint) {
    const blocks = [];
    const { palette, layers, decorations } = blueprint;

    // Process grid layers
    if (layers) {
        for (const layer of layers) {
            const y = layer.y;
            for (let z = 0; z < layer.grid.length; z++) {
                const row = layer.grid[z];
                for (let x = 0; x < row.length; x++) {
                    const char = row[x];
                    if (char === '.' || !palette[char]) continue;
                    const block = palette[char];
                    if (block === 'air') continue;
                    blocks.push({ x, y, z, block, placeOn: 'bottom' });
                }
            }
        }
    }

    // Process decorations (placed after main structure)
    if (decorations) {
        for (const deco of decorations) {
            blocks.push({
                x: deco.x,
                y: deco.y,
                z: deco.z,
                block: deco.block,
                placeOn: deco.placeOn || 'bottom'
            });
        }
    }

    // Sort: bottom layers first, then by z, then by x
    blocks.sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x);
    return blocks;
}

/**
 * Check if the bot has enough materials to build a blueprint.
 * @param {object} bot - The mineflayer bot instance
 * @param {string} blueprintName - Name of the blueprint
 * @returns {{ready: boolean, needed: Object<string,number>, missing: Object<string,number>, have: Object<string,number>}}
 */
export function checkMaterials(bot, blueprintName) {
    const blueprint = loadBlueprint(blueprintName);
    if (!blueprint) return { ready: false, needed: {}, missing: { error: 'blueprint not found' }, have: {} };

    const blocks = flattenBlueprint(blueprint);
    
    // Count required materials
    const needed = {};
    for (const entry of blocks) {
        needed[entry.block] = (needed[entry.block] || 0) + 1;
    }

    // Count inventory
    const have = {};
    for (const item of bot.inventory.items()) {
        have[item.name] = (have[item.name] || 0) + item.count;
    }

    // Calculate missing
    const missing = {};
    for (const [block, count] of Object.entries(needed)) {
        const inInventory = have[block] || 0;
        if (inInventory < count) {
            missing[block] = count - inInventory;
        }
    }

    const ready = Object.keys(missing).length === 0;
    return { ready, needed, missing, have };
}

/**
 * Build a blueprint at the given world coordinates.
 * Uses skills.placeBlock for each block placement.
 * Will refuse to start if materials are insufficient (unless force=true).
 * 
 * @param {object} bot - The mineflayer bot instance
 * @param {string} blueprintName - Name of the blueprint to build
 * @param {number} originX - World X coordinate for the build origin
 * @param {number} originY - World Y coordinate for the build origin
 * @param {number} originZ - World Z coordinate for the build origin
 * @param {boolean} [force=false] - If true, skip material check and build anyway
 * @returns {Promise<{placed: number, skipped: number, failed: number, total: number}>}
 */
export async function buildBlueprint(bot, blueprintName, originX, originY, originZ, force = false) {
    const blueprint = loadBlueprint(blueprintName);
    if (!blueprint) {
        skills.log(bot, `Blueprint "${blueprintName}" not found. Available: ${listBlueprints().join(', ')}`);
        return { placed: 0, skipped: 0, failed: 0, total: 0 };
    }

    // Material pre-check gate — skip in creative mode (infinite inventory)
    const isCreative = bot.game && bot.game.gameMode === 'creative';
    if (!force && !isCreative) {
        const check = checkMaterials(bot, blueprintName);
        if (!check.ready) {
            const missingList = Object.entries(check.missing)
                .map(([block, count]) => `${block} x${count}`)
                .join(', ');
            skills.log(bot, `Cannot start build "${blueprintName}": missing materials: ${missingList}. Gather these first or use force=true to override.`);
            return { placed: 0, skipped: 0, failed: 0, total: 0 };
        }
    }

    const blocks = flattenBlueprint(blueprint);
    const total = blocks.length;
    let placed = 0;
    let skipped = 0;
    let failed = 0;

    skills.log(bot, `Starting build: "${blueprint.name}" (${total} blocks) at (${originX}, ${originY}, ${originZ})`);
    bpLog(`buildBlueprint start: name=${blueprintName}, origin=(${originX},${originY},${originZ}), total=${total}, creative=${isCreative}, force=${force}`);

    for (const entry of blocks) {
        if (bot.interrupt_code) {
            skills.log(bot, `Build interrupted. Placed ${placed}/${total} blocks.`);
            break;
        }

        const wx = originX + entry.x;
        const wy = originY + entry.y;
        const wz = originZ + entry.z;

        // Check if block already exists at target position
        const targetBlock = bot.blockAt(new Vec3(wx, wy, wz));
        if (targetBlock && targetBlock.name === entry.block) {
            skipped++;
            continue;
        }

        // --- Navigate to within reach of the target block ---
        let reachable = false;
        const dist = bot.entity.position.distanceTo(new Vec3(wx, wy, wz));
        if (dist <= 4.5) {
            reachable = true;
        } else {
            try {
                const movements = new Movements(bot);
                movements.canDig = false; // Don't break blocks to path
                bot.pathfinder.setMovements(movements);
                await bot.pathfinder.goto(new GoalNear(wx, wy, wz, 3));
                reachable = true;
            } catch (pathErr) {
                reachable = false;
            }
        }

        // --- Attempt placement ---
        let success = false;
        if (reachable) {
            try {
                success = await skills.placeBlock(bot, entry.block, wx, wy, wz, entry.placeOn);
                if (!success) bpLog(`placeBlock returned false: block=${entry.block} at (${wx},${wy},${wz}), placeOn=${entry.placeOn}`);
            } catch (e) {
                bpLog(`placeBlock threw: block=${entry.block} at (${wx},${wy},${wz}), error=${e.message}`);
                success = false;
            }
        } else {
            bpLog(`unreachable: block=${entry.block} at (${wx},${wy},${wz}), dist=${dist.toFixed(1)}`);
        }

        // --- Fallback: /setblock command (creative/op mode) ---
        if (!success && isCreative) {
            try {
                bot.chat(`/setblock ${wx} ${wy} ${wz} ${entry.block}`);
                await new Promise(resolve => setTimeout(resolve, 50));
                success = true;
            } catch (e) {
                success = false;
            }
        }

        if (success) {
            placed++;
        } else {
            failed++;
        }

        // Brief pause every 10 blocks to avoid server spam
        if ((placed + failed) % 10 === 0) {
            await new Promise(resolve => setTimeout(resolve, 100));
        }
    }

    const msg = `Build complete: "${blueprint.name}" - placed ${placed}, skipped ${skipped}, failed ${failed} / ${total} total.`;
    skills.log(bot, msg);
    bpLog(`buildBlueprint done: placed=${placed}, skipped=${skipped}, failed=${failed}, total=${total}`);

    // 自动写bug报告：当placed=0且有方块需要放置时
    if (placed === 0 && total > 0 && failed > 0) {
        try {
            writeBugReport({
                error: `buildBlueprint placed=0: "${blueprintName}" (${failed} failed / ${total} total)`,
                context: `尝试在 (${originX},${originY},${originZ}) 建造蓝图 "${blueprintName}"`,
                analysis: `所有方块放置都失败了。可能原因：1) 背包没有对应材料 2) placeBlock无法找到支撑面 3) 路径不可达无法靠近目标位置`,
                suggestion: '检查console中的[BP-DIAG]日志，确认placeBlock失败的具体原因',
                rawData: { blueprintName, origin: { x: originX, y: originY, z: originZ }, placed, skipped, failed, total, isCreative, force }
            });
        } catch (e) { /* ignore journal write errors */ }
    }

    return { placed, skipped, failed, total };
}

/**
 * Build from a dynamic block list (for LLM-generated blueprints).
 * @param {object} bot - The mineflayer bot instance
 * @param {Array<{block:string, x:number, y:number, z:number}>} blockList - Array of block placements
 * @param {number} originX - World X origin
 * @param {number} originY - World Y origin
 * @param {number} originZ - World Z origin
 * @returns {Promise<{placed: number, failed: number, total: number}>}
 */
export async function buildFromBlockList(bot, blockList, originX, originY, originZ) {
    // Sort bottom-up
    blockList.sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x);

    const total = blockList.length;
    let placed = 0;
    let failed = 0;
    const isCreative = bot.game && bot.game.gameMode === 'creative';

    skills.log(bot, `Building custom structure (${total} blocks) at (${originX}, ${originY}, ${originZ})`);

    for (const entry of blockList) {
        if (bot.interrupt_code) {
            skills.log(bot, `Build interrupted. Placed ${placed}/${total} blocks.`);
            break;
        }

        const wx = originX + entry.x;
        const wy = originY + entry.y;
        const wz = originZ + entry.z;

        // Navigate to within reach
        let reachable = false;
        const dist = bot.entity.position.distanceTo(new Vec3(wx, wy, wz));
        if (dist <= 4.5) {
            reachable = true;
        } else {
            try {
                const movements = new Movements(bot);
                movements.canDig = false;
                bot.pathfinder.setMovements(movements);
                await bot.pathfinder.goto(new GoalNear(wx, wy, wz, 3));
                reachable = true;
            } catch (pathErr) {
                reachable = false;
            }
        }

        let success = false;
        if (reachable) {
            try {
                success = await skills.placeBlock(bot, entry.block, wx, wy, wz, entry.placeOn || 'bottom');
            } catch (e) {
                success = false;
            }
        }

        // Fallback: /setblock in creative mode
        if (!success && isCreative) {
            try {
                bot.chat(`/setblock ${wx} ${wy} ${wz} ${entry.block}`);
                await new Promise(resolve => setTimeout(resolve, 50));
                success = true;
            } catch (e) {
                success = false;
            }
        }

        if (success) placed++;
        else failed++;

        if ((placed + failed) % 10 === 0) {
            await new Promise(resolve => setTimeout(resolve, 100));
        }
    }

    skills.log(bot, `Custom build done: placed ${placed}, failed ${failed} / ${total} total.`);
    return { placed, failed, total };
}
