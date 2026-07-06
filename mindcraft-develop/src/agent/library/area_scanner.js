/**
 * Area Scanner - Scan a 3D bounding box in the world and produce a
 * blueprint-style snapshot (palette + per-Y grid layers) that is friendly
 * for an LLM to reason about and edit.
 *
 * Strategy (chosen over "three-view projections"):
 *   - Three orthographic projections lose information for hollow/overhanging
 *     structures and make the inverse projection step ambiguous.
 *   - Per-Y horizontal slices preserve every block losslessly while staying
 *     2D per layer, which matches how LLMs already handle the existing
 *     blueprint JSON format (see blueprints/cabin.json).
 *
 * Output format mirrors the existing blueprints so the same builder
 * (buildFromBlockList / applyEditsToSnapshot) can replay edits.
 */

import Vec3 from 'vec3';

// In-memory cache of the most recent scan, keyed by bot username.
// Stored so that follow-up commands like !buildEdits can find the
// world origin and palette without the LLM having to repeat them.
const lastScanByBot = new Map();

/**
 * Pick a short single-character symbol for a block name.
 * We try the first uppercase letter of the name; if taken, fall back to
 * other unique characters.
 */
function allocateSymbol(blockName, palette, symbolToBlock) {
    // Reuse symbol if already mapped
    for (const [sym, name] of Object.entries(palette)) {
        if (name === blockName) return sym;
    }
    // Air is always '.'
    if (blockName === 'air' || blockName === 'cave_air' || blockName === 'void_air') {
        palette['.'] = 'air';
        symbolToBlock['.'] = 'air';
        return '.';
    }
    // Try to use the first letter of the last word, uppercase
    const parts = blockName.split('_');
    const candidates = [];
    for (const part of parts) {
        if (part.length > 0) candidates.push(part[0].toUpperCase());
    }
    // Then add all other letters
    for (const ch of blockName) {
        if (/[a-z]/.test(ch)) candidates.push(ch.toUpperCase());
    }
    // Then digits
    for (const ch of '0123456789') candidates.push(ch);
    // Then lowercase letters
    for (const ch of 'abcdefghijklmnopqrstuvwxyz') candidates.push(ch);
    // Then a few punctuation marks
    for (const ch of '#@%&*+=?/\\!') candidates.push(ch);

    for (const cand of candidates) {
        if (cand === '.') continue;
        if (!symbolToBlock[cand]) {
            palette[cand] = blockName;
            symbolToBlock[cand] = blockName;
            return cand;
        }
    }
    // Last resort: index-based
    let i = 0;
    while (symbolToBlock['§' + i]) i++;
    const sym = '§' + i;
    palette[sym] = blockName;
    symbolToBlock[sym] = blockName;
    return sym;
}

/**
 * Scan a world bounding box into a snapshot object.
 *
 * @param {object} bot - mineflayer bot instance
 * @param {number} x1
 * @param {number} y1
 * @param {number} z1
 * @param {number} x2
 * @param {number} y2
 * @param {number} z2
 * @param {object} [opts]
 * @param {boolean} [opts.collapseAir=true] - treat all air variants as 'air'
 * @returns {{
 *   origin: {x:number,y:number,z:number},
 *   size: {x:number,y:number,z:number},
 *   palette: Object<string,string>,
 *   layers: Array<{y:number, grid:string[]}>
 * }}
 */
export function scanArea(bot, x1, y1, z1, x2, y2, z2, opts = {}) {
    const collapseAir = opts.collapseAir !== false;

    const minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
    const minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
    const minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

    const sizeX = maxX - minX + 1;
    const sizeY = maxY - minY + 1;
    const sizeZ = maxZ - minZ + 1;

    // Soft cap to avoid producing prompts the LLM cannot digest.
    const MAX_VOLUME = 32 * 32 * 32; // 32k blocks
    const volume = sizeX * sizeY * sizeZ;
    if (volume > MAX_VOLUME) {
        throw new Error(`Scan volume ${volume} exceeds limit ${MAX_VOLUME}. Pick a smaller region (max ~32x32x32).`);
    }

    const palette = {};
    const symbolToBlock = {};
    // Pre-register air so it always wins the '.' slot.
    palette['.'] = 'air';
    symbolToBlock['.'] = 'air';

    const layers = [];

    for (let dy = 0; dy < sizeY; dy++) {
        const wy = minY + dy;
        const grid = [];
        for (let dz = 0; dz < sizeZ; dz++) {
            const wz = minZ + dz;
            let row = '';
            for (let dx = 0; dx < sizeX; dx++) {
                const wx = minX + dx;
                let block = bot.blockAt(new Vec3(wx, wy, wz));
                let name = block ? block.name : 'air';
                if (collapseAir && (name === 'cave_air' || name === 'void_air')) {
                    name = 'air';
                }
                const sym = allocateSymbol(name, palette, symbolToBlock);
                row += sym;
            }
            grid.push(row);
        }
        layers.push({ y: dy, world_y: wy, grid });
    }

    const snapshot = {
        origin: { x: minX, y: minY, z: minZ },
        size: { x: sizeX, y: sizeY, z: sizeZ },
        palette,
        layers,
    };

    if (bot && bot.username) {
        lastScanByBot.set(bot.username, snapshot);
    }
    return snapshot;
}

/**
 * Look up the most recent snapshot taken by this bot.
 */
export function getLastScan(bot) {
    if (!bot || !bot.username) return null;
    return lastScanByBot.get(bot.username) || null;
}

/**
 * Render a snapshot into a compact LLM-friendly multi-line string.
 * Truncates very large layers with an ASCII frame so prompts stay readable.
 *
 * @param {object} snapshot
 * @param {object} [opts]
 * @param {number} [opts.maxLayers=12] - max number of Y-slices to print
 * @returns {string}
 */
export function renderSnapshotForLLM(snapshot, opts = {}) {
    const maxLayers = opts.maxLayers || 12;
    const { origin, size, palette, layers } = snapshot;
    const lines = [];
    lines.push(`Snapshot origin world=(${origin.x}, ${origin.y}, ${origin.z}) size=${size.x}x${size.y}x${size.z}`);
    lines.push(`Coordinate convention: local (x,y,z) = world - origin. y increases upward; z is north-south rows; x is west-east columns.`);
    lines.push(`Palette (symbol -> block):`);
    for (const [sym, name] of Object.entries(palette)) {
        lines.push(`  '${sym}' = ${name}`);
    }

    const layersToShow = layers.length <= maxLayers
        ? layers
        : [...layers.slice(0, Math.ceil(maxLayers / 2)),
           ...layers.slice(-Math.floor(maxLayers / 2))];
    if (layers.length > maxLayers) {
        lines.push(`(showing ${layersToShow.length} of ${layers.length} layers; ask for more if needed)`);
    }

    for (const layer of layersToShow) {
        lines.push('');
        lines.push(`y=${layer.y} (world_y=${layer.world_y}):`);
        for (const row of layer.grid) {
            lines.push('  ' + row);
        }
    }
    return lines.join('\n');
}

/**
 * Apply a list of edits (add/remove/replace) on top of a stored snapshot
 * and return a flat list of world-coordinate block placements ready for
 * buildFromBlockList.
 *
 * Edits format - the LLM should output one of:
 *   { op: "set", x, y, z, block: "oak_planks" }   // local coords
 *   { op: "remove", x, y, z }                      // == set air
 *
 * Coordinates are LOCAL to the snapshot origin unless `world: true` is set.
 *
 * @param {object} snapshot
 * @param {Array<object>} edits
 * @returns {{
 *   placements: Array<{block:string,x:number,y:number,z:number,placeOn?:string}>,
 *   removals: Array<{x:number,y:number,z:number}>,
 *   skipped: Array<{edit:object, reason:string}>
 * }}
 */
export function buildEditPlan(snapshot, edits) {
    const placements = [];
    const removals = [];
    const skipped = [];

    if (!Array.isArray(edits)) {
        return { placements, removals, skipped: [{ edit: edits, reason: 'edits is not an array' }] };
    }

    const { origin, size } = snapshot;

    for (const raw of edits) {
        if (!raw || typeof raw !== 'object') {
            skipped.push({ edit: raw, reason: 'not an object' });
            continue;
        }
        const op = (raw.op || 'set').toLowerCase();
        let { x, y, z } = raw;
        if (typeof x !== 'number' || typeof y !== 'number' || typeof z !== 'number') {
            skipped.push({ edit: raw, reason: 'missing numeric x/y/z' });
            continue;
        }
        // Convert to world coordinates
        let wx, wy, wz;
        if (raw.world) {
            wx = Math.floor(x); wy = Math.floor(y); wz = Math.floor(z);
        } else {
            wx = origin.x + Math.floor(x);
            wy = origin.y + Math.floor(y);
            wz = origin.z + Math.floor(z);
        }
        // Optional bbox check (only when local coords were used)
        if (!raw.world) {
            if (x < 0 || x >= size.x || y < 0 || y >= size.y || z < 0 || z >= size.z) {
                skipped.push({ edit: raw, reason: `local coord out of bounds (size ${size.x}x${size.y}x${size.z})` });
                continue;
            }
        }

        if (op === 'remove' || raw.block === 'air') {
            removals.push({ x: wx, y: wy, z: wz });
            continue;
        }
        if (op !== 'set' && op !== 'place') {
            skipped.push({ edit: raw, reason: `unknown op "${op}"` });
            continue;
        }
        if (typeof raw.block !== 'string' || raw.block.length === 0) {
            skipped.push({ edit: raw, reason: 'missing block name' });
            continue;
        }
        placements.push({
            block: raw.block,
            x: wx,
            y: wy,
            z: wz,
            placeOn: raw.placeOn || 'bottom',
        });
    }

    // Sort placements bottom-up so support blocks exist before what rests on them.
    placements.sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x);
    return { placements, removals, skipped };
}

/**
 * Apply removals (break blocks) and then placements via the existing
 * skills layer. This is a thin convenience wrapper; callers can also use
 * buildEditPlan + the existing builder directly.
 */
export async function applyEdits(bot, snapshot, edits, skills) {
    const plan = buildEditPlan(snapshot, edits);
    let removed = 0, placed = 0, failed = 0;
    const isCreative = bot.game && bot.game.gameMode === 'creative';

    // Removals first (so we don't try to place into occupied space)
    for (const r of plan.removals) {
        if (bot.interrupt_code) break;
        try {
            if (isCreative) {
                bot.chat(`/setblock ${r.x} ${r.y} ${r.z} air`);
                await new Promise(res => setTimeout(res, 30));
                removed++;
            } else {
                const ok = await skills.breakBlockAt(bot, r.x, r.y, r.z);
                if (ok) removed++; else failed++;
            }
        } catch (_e) {
            failed++;
        }
    }

    // Placements
    for (const p of plan.placements) {
        if (bot.interrupt_code) break;
        try {
            let ok = false;
            if (isCreative) {
                bot.chat(`/setblock ${p.x} ${p.y} ${p.z} ${p.block}`);
                await new Promise(res => setTimeout(res, 30));
                ok = true;
            } else {
                ok = await skills.placeBlock(bot, p.block, p.x, p.y, p.z, p.placeOn);
            }
            if (ok) placed++; else failed++;
        } catch (_e) {
            failed++;
        }
    }

    return { removed, placed, failed, skipped: plan.skipped };
}
