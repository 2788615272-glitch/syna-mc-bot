import * as world from './world.js';
import * as portals from './portals.js';
import { countPortalFillBlocks } from './portals.js';
import taskBoard from '../task_board.js';

const PREFIX = '[閫氬叧]';
const VISIBLE_FLUIDS = new Set(['air', 'cave_air', 'water', 'lava']);
const OPPORTUNITY_ORES = ['diamond_ore', 'deepslate_diamond_ore', 'emerald_ore', 'deepslate_emerald_ore', 'lapis_ore', 'deepslate_lapis_ore', 'gold_ore', 'deepslate_gold_ore', 'iron_ore', 'deepslate_iron_ore', 'coal_ore', 'deepslate_coal_ore'];
const ORE_COMMAND_NAME = {
    deepslate_diamond_ore: 'diamond_ore',
    deepslate_emerald_ore: 'emerald_ore',
    deepslate_lapis_ore: 'lapis_ore',
    deepslate_gold_ore: 'gold_ore',
    deepslate_iron_ore: 'iron_ore',
    deepslate_coal_ore: 'coal_ore'
};

export const MC_RUN_TEMPLATE = [
    'Stage 1 survival start: collect wood, make crafting table and basic pickaxes, secure food and bed.',
    'Stage 2 stone to iron: mine stone/coal/iron, smelt iron, craft shield, iron pickaxe, sword, bucket, and priority armor.',
    'Stage 3 diamond/obsidian: obtain diamond pickaxe or enough obsidian, prepare water bucket, flint_and_steel, food, and blocks.',
    'Stage 4 nether: build and enter a verified nether portal, find fortress/bastion, collect blaze rods and ender pearls.',
    'Stage 5 eyes of ender: craft enough eyes, locate stronghold, complete the end portal.',
    'Stage 6 end preparation: bring bow/arrows or blocks, water bucket, food, beds or safe combat gear.',
    'Stage 7 ender dragon: enter the End, destroy crystals, defeat the dragon.'
];

function count(inv, names) {
    for (const name of names) {
        const n = inv[name] || inv[name.replace(/^minecraft:/, '')] || 0;
        if (n) return n;
    }
    return 0;
}

function hasAny(inv, names) {
    return count(inv, names) > 0;
}

function dist(bot, block) {
    try { return bot.entity.position.distanceTo(block.position); }
    catch (_) { return Infinity; }
}

function isExposedBlock(bot, block) {
    if (!block?.position) return false;
    const offsets = [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]];
    return offsets.some(([x, y, z]) => {
        const near = bot.blockAt(block.position.offset(x, y, z));
        return near && VISIBLE_FLUIDS.has(near.name);
    });
}

function findOpportunity(agent) {
    const bot = agent.bot;
    if (!bot?.entity?.position) return null;
    const chest = world.getNearestBlock(bot, 'chest', 8);
    if (chest && isExposedBlock(bot, chest)) {
        return { kind: 'chest', command: '!viewChest()', reason: 'visible nearby chest; inspect it before continuing the route', detail: 'chest d=' + dist(bot, chest).toFixed(1) };
    }
    const spawner = world.getNearestBlock(bot, 'spawner', 10);
    if (spawner && isExposedBlock(bot, spawner)) {
        return {
            kind: 'spawner',
            command: '!searchForBlock("spawner", 32)',
            reason: 'visible monster spawner nearby; decide whether to break it or place torches before mobs build up',
            detail: 'spawner d=' + dist(bot, spawner).toFixed(1)
        };
    }
    const ores = world.getNearestBlocks(bot, OPPORTUNITY_ORES, 8, 16)
        .filter(block => block && isExposedBlock(bot, block))
        .sort((a, b) => dist(bot, a) - dist(bot, b));
    if (ores.length) {
        const name = ores[0].name;
        const commandName = ORE_COMMAND_NAME[name] || name;
        return { kind: 'ore', command: '!collectBlocks("' + commandName + '", 1)', reason: 'nearby exposed ore is already visible; opportunistically mine it before walking away', detail: name + ' d=' + dist(bot, ores[0]).toFixed(1) };
    }
    return null;
}

function recentlyFailed(agent, commandPrefix, withinMs = 45000) {
    const failures = agent?._recentMcRunFailures;
    if (!failures) return false;
    const now = Date.now();
    for (const [key, value] of failures.entries()) {
        if (now - (value?.time || 0) > withinMs) {
            failures.delete(key);
            continue;
        }
        if (String(key).includes(commandPrefix)) return true;
    }
    return false;
}

function gearSummary(inv) {
    const tools = [];
    for (const item of ['wooden_pickaxe', 'stone_pickaxe', 'iron_pickaxe', 'diamond_pickaxe', 'iron_sword', 'diamond_sword', 'shield', 'bucket', 'water_bucket', 'flint_and_steel', 'bow']) {
        if (inv[item]) tools.push(item);
    }
    const armor = ['leather', 'golden', 'chainmail', 'iron', 'diamond', 'netherite'].flatMap(prefix =>
        ['helmet', 'chestplate', 'leggings', 'boots'].map(slot => prefix + '_' + slot).filter(item => inv[item])
    );
    return { tools, armor };
}

function summarizeNetherPortal(agent, range = 64) {
    try {
        const inspection = portals.inspectNetherPortal(agent.bot, range);
        if (inspection.active) return { ...inspection, text: 'active at ' + inspection.activePos };
        if (inspection.frame) {
            const frame = inspection.frame;
            return {
                ...inspection,
                text: 'no_active; best_frame complete=' + frame.complete + ' frameBlocks=' + frame.frameBlocks + '/10 blockedInterior=' + frame.blockedInterior.length + ' at=' + frame.x + ',' + frame.y + ',' + frame.z + ' axis=' + frame.axis,
            };
        }
        return { ...inspection, text: 'none nearby' };
    } catch (err) {
        return { active: false, frame: null, frameComplete: false, frameLit: false, text: 'unknown (' + (err?.message || err) + ')' };
    }
}


export function ensureMcRunPlan(options = {}) {
    const replace = options.replace !== false;
    if (replace) taskBoard.clear({ kind: 'mc_run' });
    const existing = getMcRunTasks();
    if (existing.length && !replace) return existing;
    return taskBoard.addMany(MC_RUN_TEMPLATE.map((text, i) => PREFIX + ' #' + (i + 1) + ' ' + text), { kind: 'mc_run', phase_total: MC_RUN_TEMPLATE.length });
}

export function getMcRunTasks() {
    return taskBoard.list().filter(t => t?.kind === 'mc_run' || String(t.text || '').startsWith(PREFIX));
}
export function summarizeMcRunStatus(agent) {
    const bot = agent.bot;
    const inv = world.getInventoryCounts(bot);
    const pos = bot.entity?.position;
    const dim = bot.game?.dimension || 'unknown';
    const health = Math.round(bot.health ?? 0);
    const food = Math.round(bot.food ?? 0);
    const gear = gearSummary(inv);
    const opportunity = findOpportunity(agent);
    const tasks = getMcRunTasks();
    const done = tasks.filter(t => t.done).length;
    const total = tasks.length;
    const wood = count(inv, ['oak_log', 'birch_log', 'spruce_log', 'jungle_log', 'acacia_log', 'dark_oak_log', 'mangrove_log', 'cherry_log', 'crimson_stem', 'warped_stem', 'planks', 'oak_planks']);
    const y = Math.floor(bot.entity?.position?.y ?? 999);
    const underground = y < 55 && String(bot.game?.dimension || '').includes('overworld');
    const foodItems = count(inv, ['bread', 'cooked_beef', 'cooked_porkchop', 'cooked_chicken', 'cooked_mutton', 'baked_potato', 'beef', 'porkchop', 'chicken']);
    const iron = count(inv, ['iron_ingot']);
    const rawIron = count(inv, ['raw_iron', 'iron_ore', 'deepslate_iron_ore']);
    const diamonds = count(inv, ['diamond']);
    const obsidian = count(inv, ['obsidian']);
    const nearbyObsidian = world.getNearestBlocksWhere(bot, block => block.name === 'obsidian', 64, 32).length;
    const netherPortal = summarizeNetherPortal(agent, 64);
    const fillBlocks = countPortalFillBlocks(bot);
    const blazeRods = count(inv, ['blaze_rod']);
    const blazePowder = count(inv, ['blaze_powder']);
    const pearls = count(inv, ['ender_pearl']);
    const eyes = count(inv, ['ender_eye']);
    const beds = count(inv, ['white_bed', 'orange_bed', 'magenta_bed', 'light_blue_bed', 'yellow_bed', 'lime_bed', 'pink_bed', 'gray_bed', 'light_gray_bed', 'cyan_bed', 'purple_bed', 'blue_bed', 'brown_bed', 'green_bed', 'red_bed', 'black_bed']);

    const missing = [];
    if (wood < 8 && !hasAny(inv, ['crafting_table'])) missing.push('wood/crafting table');
    if (!hasAny(inv, ['stone_pickaxe', 'iron_pickaxe', 'diamond_pickaxe'])) missing.push('stone pickaxe or better');
    if (foodItems < 8) missing.push('reliable food');
    if (iron < 12 && rawIron < 12 && !hasAny(inv, ['iron_pickaxe', 'diamond_pickaxe'])) missing.push('iron supply');
    if (!hasAny(inv, ['shield'])) missing.push('shield');
    if (!hasAny(inv, ['bucket', 'water_bucket'])) missing.push('bucket/water bucket');
    if (obsidian < 10 && !hasAny(inv, ['diamond_pickaxe'])) missing.push('obsidian or diamond pickaxe route');
    if (!hasAny(inv, ['flint_and_steel'])) missing.push('flint and steel');
    if (blazeRods < 6 && blazePowder < 12) missing.push('blaze rods');
    if (pearls + eyes < 12) missing.push('ender pearls/eyes');
    if (beds < 3 && dim.includes('end')) missing.push('beds or safe dragon gear');

    let phase = 'survival_start';
    if (dim.includes('the_end') || dim.includes('end')) phase = 'end_dragon';
    else if (dim.includes('nether')) phase = 'nether_resources';
    else if (eyes >= 10) phase = 'stronghold_end_portal';
    else if (blazeRods >= 6 || blazePowder >= 12) phase = 'pearls_and_eyes';
    else if (obsidian >= 10 || hasAny(inv, ['diamond_pickaxe'])) phase = 'prepare_nether_portal';
    else if (hasAny(inv, ['iron_pickaxe']) || iron >= 3) phase = 'iron_diamond_obsidian';
    else if (hasAny(inv, ['stone_pickaxe'])) phase = 'stone_to_iron';

    const lines = [
        'MC_RUN_STATUS',
        'phase=' + phase,
        'dimension=' + dim,
        'position=' + (pos ? [pos.x.toFixed(1), pos.y.toFixed(1), pos.z.toFixed(1)].join(',') : 'unknown'),
        'health=' + health + '/20 food=' + food + '/20',
        'plan=' + done + '/' + total + ' checked',
        'key_items wood=' + wood + ' food=' + foodItems + ' iron=' + iron + ' raw_iron=' + rawIron + ' diamonds=' + diamonds + ' obsidian=' + obsidian + ' nearby_obsidian=' + nearbyObsidian + ' blaze_rods=' + blazeRods + ' pearls=' + pearls + ' eyes=' + eyes + ' beds=' + beds,
        'nether_portal=' + netherPortal.text,
        'portal_support_blocks=' + fillBlocks + '/32',
        'gear tools=' + (gear.tools.join(',') || 'none') + ' armor=' + (gear.armor.join(',') || 'none'),
        'missing=' + (missing.join(', ') || 'none obvious'),
        opportunity ? (opportunity.kind === 'ore' ? ('opportunity_advisory=' + opportunity.kind + ' ' + opportunity.detail + ' optional_command=' + opportunity.command + ' reason=' + opportunity.reason + '; advisory only, do not interrupt the main MC_RUN requirement unless this ore directly satisfies the current missing item') : ('opportunity=' + opportunity.kind + ' ' + opportunity.detail + ' suggested=' + opportunity.command + ' reason=' + opportunity.reason)) : 'opportunity=none nearby visible',
        'Use the checklist as a fixed backbone, but choose the next executable command from current missing items and environment. Mark a checklist item done only when its real requirement is satisfied.'
    ];
    return lines.join('\n');
}

export function suggestMcRunAction(agent) {
    const bot = agent.bot;
    const inv = world.getInventoryCounts(bot);
    const foodItems = count(inv, ['bread', 'cooked_beef', 'cooked_porkchop', 'cooked_chicken', 'cooked_mutton', 'baked_potato', 'beef', 'porkchop', 'chicken', 'wheat']);
    const iron = count(inv, ['iron_ingot']);
    const rawIron = count(inv, ['raw_iron', 'iron_ore', 'deepslate_iron_ore']);
    const flint = count(inv, ['flint']);
    const wood = count(inv, ['oak_log', 'birch_log', 'spruce_log', 'jungle_log', 'acacia_log', 'dark_oak_log', 'mangrove_log', 'cherry_log', 'crimson_stem', 'warped_stem', 'planks', 'oak_planks']);
    const nearbyObsidian = world.getNearestBlocksWhere(bot, block => block.name === 'obsidian', 64, 32).length;
    const netherPortal = summarizeNetherPortal(agent, 64);
    const fillBlocks = countPortalFillBlocks(bot);
    const y = Math.floor(bot.entity?.position?.y ?? 999);
    const underground = y < 55 && String(bot.game?.dimension || '').includes('overworld');
    const cowRecentlyFailed = recentlyFailed(agent, '!searchForEntity("cow"');

    const opportunity = findOpportunity(agent);
    if (opportunity && (opportunity.kind === 'chest' || opportunity.kind === 'spawner')) {
        return { command: opportunity.command, reason: opportunity.reason + ' (' + opportunity.detail + ')' };
    }

    if (!hasAny(inv, ['bucket', 'water_bucket']) && iron >= 3) {
        return { command: '!craftRecipe("bucket", 1)', reason: 'has 3 iron and no bucket' };
    }
    if (!hasAny(inv, ['shield']) && iron >= 1 && (wood > 0 || hasAny(inv, ['oak_planks', 'planks']))) {
        return { command: '!craftRecipe("shield", 1)', reason: 'has iron/wood and no shield' };
    }

    const hunger = Math.round(bot.food ?? 0);
    const foodCritical = hunger < 8 && foodItems < 1;
    const foodUrgent = hunger < 14 && foodItems < 4;
    const foodStillLow = hunger < 18 && foodItems < 2;
    if (foodCritical) {
        return { command: (underground || cowRecentlyFailed) ? '!goToSurface()' : '!searchForEntity("cow", 96)', reason: 'food is critical; surface or find food immediately' };
    }
    if (!underground && !cowRecentlyFailed && (foodUrgent || foodStillLow)) {
        return { command: '!searchForEntity("cow", 96)', reason: 'reliable food is low and bot is on/near surface; move toward food first' };
    }

    if (!hasAny(inv, ['flint_and_steel']) && iron >= 1) {
        if (flint > 0) return { command: '!craftRecipe("flint_and_steel", 1)', reason: 'has iron and flint; craft flint and steel' };
        return { command: '!gatherFlint(1, 40)', reason: 'needs flint for flint and steel; gravel drops flint by chance, so use the dedicated gravel place/break loop' };
    }

    const totalPortalObsidian = count(inv, ['obsidian']) + (netherPortal.frameBlocks || 0);
    if (netherPortal.framePartial && fillBlocks < 32) {
        return { command: '!collectBlocks("cobblestone", ' + Math.max(1, 32 - fillBlocks) + ')', reason: 'partial nether portal frame exists; collect support blocks before mining or resuming so placed obsidian stays protected' };
    }
    if (String(bot.game?.dimension || '').includes('overworld')) {
        if (netherPortal.active) {
            return { command: '!useNetherPortal()', reason: 'verified active nether_portal block exists nearby; enter it' };
        }
        if (netherPortal.frameComplete && hasAny(inv, ['flint_and_steel'])) {
            return { command: '!useNetherPortal()', reason: 'complete obsidian frame exists but no active portal block; use command will light then enter only if activation is verified' };
        }
        if (totalPortalObsidian >= 10 && hasAny(inv, ['flint_and_steel'])) {
            if (fillBlocks < 32) {
                return { command: '!collectBlocks("cobblestone", ' + Math.max(1, 32 - fillBlocks) + ')', reason: 'portal frame has enough obsidian total, but needs 32 support blocks before resuming construction; do not mine placed obsidian' };
            }
            const p = bot.entity?.position;
            const x = netherPortal.frame?.x ?? (Math.floor(p?.x ?? 0) + 2);
            const y0 = netherPortal.frame?.y ?? Math.floor(p?.y ?? 0);
            const z = netherPortal.frame?.z ?? Math.floor(p?.z ?? 0);
            const axis = netherPortal.frame?.axis || 'x';
            return { command: '!buildNetherPortal(' + x + ', ' + y0 + ', ' + z + ', "' + axis + '")', reason: 'resume/build verified portal frame; placed obsidian is protected' };
        }
    }

    if (underground && hasAny(inv, ['diamond_pickaxe']) && totalPortalObsidian < 10 && (nearbyObsidian > 0 || hasAny(inv, ['water_bucket', 'bucket']))) {
        return { command: '!makeObsidian(10, 64)', reason: nearbyObsidian > 0 ? ('need more obsidian; mineable nearby obsidian=' + nearbyObsidian + ' after protecting partial portal frames') : 'has diamond pickaxe and bucket route; make and mine obsidian from nearby lava safely' };
    }
    if (!underground && wood < 8 && !hasAny(inv, ['crafting_table'])) {
        return { command: '!collectBlocks("oak_log", 4)', reason: 'food bar is stable enough; resume wood/crafting-table backbone' };
    }
    if (rawIron > 0) {
        return { command: '!smeltItem("raw_iron", 1)', reason: 'raw iron is available' };
    }
    if (!hasAny(inv, ['diamond_pickaxe']) && hasAny(inv, ['iron_pickaxe'])) {
        return { command: '!searchForBlock("diamond_ore", 128)', reason: 'has iron pickaxe; start diamond route' };
    }
    return { command: (underground || cowRecentlyFailed) ? '!goToSurface()' : '!searchForEntity("cow", 96)', reason: (underground || cowRecentlyFailed) ? 'avoid repeating failed cow search; surface to get food/wood and reset route' : 'default MC-run nudge: move toward food/resource progress' };
}
export function buildMcRunGoalPrompt(agent) {
    ensureMcRunPlan({ replace: false });
    const status = summarizeMcRunStatus(agent);
    const pending = taskBoard.getPendingText();
    const suggested = suggestMcRunAction(agent);
    return [
        '[Syna MC run skill] Advance from the current Minecraft state toward defeating the Ender Dragon.',
        'Core strategy: use the fixed checklist as the backbone, but choose each next step from current state. Do not assume resources or portals exist; verify with !mcRunStatus / !inventory / !nearbyBlocks / !entities.',
        'Each round should be one small loop: inspect state -> choose the current gap -> execute one command -> update the task board from real results.',
        'Priority: survive > food/bed > tools > iron/shield/bucket > obsidian/nether portal > blaze rods > pearls/eyes > stronghold > End.',
        'In danger, use !consume, !equip, !moveAway, or !goToBed first.',
        'Only mark checklist items done when the real requirement is satisfied. For portals, require an active portal block or a verified complete frame, not a guessed statement.',
        'Available commands include !collectBlocks, !gatherFlint, !craftRecipe, !smeltItem, !attack, !searchForBlock, !makeObsidian, !buildNetherPortal, !useNetherPortal, !findEndPortal, !enterEndPortal.',
        'If a normal command cannot perform a complex step, use !newAction with one small, specific action.',
        status,
        suggested?.command ? ('SUGGESTED_NEXT_ACTION=' + suggested.command + ' ; reason=' + suggested.reason + '. If unsure, use exactly this command instead of another status query.') : '',
        pending ? ('[Current MC run task board]\n' + pending) : '[Current MC run task board] empty; call !mcRunPlan first.',
        'Your next response must contain one command. Keep public speech short; routine execution should usually be silent.'
    ].join('\n');
}



