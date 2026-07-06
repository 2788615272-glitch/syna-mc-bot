import * as world from '../library/world.js';
import * as mc from '../../utils/mcdata.js';
import { getCommandDocs } from './index.js';
import convoManager from '../conversation.js';
import { checkLevelBlueprint, checkBlueprint } from '../tasks/construction_tasks.js';
import { load } from 'cheerio';
import taskBoard from '../task_board.js';
import { resolveModEntity } from '../mod_registry.js';
import { getModBlueprintClient } from '../library/mod_blueprint_client.js';
import { ensureMcRunPlan, summarizeMcRunStatus } from '../library/mc_run_planner.js';

const pad = (str) => {
    return '\n' + str + '\n';
}

// queries are commands that just return strings and don't affect anything in the world
export const queryList = [
    {
        name: '!synaWait',
        description: 'Do nothing for this turn. Use when Syna notices something internally but chooses not to speak or act.',
        params: {
            'reason': { type: 'string', description: 'Short private reason for staying quiet.', optional: true }
        },
        perform: function (_agent, reason = '') {
            return reason ? `SYNA_WAIT quiet_reason=${reason}` : 'SYNA_WAIT';
        }
    },
    {
        name: "!stats",
        description: "Get your bot's location, health, hunger, and time of day.", 
        perform: function (agent) {
            let bot = agent.bot;
            let res = 'STATS';
            let pos = bot.entity.position;
            // display position to 2 decimal places
            res += `\n- Position: x: ${pos.x.toFixed(2)}, y: ${pos.y.toFixed(2)}, z: ${pos.z.toFixed(2)}`;
            // Gameplay
            res += `\n- Gamemode: ${bot.game.gameMode}`;
            res += `\n- Health: ${Math.round(bot.health)} / 20`;
            res += `\n- Hunger: ${Math.round(bot.food)} / 20`;
            res += `\n- Biome: ${world.getBiomeName(bot)}`;
            let weather = "Clear";
            if (bot.rainState > 0)
                weather = "Rain";
            if (bot.thunderState > 0)
                weather = "Thunderstorm";
            res += `\n- Weather: ${weather}`;
            // let block = bot.blockAt(pos);
            // res += `\n- Artficial light: ${block.skyLight}`;
            // res += `\n- Sky light: ${block.light}`;
            // light properties are bugged, they are not accurate


            if (bot.time.timeOfDay < 6000) {
                res += '\n- Time: Morning';
            } else if (bot.time.timeOfDay < 12000) {
                res += '\n- Time: Afternoon';
            } else {
                res += '\n- Time: Night';
            }

            // get the bot's current action
            let action = agent.actions.currentActionLabel;
            if (agent.isIdle())
                action = 'Idle';
            res += `\- Current Action: ${action}`;


            let players = world.getNearbyPlayerNames(bot);
            let bots = convoManager.getInGameAgents().filter(b => b !== agent.name);
            players = players.filter(p => !bots.includes(p));

            res += '\n- Nearby Human Players: ' + (players.length > 0 ? players.join(', ') : 'None.');
            res += '\n- Nearby Bot Players: ' + (bots.length > 0 ? bots.join(', ') : 'None.');

            res += '\n' + agent.bot.modes.getMiniDocs() + '\n';
            return pad(res);
        }
    },
    {
        name: "!inventory",
        description: "Get your bot's inventory.",
        perform: function (agent) {
            let bot = agent.bot;
            let inventory = world.getInventoryCounts(bot);
            let res = 'INVENTORY';
            for (const item in inventory) {
                if (inventory[item] && inventory[item] > 0)
                    res += `\n- ${item}: ${inventory[item]}`;
            }
            if (res === 'INVENTORY') {
                res += ': Nothing';
            }
            else if (agent.bot.game.gameMode === 'creative') {
                res += '\n(You have infinite items in creative mode. You do not need to gather resources!!)';
            }

            let helmet = bot.inventory.slots[5];
            let chestplate = bot.inventory.slots[6];
            let leggings = bot.inventory.slots[7];
            let boots = bot.inventory.slots[8];
            res += '\nWEARING: ';
            if (helmet)
                res += `\nHead: ${helmet.name}`;
            if (chestplate)
                res += `\nTorso: ${chestplate.name}`;
            if (leggings)
                res += `\nLegs: ${leggings.name}`;
            if (boots)
                res += `\nFeet: ${boots.name}`;
            if (!helmet && !chestplate && !leggings && !boots)
                res += 'Nothing';

            return pad(res);
        }
    },
    {
        name: "!nearbyBlocks",
        description: "Get the blocks near the bot.",
        perform: function (agent) {
            let bot = agent.bot;
            let res = 'NEARBY_BLOCKS';
            let blocks = world.getNearestBlocks(bot);
            let block_details = new Set();
            
            for (let block of blocks) {
                let details = block.name;
                if (block.name === 'water' || block.name === 'lava') {
                    details += block.metadata === 0 ? ' (source)' : ' (flowing)';
                }
                block_details.add(details);
            }
            for (let details of block_details) {
                res += `\n- ${details}`;
            }
            if (block_details.size === 0) {
                res += ': none';
            } 
            else {
                res += '\n- ' + world.getSurroundingBlocks(bot).join('\n- ');
                res += `\n- First Solid Block Above Head: ${world.getFirstBlockAboveHead(bot, null, 32)}`;
            }
            return pad(res);
        }
    },
    {
        name: "!craftable",
        description: "Get the craftable items with the bot's inventory.",
        perform: function (agent) {
            let craftable = world.getCraftableItems(agent.bot);
            let res = 'CRAFTABLE_ITEMS';
            for (const item of craftable) {
                res += `\n- ${item}`;
            }
            if (res == 'CRAFTABLE_ITEMS') {
                res += ': none';
            }
            return pad(res);
        }
    },
    {
        name: "!entities",
        description: "Get the nearby players and entities.",
        perform: async function (agent) {
            let bot = agent.bot;
            let res = 'NEARBY_ENTITIES';
            let players = world.getNearbyPlayerNames(bot);
            let bots = convoManager.getInGameAgents().filter(b => b !== agent.name);
            players = players.filter(p => !bots.includes(p));

            for (const player of players) {
                res += `\n- Human player: ${player}`;
            }
            for (const bot of bots) {
                res += `\n- Bot player: ${bot}`;
            }

            let nearbyEntities = world.getNearbyEntities(bot);
            let entityCounts = {};
            let villagerIds = [];
            let babyVillagerIds = [];
            let villagerDetails = []; // Store detailed villager info including profession
            
            for (const entity of nearbyEntities) {
                if (entity.type === 'player' || entity.name === 'item')
                    continue;

                // Mod entity fallback: mineflayer may report unknown entity names.
                let eName = entity.name;
                if (!eName || eName === 'unknown' || eName === 'Unknown') {
                    const resolved = resolveModEntity(entity.entityType);
                    if (resolved) eName = resolved.name; // e.g. "twilightforest:naga"
                    else eName = `unknown(type=${entity.entityType})`;
                }
                    
                if (!entityCounts[eName]) {
                    entityCounts[eName] = 0;
                }
                entityCounts[eName]++;
                
                if (entity.name === 'villager') {
                    if (entity.metadata && entity.metadata[16] === 1) {
                        babyVillagerIds.push(entity.id);
                    } else {
                        const profession = world.getVillagerProfession(entity);
                        villagerIds.push(entity.id);
                        villagerDetails.push({
                            id: entity.id,
                            profession: profession
                        });
                    }
                }
            }
            
            for (const [entityType, count] of Object.entries(entityCounts)) {
                if (entityType === 'villager') {
                    let villagerInfo = `${count} ${entityType}(s)`;
                    if (villagerDetails.length > 0) {
                        const detailStrings = villagerDetails.map(v => `(${v.id}:${v.profession})`);
                        villagerInfo += ` - Adults: ${detailStrings.join(', ')}`;
                    }
                    if (babyVillagerIds.length > 0) {
                        villagerInfo += ` - Baby IDs: ${babyVillagerIds.join(', ')} (babies cannot trade)`;
                    }
                    res += `\n- entities: ${villagerInfo}`;
                } else {
                    res += `\n- entities: ${count} ${entityType}(s)`;
                }
            }

            const client = getModBlueprintClient();
            const pos = agent.bot.entity?.position;
            const scanned = await client.scanEntities('', { radius: 96, count: 24, fromPos: pos });
            if (scanned?.ok !== false && Array.isArray(scanned?.matches) && scanned.matches.length) {
                const seenLocal = new Set(Object.keys(entityCounts).map(name => String(name).toLowerCase()));
                const extras = scanned.matches
                    .filter(m => m?.name)
                    .filter(m => !seenLocal.has(String(m.name).replace(/^minecraft:/, '').toLowerCase()))
                    .slice(0, 8);
                if (extras.length) {
                    res += `\n- server scan (${scanned.radius || 96} blocks): `
                        + extras.map(m => `${m.name}${m.display ? ' [' + m.display + ']' : ''} d=${Number(m.distance || 0).toFixed(1)}`).join('; ');
                }
            }
            
            if (res == 'NEARBY_ENTITIES') {
                res += ': none';
            }
            return pad(res);
        }
    },
    {
        name: "!modes",
        description: "Get all available modes and their docs and see which are on/off.",
        perform: function (agent) {
            return agent.bot.modes.getDocs();
        }
    },
    {
        name: '!savedPlaces',
        description: 'List all saved locations.',
        perform: async function (agent) {
            return "Saved place names: " + agent.memory_bank.getKeys();
        }
    }, 
    {
        name: '!checkBlueprintLevel',
        description: 'Check if the level is complete and what blocks still need to be placed for the blueprint',
        params: {
            'levelNum': { type: 'int', description: 'The level number to check.', domain: [0, Number.MAX_SAFE_INTEGER] }
        },
        perform: function (agent, levelNum) {
            let res = checkLevelBlueprint(agent, levelNum);
            console.log(res);
            return pad(res);
        }
    }, 
    {
        name: '!checkBlueprint',
        description: 'Check what blocks still need to be placed for the blueprint',
        perform: function (agent) {
            let res = checkBlueprint(agent);
            return pad(res);
        }
    }, 
    {
        name: '!getBlueprint',
        description: 'Get the blueprint for the building',
        perform: function (agent) {
            let res = agent.task.blueprint.explain();
            return pad(res);
        }
    }, 
    {
        name: '!getBlueprintLevel',
        description: 'Get the blueprint for the building',
        params: {
            'levelNum': { type: 'int', description: 'The level number to check.', domain: [0, Number.MAX_SAFE_INTEGER] }
        },
        perform: function (agent, levelNum) {
            let res = agent.task.blueprint.explainLevel(levelNum);
            console.log(res);
            return pad(res);
        }
    },
    {
        name: '!getCraftingPlan',
        description: "Provides a comprehensive crafting plan for a specified item. This includes a breakdown of required ingredients, the exact quantities needed, and an analysis of missing ingredients or extra items needed based on the bot's current inventory.",
        params: {
            targetItem: { 
                type: 'string', 
                description: 'The item that we are trying to craft' 
            },
            quantity: { 
                type: 'int',
                description: 'The quantity of the item that we are trying to craft',
                optional: true,
                domain: [1, Infinity, '[)'], // Quantity must be at least 1,
                default: 1
            }
        },
        perform: function (agent, targetItem, quantity = 1) {
            let bot = agent.bot;

            // Fetch the bot's inventory
            const curr_inventory = world.getInventoryCounts(bot); 
            const target_item = targetItem;
            let existingCount = curr_inventory[target_item] || 0;
            let prefixMessage = '';
            if (existingCount > 0) {
                curr_inventory[target_item] -= existingCount;
                prefixMessage = `You already have ${existingCount} ${target_item} in your inventory. If you need to craft more,\n`;
            }

            // Generate crafting plan
            try {
                let craftingPlan = mc.getDetailedCraftingPlan(target_item, quantity, curr_inventory);
                craftingPlan = prefixMessage + craftingPlan;
                return pad(craftingPlan);
            } catch (error) {
                console.error("Error generating crafting plan:", error);
                return `An error occurred while generating the crafting plan: ${error.message}`;
            }
            
            
        },
    },
    {
        name: '!modFindBlock',
        description: 'Use the SynaBridge Forge mod to find blocks in a large server-side radius, beyond Mineflayer client chunk vision. Use this before asking the player for help finding resources.',
        params: {
            'block_name': { type: 'string', description: 'Block name or comma-separated block names, e.g. short_grass,grass,tall_grass or iron_ore.' },
            'radius': { type: 'int', description: 'Search radius in blocks. Default 96, max 160.', optional: true, domain: [1, 161] },
            'count': { type: 'int', description: 'Maximum results. Default 12, max 64.', optional: true, domain: [1, 65] }
        },
        perform: async function (agent, block_name, radius = 96, count = 12) {
            const client = getModBlueprintClient();
            const pos = agent.bot.entity.position;
            const res = await client.scanBlocks(block_name, { radius, count, fromPos: pos });
            if (!res) return `Mod scan failed: ${client.lastError || 'no response'}`;
            if (res.ok === false) return `Mod scan error: ${res.error || JSON.stringify(res)}`;
            const matches = res.matches || [];
            if (!matches.length) return `No ${block_name} found within ${res.radius || radius} blocks. scanned=${res.scanned ?? '?'} skipped_unloaded=${res.skipped_unloaded ?? '?'}`;
            const lines = matches.slice(0, count).map((m, i) => `${i + 1}. ${m.name} at (${m.x}, ${m.y}, ${m.z}), d=${Number(m.distance || 0).toFixed(1)}`);
            return pad(`MOD_BLOCK_SCAN ${block_name} radius=${res.radius || radius} matched=${res.matched}\n${lines.join('\n')}`);
        }
    },
    {
        name: '!modFindEntity',
        description: 'Use the SynaBridge Forge mod to find entities in a large server-side radius, beyond Mineflayer client entity vision.',
        params: {
            'entity_name': { type: 'string', description: 'Entity type/name, e.g. cow, zombie, minecraft:villager. Empty string lists nearby entities.', optional: true },
            'radius': { type: 'int', description: 'Search radius in blocks. Default 96, max 160.', optional: true, domain: [1, 161] },
            'count': { type: 'int', description: 'Maximum results. Default 12, max 64.', optional: true, domain: [1, 65] }
        },
        perform: async function (agent, entity_name = '', radius = 96, count = 12) {
            const client = getModBlueprintClient();
            const pos = agent.bot.entity.position;
            const res = await client.scanEntities(entity_name, { radius, count, fromPos: pos });
            if (!res) return `Mod scan failed: ${client.lastError || 'no response'}`;
            if (res.ok === false) return `Mod scan error: ${res.error || JSON.stringify(res)}`;
            const matches = res.matches || [];
            if (!matches.length) return `No ${entity_name || 'entities'} found within ${res.radius || radius} blocks.`;
            const lines = matches.slice(0, count).map((m, i) => `${i + 1}. ${m.name}${m.display ? ' [' + m.display + ']' : ''} at (${m.x}, ${m.y}, ${m.z}), d=${Number(m.distance || 0).toFixed(1)}`);
            return pad(`MOD_ENTITY_SCAN ${entity_name || '*'} radius=${res.radius || radius} matched=${res.matched}\n${lines.join('\n')}`);
        }
    },
    {
        name: '!synaHorrorTakeover',
        description: 'Let Syna enter her second form at the Mineflayer bot position for horror roleplay. This keeps the LLM online, hides the normal Mineflayer Syna, spawns/moves Horror Syna to the bot position, then starts takeover/countdown/hunt using an anger value. Use only when intentionally entering horror mode.',
        params: {
            'action': { type: 'string', description: 'takeover, countdown, or hunt.' },
            'player_name': { type: 'string', description: 'Target player name.' },
            'anger': { type: 'int', description: 'Anger value 0-240. 130 starts countdown, 180 starts hunt. Default 160.', optional: true, domain: [0, 241] }
        },
        perform: async function (agent, action = 'takeover', player_name = '', anger = 160) {
            const client = getModBlueprintClient();
            try {
                await agent.actions.stop();
                agent.bot.pathfinder?.stop?.();
                agent.bot.pvp?.stop?.();
                agent.bot.stopDigging?.();
            } catch { }
            const pos = agent.bot.entity?.position;
            if (!pos) return 'Syna horror takeover failed: bot position unavailable.';
            const spawned = await client.spawnSynaAt(pos);
            if (!spawned || spawned.ok === false) return `Syna horror takeover spawn failed: ${client.lastError || spawned?.error || 'no response'}`;
            const normalized = String(action || 'takeover').toLowerCase();
            const mode = normalized === 'hunt' ? 'hunt' : normalized === 'countdown' ? 'countdown' : 'takeover';
            const safeAnger = Number.isFinite(Number(anger)) ? Math.max(0, Math.min(240, Math.floor(Number(anger)))) : 160;
            const res = await client.horror(mode, player_name, { anger: safeAnger, ownerName: agent.name });
            if (!res || res.ok === false) return `Syna horror takeover mode failed: ${client.lastError || res?.error || 'no response'}`;
            return pad(`SYNA_HORROR_TAKEOVER active mode=${mode} anger=${safeAnger} target=${player_name || '(current target)'}`);
        }
    },    {
        name: '!synaHorror',
        description: 'Control or inspect the SynaBridge horror mode. Use forgive/stop/calm when the player begs for mercy or the scary behavior should end. Use status to inspect current horror state. Use warn/countdown/hunt/takeover or set_anger only when intentionally roleplaying horror.',
        params: {
            'action': { type: 'string', description: 'status, forgive, stop, calm, warn, countdown, hunt, takeover, or set_anger.' },
            'player_name': { type: 'string', description: 'Target player name for warn/countdown/hunt/takeover. Optional for status/forgive.', optional: true },
            'anger': { type: 'int', description: 'Optional anger value 0-240. 30 warning, 70 storm, 130 countdown, 180 hunt.', optional: true, domain: [0, 241] },
            'reason': { type: 'string', description: 'Optional anger key/reason or player confession guess. Use with key/reason/guess actions.', optional: true }
        },
        perform: async function (agent, action = 'status', player_name = '', anger = null, reason = '') {
            const client = getModBlueprintClient();
            const res = await client.horror(action, player_name, { ensureSpawnAt: agent.bot.entity?.position, anger, ownerName: agent.name, reason });
            if (!res) return `Syna horror command failed: ${client.lastError || 'no response'}`;
            if (res.ok === false) return `Syna horror error: ${res.error || JSON.stringify(res)}`;
            return pad(`SYNA_HORROR accepted action=${action}${Number.isFinite(Number(anger)) ? ' anger=' + Math.floor(Number(anger)) : ''}${player_name ? ' target=' + player_name : ''}`);
        }
    },    {
        name: '!synaHorrorChallenge',
        description: 'Create a playable mercy challenge during Horror Syna. Use block when the player must find/drop a target block or item near Horror Syna; use kill when the player must kill target Minecraft entities. The LLM should invent a short riddle/instruction in clue, then wait for the mod to count progress.',
        params: {
            'kind': { type: 'string', description: 'block or kill.' },
            'player_name': { type: 'string', description: 'Target player who must complete the challenge.' },
            'target': { type: 'string', description: 'For block: item/block id like diamond_block or minecraft:diamond_block. For kill: entity id/name like zombie or minecraft:zombie.' },
            'clue': { type: 'string', description: 'Short riddle or instruction shown to the player.' },
            'seconds': { type: 'int', description: 'Time limit in seconds, 5-600.', domain: [5, 601] },
            'count': { type: 'int', description: 'Required amount. Usually 1 for block, 1-10 for kill.', optional: true, domain: [1, 65] }
        },
        perform: async function (agent, kind = 'block', player_name = '', target = '', clue = '', seconds = 120, count = 1) {
            const client = getModBlueprintClient();
            const normalized = String(kind || 'block').toLowerCase().trim();
            const action = normalized === 'kill' ? 'challenge_kill' : 'challenge_block';
            const res = await client.horror(action, player_name, {
                ensureSpawnAt: agent.bot.entity?.position,
                item: target,
                reason: clue,
                seconds,
                count
            });
            if (!res) return `Syna horror challenge failed: ${client.lastError || 'no response'}`;
            if (res.ok === false) return `Syna horror challenge error: ${res.error || JSON.stringify(res)}`;
            return pad(`SYNA_HORROR_CHALLENGE kind=${normalized === 'kill' ? 'kill' : 'block'} target=${target} count=${count} seconds=${seconds} player=${player_name}`);
        }
    },    {
        name: '!searchWiki',
        description: 'Search the Minecraft Wiki for the given query.',
        params: {
            'query': { type: 'string', description: 'The query to search for.' }
        },
        perform: async function (agent, query) {
            const url = `https://minecraft.wiki/w/${query}`
            try {
                const response = await fetch(url);
                if (response.status === 404) {
                  return `${query} was not found on the Minecraft Wiki. Try adjusting your search term.`;
                }
                const html = await response.text();
                const $ = load(html);
            
                const parserOutput = $("div.mw-parser-output");
                
                parserOutput.find("table.navbox").remove();

                const divContent = parserOutput.text();
            
                return divContent.trim();
              } catch (error) {
                console.error("Error fetching or parsing HTML:", error);
                return `The following error occurred: ${error}`
              }
        }
    },
    {
        name: '!help',
        description: 'Lists all available commands and their descriptions.',
        perform: async function (agent) {
            return getCommandDocs(agent);
        }
    },
    {
        name: '!mcRunPlan',
        description: 'Create or reset Syna Minecraft completion checklist. This is a fixed backbone plan for beating the Ender Dragon, shown as checkable task-board items.',
        params: {
            'mode': { type: 'string', description: 'reset to replace existing completion tasks, keep to preserve them. Default reset.', optional: true }
        },
        perform: function (_agent, mode = 'reset') {
            const added = ensureMcRunPlan({ replace: String(mode || 'reset').toLowerCase() !== 'keep' });
            return pad('MC_RUN_PLAN ready: ' + added.length + ' checklist items. Use !mcRunStatus to inspect current gaps, then !mcRunStart to let Syna execute.');
        }
    },
    {
        name: '!mcRunStatus',
        description: 'Inspect current inventory, dimension, gear, missing items, and completion-plan progress for Minecraft dragon-run planning.',
        perform: function (agent) {
            return pad(summarizeMcRunStatus(agent));
        }
    },
    {
        name: '!taskList',
        description: 'Show all tasks on the task board.',
        perform: function (agent) {
            return pad('TASK_BOARD:\n' + taskBoard.getStatusText());
        }
    },
    {
        name: '!taskAdd',
        description: 'Add a new task to the task board.',
        params: {
            'task': { type: 'string', description: 'The task description to add.' }
        },
        perform: function (agent, task) {
            const t = taskBoard.add(task);
            return `Task #${t.id} added: ${t.text}`;
        }
    },
    {
        name: '!taskDone',
        description: 'Mark a task as completed.',
        params: {
            'id': { type: 'int', description: 'The task ID to mark as done.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: function (agent, id) {
            const t = taskBoard.done(id);
            if (!t) return `Task #${id} not found.`;
            return `Task #${t.id} marked done: ${t.text}`;
        }
    },
    {
        name: '!taskRemove',
        description: 'Remove a task from the task board.',
        params: {
            'id': { type: 'int', description: 'The task ID to remove.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: function (agent, id) {
            const t = taskBoard.remove(id);
            if (!t) return `Task #${id} not found.`;
            return `Removed task #${t.id}: ${t.text}`;
        }
    },
];
