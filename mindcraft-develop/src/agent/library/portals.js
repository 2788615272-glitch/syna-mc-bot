import * as world from './world.js';
import * as skills from './skills.js';
import pf from 'mineflayer-pathfinder';
import Vec3 from 'vec3';

function log(bot, msg) { bot.output += msg + '\n'; }

async function waitForDimensionChange(bot, fromDim, timeoutMs = 15000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        if (bot.game && bot.game.dimension && bot.game.dimension !== fromDim) {
            return bot.game.dimension;
        }
        await new Promise(r => setTimeout(r, 200));
    }
    return null;
}

function cleanAxis(axis) {
    return axis === 'z' ? 'z' : 'x';
}

function portalVectors(axis) {
    axis = cleanAxis(axis);
    return {
        dx: axis === 'x' ? 1 : 0,
        dz: axis === 'z' ? 1 : 0,
        nx: axis === 'x' ? 0 : 1,
        nz: axis === 'x' ? 1 : 0,
    };
}

function portalPos(x, y, z, axis, i, h, n = 0) {
    const v = portalVectors(axis);
    return new Vec3(x + v.dx * i + v.nx * n, y + h, z + v.dz * i + v.nz * n);
}

function blockName(block) {
    return String(block?.name || 'unknown');
}

function isAirish(block) {
    const name = blockName(block);
    return name === 'air' || name === 'cave_air' || name === 'void_air' || name === 'fire' || name === 'soul_fire';
}

function isLiquidOrHardStop(block) {
    const name = blockName(block);
    return name === 'lava' || name === 'water' || name === 'bedrock' || name === 'barrier' || name === 'end_portal_frame' || name === 'end_portal';
}

function isSolidFloor(block) {
    if (!block || isLiquidOrHardStop(block) || isAirish(block)) return false;
    return block.boundingBox === 'block' || block.diggable;
}

function frameOffsets() {
    const frame = [];
    for (let i = 1; i <= 2; i++) frame.push([i, 0]);
    for (let i = 1; i <= 2; i++) frame.push([i, 4]);
    for (let h = 1; h <= 3; h++) frame.push([0, h]);
    for (let h = 1; h <= 3; h++) frame.push([3, h]);
    return frame;
}

function interiorOffsets() {
    const inside = [];
    for (let i = 1; i <= 2; i++) {
        for (let h = 1; h <= 3; h++) inside.push([i, h]);
    }
    return inside;
}

function portalPlatformPositions(x, y, z, axis) {
    const positions = [];
    for (let i = -1; i <= 4; i++) {
        for (let n = -2; n <= 2; n++) {
            if (n === 0 && i >= 0 && i <= 3) continue;
            positions.push(portalPos(x, y, z, axis, i, 0, n));
        }
    }
    return positions;
}

function isFrameOffset(i, h) {
    return frameOffsets().some(([fi, fh]) => fi === i && fh === h);
}

function portalClearPositions(x, y, z, axis) {
    const positions = [];
    for (let i = -1; i <= 4; i++) {
        for (let n = -2; n <= 2; n++) {
            for (let h = 1; h <= 5; h++) {
                if (n === 0 && isFrameOffset(i, h)) continue;
                positions.push(portalPos(x, y, z, axis, i, h, n));
            }
        }
    }
    return positions;
}

export const PORTAL_FILL_BLOCKS = ['cobblestone', 'cobbled_deepslate', 'netherrack', 'blackstone', 'basalt', 'stone', 'deepslate', 'dirt', 'oak_planks', 'planks'];

export function countPortalFillBlocks(bot) {
    const counts = world.getInventoryCounts(bot);
    return PORTAL_FILL_BLOCKS.reduce((sum, name) => sum + (counts[name] || 0), 0);
}

function choosePortalFillBlock(bot) {
    const counts = world.getInventoryCounts(bot);
    return PORTAL_FILL_BLOCKS.find(name => (counts[name] || 0) > 0) || null;
}

export function isProtectedPortalObsidian(bot, pos, range = 64) {
    const inspection = inspectNetherPortal(bot, range);
    if (!inspection.frame) return false;
    return frameOffsets().some(([i, h]) => {
        const p = portalPos(inspection.frame.x, inspection.frame.y, inspection.frame.z, inspection.frame.axis, i, h);
        return p.x === pos.x && p.y === pos.y && p.z === pos.z;
    });
}

function scorePortalSite(bot, x, y, z, axis) {
    let score = 0;
    for (const [i, h] of interiorOffsets()) {
        const b = bot.blockAt(portalPos(x, y, z, axis, i, h));
        if (isLiquidOrHardStop(b)) return -10000;
        if (isAirish(b)) score += 6;
        else if (b?.diggable) score += 1;
        else score -= 12;
    }
    for (const p of portalPlatformPositions(x, y, z, axis)) {
        const b = bot.blockAt(p);
        if (isSolidFloor(b)) score += 2;
        else if (isAirish(b)) score -= 1;
        else if (blockName(b) === 'lava') score -= 8;
        else score -= 3;
    }
    for (const p of portalClearPositions(x, y, z, axis).slice(0, 80)) {
        const b = bot.blockAt(p);
        if (isLiquidOrHardStop(b)) score -= 20;
        else if (isAirish(b)) score += 1;
        else if (b?.diggable) score -= 1;
        else score -= 6;
    }
    const pos = bot.entity?.position;
    if (pos) score -= Math.min(20, pos.distanceTo(new Vec3(x + 1.5, y + 1, z + 1.5)) * 0.4);
    return score;
}

function findStablePortalSite(bot, requestedX, requestedY, requestedZ, axis) {
    axis = cleanAxis(axis);
    const bases = [];
    if (Number.isFinite(requestedX) && Number.isFinite(requestedY) && Number.isFinite(requestedZ)) {
        bases.push(new Vec3(Math.floor(requestedX), Math.floor(requestedY), Math.floor(requestedZ)));
    }
    if (bot.entity?.position) {
        const p = bot.entity.position;
        bases.push(new Vec3(Math.floor(p.x), Math.floor(p.y) - 1, Math.floor(p.z)));
    }
    let best = null;
    for (const base of bases) {
        for (let dy = -2; dy <= 2; dy++) {
            for (let ox = -8; ox <= 8; ox++) {
                for (let oz = -8; oz <= 8; oz++) {
                    const x = base.x + ox;
                    const y = base.y + dy;
                    const z = base.z + oz;
                    const score = scorePortalSite(bot, x, y, z, axis);
                    if (!best || score > best.score) best = { x, y, z, axis, score };
                }
            }
        }
    }
    const requestedScore = scorePortalSite(bot, Math.floor(requestedX), Math.floor(requestedY), Math.floor(requestedZ), axis);
    if (!best || requestedScore >= best.score - 4) return { x: Math.floor(requestedX), y: Math.floor(requestedY), z: Math.floor(requestedZ), axis, score: requestedScore, adjusted: false };
    return { ...best, adjusted: true };
}

async function clearBlockForPortal(bot, pos) {
    const block = bot.blockAt(pos);
    if (!block || isAirish(block)) return true;
    if (isLiquidOrHardStop(block)) return false;
    if (!block.diggable) return false;
    return await skills.breakBlockAt(bot, pos.x, pos.y, pos.z);
}

async function prepareNetherPortalSite(bot, x, y, z, axis) {
    const fillBlock = choosePortalFillBlock(bot);
    const platform = portalPlatformPositions(x, y, z, axis)
        .sort((a, b) => bot.entity.position.distanceTo(a) - bot.entity.position.distanceTo(b));
    if (fillBlock) {
        for (const p of platform) {
            const existing = bot.blockAt(p);
            if (isSolidFloor(existing)) continue;
            await skills.placeBlock(bot, fillBlock, p.x, p.y, p.z, 'side', true);
        }
    } else {
        log(bot, 'No expendable blocks for portal work platform; will try to use existing terrain only.');
    }

    const clear = portalClearPositions(x, y, z, axis)
        .sort((a, b) => bot.entity.position.distanceTo(a) - bot.entity.position.distanceTo(b));
    for (const p of clear) {
        const ok = await clearBlockForPortal(bot, p);
        if (!ok) {
            const name = blockName(bot.blockAt(p));
            log(bot, `Portal site blocked by ${name} at ${p.x},${p.y},${p.z}.`);
            return false;
        }
    }

    for (const [i, h] of interiorOffsets()) {
        const p = portalPos(x, y, z, axis, i, h);
        const b = bot.blockAt(p);
        if (!isAirish(b)) {
            log(bot, `Portal interior is not clear at ${p.x},${p.y},${p.z}: ${blockName(b)}.`);
            return false;
        }
    }
    return true;
}
/**
 * Build a 4-wide x 5-tall obsidian nether portal frame at (x,y,z) and light it.
 * The (x,y,z) is the bottom-left corner of the frame. Interior 2x3 must be air.
 * axis = 'x' means the portal faces Z (you walk through along Z); 'z' is the opposite.
 * Requires >= 10 obsidian and 1 flint_and_steel in inventory.
 * @param {*} bot
 * @param {number} x
 * @param {number} y bottom row of the frame (the bottom obsidian sits at this y)
 * @param {number} z
 * @param {string} axis 'x' | 'z'
 * @example
 * await portals.buildNetherPortal(bot, p.x+2, p.y, p.z, 'x');
 */
function inspectFrameAt(bot, x, y, z, axis) {
    axis = cleanAxis(axis);
    const missingFrame = [];
    const blockedInterior = [];
    let portalBlocks = 0;
    for (const [i, h] of frameOffsets()) {
        const p = portalPos(x, y, z, axis, i, h);
        const b = bot.blockAt(p);
        if (!b || b.name !== 'obsidian') missingFrame.push({ x: p.x, y: p.y, z: p.z, found: blockName(b) });
    }
    for (const [i, h] of interiorOffsets()) {
        const p = portalPos(x, y, z, axis, i, h);
        const b = bot.blockAt(p);
        if (b && b.name === 'nether_portal') portalBlocks++;
        else if (!isAirish(b)) blockedInterior.push({ x: p.x, y: p.y, z: p.z, found: blockName(b) });
    }
    return {
        x, y, z, axis,
        complete: missingFrame.length === 0,
        lit: portalBlocks > 0,
        missingFrame,
        blockedInterior,
        portalBlocks,
        distance: bot.entity?.position ? bot.entity.position.distanceTo(new Vec3(x + 1.5, y + 1, z + 0.5)) : Infinity,
    };
}

function findNearbyNetherPortalFrame(bot, range = 64) {
    const obsidianPositions = bot.findBlocks({
        matching: (b) => b.name === 'obsidian',
        maxDistance: range,
        count: 256,
    });
    const seen = new Set();
    const candidates = [];
    for (const pos of obsidianPositions) {
        for (const axis of ['x', 'z']) {
            const v = portalVectors(axis);
            for (const [i, h] of frameOffsets()) {
                const x = pos.x - v.dx * i;
                const y = pos.y - h;
                const z = pos.z - v.dz * i;
                const key = axis + ':' + x + ',' + y + ',' + z;
                if (seen.has(key)) continue;
                seen.add(key);
                const frame = inspectFrameAt(bot, x, y, z, axis);
                const frameBlocks = frameOffsets().length - frame.missingFrame.length;
                if (frameBlocks >= 4) candidates.push({ ...frame, frameBlocks });
            }
        }
    }
    candidates.sort((a, b) => {
        if (a.complete !== b.complete) return a.complete ? -1 : 1;
        if (a.lit !== b.lit) return a.lit ? -1 : 1;
        if (a.frameBlocks !== b.frameBlocks) return b.frameBlocks - a.frameBlocks;
        if (a.blockedInterior.length !== b.blockedInterior.length) return a.blockedInterior.length - b.blockedInterior.length;
        return a.distance - b.distance;
    });
    return candidates[0] || null;
}

export function inspectNetherPortal(bot, range = 64) {
    const active = world.getNearestBlock(bot, 'nether_portal', range);
    const frame = findNearbyNetherPortalFrame(bot, range);
    return {
        active: Boolean(active),
        activePos: active?.position || null,
        frame,
        frameBlocks: frame?.frameBlocks || 0,
        frameComplete: Boolean(frame?.complete),
        framePartial: Boolean(frame && !frame.complete),
        frameLit: Boolean(frame?.lit || active),
    };
}

async function lightInspectedFrame(bot, frame) {
    if (!frame?.complete) return false;
    const fns = world.getInventoryCounts(bot)['flint_and_steel'] || 0;
    if (fns < 1) {
        log(bot, 'Found complete obsidian frame but no flint_and_steel to light it.');
        return false;
    }
    const lightPos = portalPos(frame.x, frame.y, frame.z, frame.axis, 1, 1);
    const baseObsidian = portalPos(frame.x, frame.y, frame.z, frame.axis, 1, 0);
    const base = bot.blockAt(baseObsidian);
    if (!base || base.name !== 'obsidian') return false;
    if (frame.blockedInterior?.length) {
        log(bot, 'Complete obsidian frame found, but portal interior is blocked at ' + frame.blockedInterior.map(p => p.x + ',' + p.y + ',' + p.z + '=' + p.found).join('; ') + '.');
        return false;
    }
    await skills.equip(bot, 'flint_and_steel');
    try {
        await skills.goToPosition(bot, lightPos.x, lightPos.y, lightPos.z, 3);
        await bot.lookAt(lightPos.offset(0.5, 0.5, 0.5), true);
        await bot.activateBlock(base, new Vec3(0, 1, 0));
    } catch (_) {
        return false;
    }
    await new Promise(r => setTimeout(r, 800));
    const inspection = inspectNetherPortal(bot, 16);
    if (inspection.active) {
        log(bot, 'Lit existing complete obsidian frame.');
        return true;
    }
    return false;
}

export async function buildNetherPortal(bot, x, y, z, axis = 'x') {
    const obs = world.getInventoryCounts(bot)['obsidian'] || 0;
    if (obs < 10) {
        log(bot, `Need at least 10 obsidian to build a nether portal, have ${obs}.`);
        return false;
    }
    const fns = world.getInventoryCounts(bot)['flint_and_steel'] || 0;
    if (fns < 1) {
        log(bot, `Need a flint_and_steel to light the portal.`);
        return false;
    }

    axis = cleanAxis(axis);
    const existingPortal = inspectNetherPortal(bot, 64);
    if (existingPortal.frame && existingPortal.frameBlocks > 0) {
        x = existingPortal.frame.x;
        y = existingPortal.frame.y;
        z = existingPortal.frame.z;
        axis = existingPortal.frame.axis;
        bot._protectedPortalObsidian = new Set(frameOffsets().map(([i, h]) => portalPos(x, y, z, axis, i, h).toString()));
        log(bot, 'Resuming existing nether portal frame at ' + x + ',' + y + ',' + z + ' axis=' + axis + ' frameBlocks=' + existingPortal.frameBlocks + '/10.');
    }
    const availableObsidian = obs + (existingPortal.frameBlocks || 0);
    if (availableObsidian < 10) {
        log(bot, 'Need 10 total obsidian for nether portal; have ' + obs + ' in inventory plus ' + (existingPortal.frameBlocks || 0) + ' already placed in frame. Existing placed obsidian is protected and should not be mined.');
        return false;
    }
    const fillBlocks = countPortalFillBlocks(bot);
    if (fillBlocks < 32 && !existingPortal.frameComplete) {
        log(bot, 'Need at least 32 expendable support blocks before building/resuming nether portal; have ' + fillBlocks + '. Collect cobblestone/cobbled_deepslate first, then run !buildNetherPortal again. Existing placed obsidian is protected and should not be mined.');
        return false;
    }
    const site = existingPortal.frame ? { x, y, z, axis, score: 0, adjusted: false } : findStablePortalSite(bot, Number(x), Number(y), Number(z), axis);
    x = site.x; y = site.y; z = site.z; axis = site.axis;
    bot._protectedPortalObsidian = new Set(frameOffsets().map(([i, h]) => portalPos(x, y, z, axis, i, h).toString()));
    if (site.adjusted) {
        log(bot, `Adjusted nether portal site to safer area at ${x},${y},${z} axis=${axis} (score ${site.score.toFixed(1)}).`);
    } else {
        log(bot, `Preparing nether portal site at ${x},${y},${z} axis=${axis} (score ${site.score.toFixed(1)}).`);
    }

    const prepared = await prepareNetherPortalSite(bot, x, y, z, axis);
    if (!prepared) {
        log(bot, 'Could not prepare a stable nether portal site. Need a flatter/safer area or more expendable blocks for a work platform.');
        return false;
    }

    for (const [i, h] of frameOffsets()) {
        const p = portalPos(x, y, z, axis, i, h);
        const existing = bot.blockAt(p);
        if (existing && existing.name === 'obsidian') continue;
        const ok = await skills.placeBlock(bot, 'obsidian', p.x, p.y, p.z, 'side', true);
        if (!ok) {
            log(bot, `Failed to place obsidian at ${p.x},${p.y},${p.z}.`);
            return false;
        }
    }

    for (const [i, h] of interiorOffsets()) {
        const p = portalPos(x, y, z, axis, i, h);
        const b = bot.blockAt(p);
        if (!isAirish(b)) {
            const ok = await clearBlockForPortal(bot, p);
            if (!ok) {
                log(bot, `Portal interior blocked before ignition at ${p.x},${p.y},${p.z}: ${blockName(bot.blockAt(p))}.`);
                return false;
            }
        }
    }

    const lightPos = portalPos(x, y, z, axis, 1, 1);
    const baseObsidian = portalPos(x, y, z, axis, 1, 0);
    await skills.equip(bot, 'flint_and_steel');
    const base = bot.blockAt(baseObsidian);
    if (!base || base.name !== 'obsidian') {
        log(bot, `Bottom obsidian missing for ignition at ${baseObsidian.x},${baseObsidian.y},${baseObsidian.z}.`);
        return false;
    }
    try {
        await skills.goToPosition(bot, lightPos.x, lightPos.y, lightPos.z, 3);
        await bot.lookAt(lightPos.offset(0.5, 0.5, 0.5), true);
        await bot.activateBlock(base, new Vec3(0, 1, 0));
    } catch (e) {
        log(bot, `Ignition failed: ${e.message || e}`);
        return false;
    }

    await new Promise(r => setTimeout(r, 1000));
    const inspection = inspectNetherPortal(bot, 16);
    if (inspection.active) {
        log(bot, 'Nether portal verified active at ' + inspection.activePos + '.');
        return true;
    }
    const frame = inspectFrameAt(bot, x, y, z, axis);
    if (frame.complete) {
        log(bot, 'Frame built but did not ignite into an active portal. Interior blocks=' + frame.blockedInterior.map(p => p.found + '@' + p.x + ',' + p.y + ',' + p.z).join('; '));
    } else {
        log(bot, 'Nether portal frame is incomplete after build attempt. Missing frame blocks=' + frame.missingFrame.map(p => p.x + ',' + p.y + ',' + p.z + '=' + p.found).join('; '));
    }
    return false;
}

/**
 * Walk into the nearest nether_portal block, then wait for dimension change.
 * If no active portal exists but an obsidian frame is nearby, attempts to light it.
 * @param {*} bot
 * @example
 * await portals.useNetherPortal(bot);
 */
export async function useNetherPortal(bot) {
    let inspection = inspectNetherPortal(bot, 64);
    let portal = inspection.active ? { position: inspection.activePos } : null;
    if (!portal && inspection.frameComplete) {
        const lit = await lightInspectedFrame(bot, inspection.frame);
        if (lit) {
            inspection = inspectNetherPortal(bot, 16);
            portal = inspection.active ? { position: inspection.activePos } : null;
        }
    }
    if (!portal) {
        if (inspection.frame) {
            log(bot, 'No active nether portal found nearby. Best obsidian frame: complete=' + inspection.frame.complete + ', frameBlocks=' + inspection.frame.frameBlocks + '/10, blockedInterior=' + inspection.frame.blockedInterior.length + '.');
        } else {
            log(bot, 'No active nether portal or recognizable obsidian frame found nearby.');
        }
        return false;
    }
    const fromDim = bot.game?.dimension;
    log(bot, `Entering nether portal at ${portal.position}. Current dim: ${fromDim}.`);
    await skills.goToPosition(bot, portal.position.x, portal.position.y, portal.position.z, 0);

    // Stand still inside the portal block for a few seconds for the transition.
    bot.clearControlStates();
    const newDim = await waitForDimensionChange(bot, fromDim, 15000);
    if (newDim) {
        log(bot, `Dimension changed to ${newDim}.`);
        return true;
    }
    log(bot, `Did not change dimension (still ${bot.game?.dimension}).`);
    return false;
}

/**
 * Locate the nearest end portal structure (frames or active portal) and report it.
 * Returns a small summary in bot.output and {pos, filled, active} on success.
 * @param {*} bot
 * @param {number} range search radius
 * @example
 * await portals.findEndPortal(bot, 128);
 */
export async function findEndPortal(bot, range = 128) {
    const active = world.getNearestBlock(bot, 'end_portal', range);
    if (active) {
        log(bot, `Active end portal at ${active.position}.`);
        return { pos: active.position, filled: 12, active: true };
    }
    const frames = bot.findBlocks({
        matching: (b) => b.name === 'end_portal_frame',
        maxDistance: range,
        count: 12,
    });
    if (!frames || frames.length === 0) {
        log(bot, `No end portal found within ${range} blocks. End portals naturally generate in stronghold libraries underground (use eyes of ender to triangulate).`);
        return null;
    }
    // Center = average of frames
    let cx = 0, cy = 0, cz = 0;
    let filled = 0;
    for (const p of frames) {
        cx += p.x; cy += p.y; cz += p.z;
        const b = bot.blockAt(p);
        if (b && b._properties && b._properties.eye) filled++;
    }
    cx = Math.floor(cx / frames.length);
    cy = Math.floor(cy / frames.length);
    cz = Math.floor(cz / frames.length);
    log(bot, `End portal frames found near ${cx},${cy},${cz}. Filled ${filled}/12. ${filled === 12 ? 'Portal should be active.' : `Need ${12 - filled} more eyes_of_ender.`}`);
    return { pos: new Vec3(cx, cy, cz), filled, active: false };
}

/**
 * Walk into the nearest active end_portal block.
 * Will fill missing eyes if frames are found and bot has ender_eye in inventory.
 * @param {*} bot
 */
export async function enterEndPortal(bot) {
    let portal = world.getNearestBlock(bot, 'end_portal', 64);
    if (!portal) {
        // Try to fill frames with ender_eye.
        const eyes = world.getInventoryCounts(bot)['ender_eye'] || 0;
        const frames = bot.findBlocks({
            matching: (b) => b.name === 'end_portal_frame' && !(b._properties && b._properties.eye),
            maxDistance: 16,
            count: 12,
        });
        if (frames.length > 0 && eyes > 0) {
            log(bot, `Filling ${Math.min(eyes, frames.length)} end portal frames.`);
            await skills.equip(bot, 'ender_eye');
            for (const fp of frames.slice(0, eyes)) {
                const fb = bot.blockAt(fp);
                if (!fb) continue;
                try {
                    await skills.goToPosition(bot, fp.x, fp.y + 1, fp.z, 2);
                    await bot.lookAt(fp.offset(0.5, 1.0, 0.5), true);
                    await bot.activateBlock(fb, new Vec3(0, 1, 0));
                    await new Promise(r => setTimeout(r, 250));
                } catch (e) { /* skip */ }
            }
            await new Promise(r => setTimeout(r, 500));
            portal = world.getNearestBlock(bot, 'end_portal', 16);
        }
    }
    if (!portal) {
        log(bot, `No active end portal found. Use !findEndPortal to locate frames.`);
        return false;
    }
    const fromDim = bot.game?.dimension;
    log(bot, `Entering end portal at ${portal.position}.`);
    await skills.goToPosition(bot, portal.position.x, portal.position.y, portal.position.z, 0);
    const newDim = await waitForDimensionChange(bot, fromDim, 15000);
    if (newDim) {
        log(bot, `Dimension changed to ${newDim}.`);
        return true;
    }
    log(bot, `Did not change dimension.`);
    return false;
}



