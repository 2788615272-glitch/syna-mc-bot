import * as skills from '../library/skills.js';
import * as portals from '../library/portals.js';
import * as blueprint from '../library/blueprint_builder.js';
import * as areaScanner from '../library/area_scanner.js';
import { getModBlueprintClient } from '../library/mod_blueprint_client.js';
import { bpLog } from '../library/bp_log.js';
import { listAvailableBlueprints, importAndUpload } from '../library/schematic_importer.js';
import { searchAndDownload, downloadRandom, listOnlineBlueprints, saveScannedBlueprint } from '../library/blueprint_downloader.js';
import { writeBugReport } from '../debug_journal.js';
import settings from '../settings.js';
import taskBoard from '../task_board.js';
import { buildMcRunGoalPrompt, ensureMcRunPlan, summarizeMcRunStatus } from '../library/mc_run_planner.js';
                                                                import convoManager from '../conversation.js';

// âââ ć¨Ąĺçş§ćäščˇłčżéĺďźčˇ?modBuildNext č°ç¨äżćďźéżĺ?mod ĺĺ¤čżĺĺä¸ĺć ĺŻźč´ć­ťĺžŞç?âââ
const _persistentSkippedCoords = new Set();
// ——— 智能替换缓存：方块名 → 替代品名，卡住时AI选一次，后续同类直接用缓存 ———
const _autoSubstitutes = new Map();


function runAsAction (actionFn, resume = false, timeout = -1) {
    let actionLabel = null;  // Will be set on first use
    
    const wrappedAction = async function (agent, ...args) {
        // Set actionLabel only once, when the action is first created
        if (!actionLabel) {
            const actionObj = actionsList.find(a => a.perform === wrappedAction);
            actionLabel = actionObj.name.substring(1); // Remove the ! prefix
        }

        const actionFnWithAgent = async () => {
            await actionFn(agent, ...args);
        };
        const code_return = await agent.actions.runAction(`action:${actionLabel}`, actionFnWithAgent, { timeout, resume });
        if (code_return.interrupted && !code_return.timedout)
            return;
        return code_return.message;
    }

    return wrappedAction;
}

export const actionsList = [
    {
        name: '!newAction',
        description: 'Perform new and unknown custom behaviors that are not available as a command.', 
        params: {
            'prompt': { type: 'string', description: 'A natural language prompt to guide code generation. Make a detailed step-by-step plan.' }
        },
        perform: async function(agent, prompt) {
            // just ignore prompt - it is now in context in chat history
            if (!settings.allow_insecure_coding) { 
                agent.openChat('newAction is disabled. Enable with allow_insecure_coding=true in settings.js');
                return "newAction not allowed! Code writing is disabled in settings. Notify the user.";
            }
            let result = "";
            const actionFn = async () => {
                try {
                    result = await agent.coder.generateCode(agent.history);
                } catch (e) {
                    result = 'Error generating code: ' + e.toString();
                }
            };
            await agent.actions.runAction('action:newAction', actionFn, {timeout: settings.code_timeout_mins});
            return result;
        }
    },
    {
        name: '!stop',
        description: 'Force stop all actions and commands that are currently executing.',
        perform: async function (agent) {
            await agent.actions.stop();
            taskBoard.clearFocus();
            agent.clearBotLogs();
            agent.actions.cancelResume();
            agent.bot.emit('idle');
            let msg = 'Agent stopped. Current voice focus cleared.';
            if (agent.self_prompter.isActive())
                msg += ' Self-prompting still active.';
            return msg;
        }
    },
    {
        name: '!stfu',
        description: 'Stop all chatting and self prompting, but continue current action.',
        perform: async function (agent) {
            agent.openChat('Shutting up.');
            agent.shutUp();
            return;
        }
    },
    {
        name: '!restart',
        description: 'Restart the agent process.',
        perform: async function (agent) {
            agent.cleanKill();
        }
    },
    {
        name: '!clearChat',
        description: 'Clear the chat history.',
        perform: async function (agent) {
            agent.history.clear();
            return agent.name + "'s chat history was cleared, starting new conversation from scratch.";
        }
    },
    {
        name: '!goToPlayer',
        description: 'Go to the given player.',
        params: {
            'player_name': {type: 'string', description: 'The name of the player to go to.'},
            'closeness': {type: 'float', description: 'How close to get to the player.', domain: [0, Infinity]}
        },
        perform: runAsAction(async (agent, player_name, closeness) => {
            await skills.goToPlayer(agent.bot, player_name, closeness);
        })
    },
    {
        name: '!followPlayer',
        description: 'Endlessly follow the given player.',
        params: {
            'player_name': {type: 'string', description: 'name of the player to follow.'},
            'follow_dist': {type: 'float', description: 'The distance to follow from.', domain: [0, Infinity]}
        },
        perform: runAsAction(async (agent, player_name, follow_dist) => {
            await skills.followPlayer(agent.bot, player_name, follow_dist);
        }, true)
    },
    {
        name: '!goToCoordinates',
        description: 'Go to the given x, y, z location.',
        params: {
            'x': {type: 'float', description: 'The x coordinate.', domain: [-Infinity, Infinity]},
            'y': {type: 'float', description: 'The y coordinate.', domain: [-64, 320]},
            'z': {type: 'float', description: 'The z coordinate.', domain: [-Infinity, Infinity]},
            'closeness': {type: 'float', description: 'How close to get to the location.', domain: [0, Infinity]}
        },
        perform: runAsAction(async (agent, x, y, z, closeness) => {
            await skills.goToPosition(agent.bot, x, y, z, closeness);
        })
    },
    {
        name: '!searchForBlock',
        description: 'Find and go to the nearest block of a given type in a given range.',
        params: {
            'type': { type: 'BlockName', description: 'The block type to go to.' },
            'search_range': { type: 'float', description: 'The range to search for the block. Minimum 32.', domain: [10, 512] }
        },
        perform: runAsAction(async (agent, block_type, range) => {
            if (range < 32) {
                skills.log(agent.bot, `Minimum search range is 32.`);
                range = 32;
            }
            await skills.goToNearestBlock(agent.bot, block_type, 4, range);
        })
    },
    {
        name: '!searchForEntity',
        description: 'Find and go to the nearest entity of a given type in a given range.',
        params: {
            'type': { type: 'string', description: 'The type of entity to go to.' },
            'search_range': { type: 'float', description: 'The range to search for the entity.', domain: [32, 512] }
        },
        perform: runAsAction(async (agent, entity_type, range) => {
            await skills.goToNearestEntity(agent.bot, entity_type, 4, range);
        })
    },
    {
        name: '!moveAway',
        description: 'Move away from the current location in any direction by a given distance.',
        params: {'distance': { type: 'float', description: 'The distance to move away.', domain: [0, Infinity] }},
        perform: runAsAction(async (agent, distance) => {
            await skills.moveAway(agent.bot, distance);
        })
    },
    {
        name: '!rememberHere',
        description: 'Save the current location with a given name.',
        params: {'name': { type: 'string', description: 'The name to remember the location as.' }},
        perform: async function (agent, name) {
            const pos = agent.bot.entity.position;
            agent.memory_bank.rememberPlace(name, pos.x, pos.y, pos.z);
            return `Location saved as "${name}".`;
        }
    },
    {
        name: '!goToRememberedPlace',
        description: 'Go to a saved location.',
        params: {'name': { type: 'string', description: 'The name of the location to go to.' }},
        perform: runAsAction(async (agent, name) => {
            const pos = agent.memory_bank.recallPlace(name);
            if (!pos) {
            skills.log(agent.bot, `no location named "${name}" saved.`);
            return;
            }
            await skills.goToPosition(agent.bot, pos[0], pos[1], pos[2], 1);
        })
    },
    {
        name: '!givePlayer',
        description: 'Give the specified item to the given player.',
        params: { 
            'player_name': { type: 'string', description: 'The name of the player to give the item to.' }, 
            'item_name': { type: 'ItemName', description: 'The name of the item to give.' },
            'num': { type: 'int', description: 'The number of items to give.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: runAsAction(async (agent, player_name, item_name, num) => {
            await skills.giveToPlayer(agent.bot, item_name, player_name, num);
        })
    },
    {
        name: '!consume',
        description: 'Eat/drink the given item.',
        params: {'item_name': { type: 'ItemName', description: 'The name of the item to consume.' }},
        perform: runAsAction(async (agent, item_name) => {
            await skills.consume(agent.bot, item_name);
        })
    },
    {
        name: '!equip',
        description: 'Equip the given item.',
        params: {'item_name': { type: 'ItemName', description: 'The name of the item to equip.' }},
        perform: runAsAction(async (agent, item_name) => {
            await skills.equip(agent.bot, item_name);
        })
    },
    {
        name: '!putInChest',
        description: 'Put the given item in the nearest chest.',
        params: {
            'item_name': { type: 'ItemName', description: 'The name of the item to put in the chest.' },
            'num': { type: 'int', description: 'The number of items to put in the chest.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: runAsAction(async (agent, item_name, num) => {
            await skills.putInChest(agent.bot, item_name, num);
        })
    },
    {
        name: '!takeFromChest',
        description: 'Take the given items from the nearest chest.',
        params: {
            'item_name': { type: 'ItemName', description: 'The name of the item to take.' },
            'num': { type: 'int', description: 'The number of items to take.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: runAsAction(async (agent, item_name, num) => {
            await skills.takeFromChest(agent.bot, item_name, num);
        })
    },
    {
        name: '!viewChest',
        description: 'View the items/counts of the nearest chest.',
        params: { },
        perform: runAsAction(async (agent) => {
            await skills.viewChest(agent.bot);
        })
    },
    {
        name: '!discard',
        description: 'Discard the given item from the inventory.',
        params: {
            'item_name': { type: 'ItemName', description: 'The name of the item to discard.' },
            'num': { type: 'int', description: 'The number of items to discard.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: runAsAction(async (agent, item_name, num) => {
            const start_loc = agent.bot.entity.position;
            await skills.moveAway(agent.bot, 5);
            await skills.discard(agent.bot, item_name, num);
            await skills.goToPosition(agent.bot, start_loc.x, start_loc.y, start_loc.z, 0);
        })
    },
    {
        name: '!collectBlocks',
        description: 'Collect the nearest blocks of a given type.',
        params: {
            'type': { type: 'BlockName', description: 'The block type to collect.' },
            'num': { type: 'int', description: 'The number of blocks to collect.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: runAsAction(async (agent, type, num) => {
            await skills.collectBlock(agent.bot, type, num);
        }, false, 10) // 10 minute timeout
    },
    {
        name: '!gatherFlint',
        description: 'Farm flint by placing gravel, breaking it, and picking up drops until enough flint is obtained. Use this before crafting flint_and_steel when gravel collection alone is not enough.',
        params: {
            'count': { type: 'int', description: 'Target flint count, usually 1 for flint_and_steel.', domain: [1, 8] },
            'max_attempts': { type: 'int', description: 'Maximum gravel place/break attempts.', domain: [4, 128] }
        },
        perform: runAsAction(async (agent, count, max_attempts) => {
            await skills.gatherFlint(agent.bot, count || 1, max_attempts || 40);
        }, false, 6)
    },
    {
        name: '!craftRecipe',
        description: 'Craft the given recipe a given number of times.',
        params: {
            'recipe_name': { type: 'ItemName', description: 'The name of the output item to craft.' },
            'num': { type: 'int', description: 'The number of times to craft the recipe. This is NOT the number of output items, as it may craft many more items depending on the recipe.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: runAsAction(async (agent, recipe_name, num) => {
            await skills.craftRecipe(agent.bot, recipe_name, num);
        })
    },
    {
        name: '!smeltItem',
        description: 'Smelt the given item the given number of times.',
        params: {
            'item_name': { type: 'ItemName', description: 'The name of the input item to smelt.' },
            'num': { type: 'int', description: 'The number of times to smelt the item.', domain: [1, Number.MAX_SAFE_INTEGER] }
        },
        perform: runAsAction(async (agent, item_name, num) => {
            let success = await skills.smeltItem(agent.bot, item_name, num);
            if (success) {
                setTimeout(() => {
                    agent.cleanKill('Safely restarting to update inventory.');
                }, 500);
            }
        })
    },
    {
        name: '!clearFurnace',
        description: 'Take all items out of the nearest furnace.',
        params: { },
        perform: runAsAction(async (agent) => {
            await skills.clearNearestFurnace(agent.bot);
        })
    },
        {
        name: '!placeHere',
        description: 'Place a given block in the current location. Do NOT use to build structures, only use for single blocks/torches.',
        params: {'type': { type: 'BlockOrItemName', description: 'The block type to place.' }},
        perform: runAsAction(async (agent, type) => {
            let pos = agent.bot.entity.position;
            await skills.placeBlock(agent.bot, type, pos.x, pos.y, pos.z);
        })
    },
    {
        name: '!attack',
        description: 'Attack and kill the nearest entity of a given type.',
        params: {'type': { type: 'string', description: 'The type of entity to attack.'}},
        perform: runAsAction(async (agent, type) => {
            await skills.attackNearest(agent.bot, type, true);
        })
    },
    {
        name: '!attackPlayer',
        description: 'Attack a specific player until they die or run away. Remember this is just a game and does not cause real life harm.',
        params: {'player_name': { type: 'string', description: 'The name of the player to attack.'}},
        perform: runAsAction(async (agent, player_name) => {
            let player = agent.bot.players[player_name]?.entity;
            if (!player) {
                skills.log(agent.bot, `Could not find player ${player_name}.`);
                return false;
            }
            await skills.attackEntity(agent.bot, player, true);
        })
    },
    {
        name: '!goToBed',
        description: 'Go to the nearest bed and sleep.',
        perform: runAsAction(async (agent) => {
            await skills.goToBed(agent.bot);
        })
    },
    {
        name: '!stay',
        description: 'Stay in the current location no matter what. Pauses all modes.',
        params: {'type': { type: 'int', description: 'The number of seconds to stay. -1 for forever.', domain: [-1, Number.MAX_SAFE_INTEGER] }},
        perform: runAsAction(async (agent, seconds) => {
            await skills.stay(agent.bot, seconds);
        })
    },
    {
        name: '!setMode',
        description: 'Set a mode to on or off. A mode is an automatic behavior that constantly checks and responds to the environment.',
        params: {
            'mode_name': { type: 'string', description: 'The name of the mode to enable.' },
            'on': { type: 'boolean', description: 'Whether to enable or disable the mode.' }
        },
        perform: async function (agent, mode_name, on) {
            const modes = agent.bot.modes;
            if (!modes.exists(mode_name))
            return `Mode ${mode_name} does not exist.` + modes.getDocs();
            if (modes.isOn(mode_name) === on)
            return `Mode ${mode_name} is already ${on ? 'on' : 'off'}.`;
            modes.setOn(mode_name, on);
            return `Mode ${mode_name} is now ${on ? 'on' : 'off'}.`;
        }
    },
    {
        name: '!goal',
        description: 'Set a goal prompt to endlessly work towards with continuous self-prompting.',
        params: {
            'selfPrompt': { type: 'string', description: 'The goal prompt.' },
        },
        perform: async function (agent, prompt) {
            if (convoManager.inConversation()) {
                agent.self_prompter.setPromptPaused(prompt);
            }
            else {
                agent.self_prompter.start(prompt);
            }
        }
    },
    {
        name: '!endGoal',
        description: 'Call when you have accomplished your goal. It will stop self-prompting and the current action. ',
        perform: async function (agent) {
            agent.self_prompter.stop();
            taskBoard.clearFocus();
            return 'Self-prompting stopped. Current focus cleared.';
        }
    },
    {
        name: '!mcRunStart',
        description: 'Start Syna Minecraft completion skill: create the dragon-run checklist, inspect current gaps, and enter focused self-prompt execution toward beating the Ender Dragon.',
        params: {
            'mode': { type: 'string', description: 'reset to rebuild the checklist, keep to preserve existing checked tasks. Default keep.', optional: true }
        },
        perform: async function (agent, mode = 'keep') {
            ensureMcRunPlan({ replace: String(mode || 'keep').toLowerCase() === 'reset' });
            const prompt = buildMcRunGoalPrompt(agent);
            agent.self_prompter.start(prompt);
            return 'MC_RUN_START: 通关技能已启动。\n' + summarizeMcRunStatus(agent);
        }
    },
    {
        name: '!mcRunStop',
        description: 'Stop Syna Minecraft completion self-prompt loop and clear the MC-run checklist.',
        perform: async function (agent) {
            await agent.self_prompter.stop(false);
            taskBoard.clear({ kind: 'mc_run' });
            return 'MC_RUN_STOP: 通关执行循环已停止，通关任务板已清空。';
        }
    },
    {
        name: '!showVillagerTrades',
        description: 'Show trades of a specified villager.',
        params: {'id': { type: 'int', description: 'The id number of the villager that you want to trade with.' }},
        perform: runAsAction(async (agent, id) => {
            await skills.showVillagerTrades(agent.bot, id);
        })
    },
    {
        name: '!tradeWithVillager',
        description: 'Trade with a specified villager.',
        params: {
            'id': { type: 'int', description: 'The id number of the villager that you want to trade with.' },
            'index': { type: 'int', description: 'The index of the trade you want executed (1-indexed).', domain: [1, Number.MAX_SAFE_INTEGER] },
            'count': { type: 'int', description: 'How many times that trade should be executed.', domain: [1, Number.MAX_SAFE_INTEGER] },
        },
        perform: runAsAction(async (agent, id, index, count) => {
            await skills.tradeWithVillager(agent.bot, id, index, count);
        })
    },
    {
        name: '!startConversation',
        description: 'Start a conversation with a bot. (FOR OTHER BOTS ONLY)',
        params: {
            'player_name': { type: 'string', description: 'The name of the player to send the message to.' },
            'message': { type: 'string', description: 'The message to send.' },
        },
        perform: async function (agent, player_name, message) {
            if (!convoManager.isOtherAgent(player_name))
                return player_name + ' is not a bot, cannot start conversation.';
            if (convoManager.inConversation() && !convoManager.inConversation(player_name)) 
                convoManager.forceEndCurrentConversation();
            else if (convoManager.inConversation(player_name))
                agent.history.add('system', 'You are already in conversation with ' + player_name + '. Don\'t use this command to talk to them.');
            convoManager.startConversation(player_name, message);
        }
    },
    {
        name: '!endConversation',
        description: 'End the conversation with the given bot. (FOR OTHER BOTS ONLY)',
        params: {
            'player_name': { type: 'string', description: 'The name of the player to end the conversation with.' }
        },
        perform: async function (agent, player_name) {
            if (!convoManager.inConversation(player_name))
                return `not in conversation with ${player_name}.`;
            convoManager.endConversation(player_name);
            return `Converstaion with ${player_name} ended.`;
        }
    },
    {
        name: '!lookAtPlayer',
        description: 'Look at a player or look in the same direction as the player.',
        params: {
            'player_name': { type: 'string', description: 'Name of the target player' },
            'direction': {
                type: 'string',
                description: 'How to look ("at": look at the player, "with": look in the same direction as the player)',
            }
        },
        perform: async function(agent, player_name, direction) {
            if (direction !== 'at' && direction !== 'with') {
                return "Invalid direction. Use 'at' or 'with'.";
            }
            let result = "";
            const actionFn = async () => {
                result = await agent.vision_interpreter.lookAtPlayer(player_name, direction);
            };
            await agent.actions.runAction('action:lookAtPlayer', actionFn);
            return result;
        }
    },
    {
        name: '!lookAtPosition',
        description: 'Look at specified coordinates.',
        params: {
            'x': { type: 'int', description: 'x coordinate' },
            'y': { type: 'int', description: 'y coordinate' },
            'z': { type: 'int', description: 'z coordinate' }
        },
        perform: async function(agent, x, y, z) {
            let result = "";
            const actionFn = async () => {
                result = await agent.vision_interpreter.lookAtPosition(x, y, z);
            };
            await agent.actions.runAction('action:lookAtPosition', actionFn);
            return result;
        }
    },
    {
        name: '!digDown',
        description: 'Digs down a specified distance. Will stop if it reaches lava, water, or a fall of >=4 blocks below the bot.',
        params: {'distance': { type: 'int', description: 'Distance to dig down', domain: [1, Number.MAX_SAFE_INTEGER] }},
        perform: runAsAction(async (agent, distance) => {
            await skills.digDown(agent.bot, distance)
        })
    },
    {
        name: '!goToSurface',
        description: 'Moves the bot to the highest block above it (usually the surface).',
        params: {},
        perform: runAsAction(async (agent) => {
            await skills.goToSurface(agent.bot);
        })
    },
    {
        name: '!useOn',
        description: 'Use (right click) the given tool on the nearest target of the given type.',
        params: {
            'tool_name': { type: 'string', description: 'Name of the tool to use, or "hand" for no tool.' },
            'target': { type: 'string', description: 'The target as an entity type, block type, or "nothing" for no target.' }
        },
        perform: runAsAction(async (agent, tool_name, target) => {
            await skills.useToolOn(agent.bot, tool_name, target);
        })
    },
    {
        name: '!makeObsidian',
        description: 'Safely make and mine obsidian from nearby source lava using a water bucket and diamond pickaxe. Does not tower upward for water; if needed it first collects nearby water with an empty bucket.',
        params: {
            'count': { type: 'int', description: 'Target obsidian count, usually 10 for a nether portal.', domain: [1, 14], optional: true },
            'range': { type: 'int', description: 'Search radius for source lava and water.', domain: [16, 128], optional: true }
        },
        perform: runAsAction(async (agent, count, range) => {
            await skills.makeObsidian(agent.bot, count || 10, range || 64);
        }, false, 8)
    },
    {
        name: '!buildNetherPortal',
        description: 'Build and ignite a stable 10-obsidian nether portal. The command automatically scans for a safer nearby site, prepares a small work platform, clears the portal volume, builds the frame, and lights it. Requires 10+ obsidian and a flint_and_steel.',
        params: {
            'x': { type: 'int', description: 'X of bottom-left corner.' },
            'y': { type: 'int', description: 'Y of bottom row of the frame.' },
            'z': { type: 'int', description: 'Z of bottom-left corner.' },
            'axis': { type: 'string', description: 'Frame axis: "x" or "z" (which horizontal axis the frame extends along).' }
        },
        perform: runAsAction(async (agent, x, y, z, axis) => {
            await portals.buildNetherPortal(agent.bot, x, y, z, axis || 'x');
        })
    },
    {
        name: '!useNetherPortal',
        description: 'Walk into the nearest nether portal and wait for the dimension to change. Will try to light a nearby unlit obsidian frame if no active portal is found.',
        params: {},
        perform: runAsAction(async (agent) => {
            await portals.useNetherPortal(agent.bot);
        })
    },
    {
        name: '!findEndPortal',
        description: 'Search for an end portal (active block or end_portal_frame) and report its location plus how many eyes are missing.',
        params: {
            'range': { type: 'int', description: 'Search radius in blocks.', domain: [16, 256] }
        },
        perform: runAsAction(async (agent, range) => {
            await portals.findEndPortal(agent.bot, range || 128);
        })
    },
    {
        name: '!enterEndPortal',
        description: 'Walk into the nearest active end portal. Will fill missing end_portal_frame slots with ender_eye if any are in inventory.',
        params: {},
        perform: runAsAction(async (agent) => {
            await portals.enterEndPortal(agent.bot);
        })
    },
    {
        name: '!build',
        description: 'Build a predefined structure/blueprint at your current location or specified coordinates. Available blueprints: cabin, tower, wall, bridge, twilight_portal.',
        params: {
            'blueprint_name': { type: 'string', description: 'Name of the blueprint to build (e.g. cabin, tower, wall, bridge, twilight_portal).' },
            'x': { type: 'float', description: 'X coordinate to build at. If not given, builds at current position.', optional: true },
            'y': { type: 'float', description: 'Y coordinate to build at.', optional: true },
            'z': { type: 'float', description: 'Z coordinate to build at.', optional: true }
        },
        perform: runAsAction(async (agent, blueprint_name, x, y, z) => {
            const pos = agent.bot.entity.position;
            const bx = (x != null && !isNaN(x)) ? Math.floor(x) : Math.floor(pos.x);
            const by = (y != null && !isNaN(y)) ? Math.floor(y) : Math.floor(pos.y);
            const bz = (z != null && !isNaN(z)) ? Math.floor(z) : Math.floor(pos.z);
            await blueprint.buildBlueprint(agent.bot, blueprint_name, bx, by, bz);
        }, false, 15) // 15 minute timeout for large builds
    },
    {
        name: '!listBlueprints',
        description: 'List all available building blueprints that can be used with !build.',
        params: {},
        perform: async function (agent) {
            const names = blueprint.listBlueprints();
            if (names.length === 0) return 'No blueprints available.';
            const descriptions = names.map(name => {
                const bp = blueprint.loadBlueprint(name);
                return bp ? `  ${name}: ${bp.description}` : `  ${name}`;
            });
            return `Available blueprints:\n${descriptions.join('\n')}`;
        }
    },
    {
        name: '!checkMaterials',
        description: 'Check if you have enough materials in your inventory to build a blueprint. Returns missing items.',
        params: {
            'blueprint_name': { type: 'string', description: 'The name of the blueprint to check materials for.' }
        },
        perform: async function (agent, blueprint_name) {
            const result = blueprint.checkMaterials(agent.bot, blueprint_name);
            if (!result.success) return result.message;
            if (result.canBuild) return `You have all materials needed for "${blueprint_name}". Ready to build!`;
            const missingList = result.missing.map(m => `  ${m.name}: need ${m.needed}, have ${m.have} (short ${m.needed - m.have})`);
            return `Missing materials for "${blueprint_name}":\n${missingList.join('\n')}`;
        }
    },
    {
        name: '!scanArea',
        description: 'Scan a 3D bounding box of the world into a per-Y layer snapshot (palette + 2D grids). Use this to see what an existing build looks like before editing it. Coordinates are world coords; size is capped at ~32x32x32.',
        params: {
            'x1': { type: 'int', description: 'First corner X.' },
            'y1': { type: 'int', description: 'First corner Y.' },
            'z1': { type: 'int', description: 'First corner Z.' },
            'x2': { type: 'int', description: 'Opposite corner X.' },
            'y2': { type: 'int', description: 'Opposite corner Y.' },
            'z2': { type: 'int', description: 'Opposite corner Z.' }
        },
        perform: async function (agent, x1, y1, z1, x2, y2, z2) {
            try {
                const snap = areaScanner.scanArea(agent.bot, x1, y1, z1, x2, y2, z2);
                const text = areaScanner.renderSnapshotForLLM(snap, { maxLayers: 12 });
                return text + `\n\nSnapshot stored. Use !applyEdits with a JSON edits array to modify it (local coords, y up).`;
            } catch (e) {
                return `Scan failed: ${e.message || e}`;
            }
        }
    },
    {
        name: '!scanAroundMe',
        description: 'Convenience: scan a cube of the given radius centered on the bot (or on (cx,cy,cz) if provided). Saves the result as the current snapshot.',
        params: {
            'radius': { type: 'int', description: 'Half-extent of the cube. Final size is (2r+1)^3. Max 16.', domain: [1, 16] },
            'cx': { type: 'int', description: 'Center X (optional).', optional: true },
            'cy': { type: 'int', description: 'Center Y (optional).', optional: true },
            'cz': { type: 'int', description: 'Center Z (optional).', optional: true }
        },
        perform: async function (agent, radius, cx, cy, cz) {
            const r = Math.max(1, Math.min(16, Math.floor(radius || 5)));
            const pos = agent.bot.entity.position;
            const ux = (cx != null && !isNaN(cx)) ? Math.floor(cx) : Math.floor(pos.x);
            const uy = (cy != null && !isNaN(cy)) ? Math.floor(cy) : Math.floor(pos.y);
            const uz = (cz != null && !isNaN(cz)) ? Math.floor(cz) : Math.floor(pos.z);
            try {
                const snap = areaScanner.scanArea(agent.bot, ux - r, uy - r, uz - r, ux + r, uy + r, uz + r);
                const text = areaScanner.renderSnapshotForLLM(snap, { maxLayers: 12 });
                return text + `\n\nSnapshot stored. Use !applyEdits with a JSON edits array to modify it (local coords, y up).`;
            } catch (e) {
                return `Scan failed: ${e.message || e}`;
            }
        }
    },
    {
        name: '!applyEdits',
        description: 'Apply a list of block edits to the most recent !scanArea snapshot. Pass a JSON array of edits using LOCAL coords relative to the snapshot origin. Each edit is {"op":"set","x":..,"y":..,"z":..,"block":"oak_planks"} or {"op":"remove","x":..,"y":..,"z":..}. Add "world": true to use absolute world coords instead.',
        params: {
            'edits_json': { type: 'string', description: 'A JSON string of an array of edit objects. Example: [{"op":"set","x":2,"y":0,"z":1,"block":"oak_planks"}].' }
        },
        perform: runAsAction(async (agent, edits_json) => {
            const snap = areaScanner.getLastScan(agent.bot);
            if (!snap) {
                skills.log(agent.bot, 'No snapshot found. Run !scanArea or !scanAroundMe first.');
                return;
            }
            let edits;
            try {
                edits = JSON.parse(edits_json);
            } catch (e) {
                skills.log(agent.bot, `Invalid edits JSON: ${e.message}`);
                return;
            }
            if (!Array.isArray(edits)) {
                skills.log(agent.bot, 'edits_json must parse to an array.');
                return;
            }
            skills.log(agent.bot, `Applying ${edits.length} edit(s) to last snapshot at origin (${snap.origin.x},${snap.origin.y},${snap.origin.z})...`);
            const res = await areaScanner.applyEdits(agent.bot, snap, edits, skills);
            const skippedMsg = res.skipped.length
                ? ` Skipped ${res.skipped.length} (e.g. "${res.skipped[0].reason}")`
                : '';
            skills.log(agent.bot, `Edits done: placed ${res.placed}, removed ${res.removed}, failed ${res.failed}.${skippedMsg}`);
        }, false, 15)
    },
    {
        name: '!crosshair',
        description: 'Get the block that the player is currently looking at (requires SynaBridge mod on server). Returns block name and coordinates.',
        params: {},
        perform: async function (agent) {
            const ch = agent._playerCrosshair;
            if (!ch) return 'No crosshair data available. The player may not be looking at a block, or the SynaBridge mod is not active.';
            const age = Date.now() - ch.time;
            if (age > 5000) return `Crosshair data is stale (${(age/1000).toFixed(1)}s old). Player may have stopped looking at blocks.`;
            return `Player is looking at: ${ch.block} at (${ch.x}, ${ch.y}, ${ch.z})`;
        }
    },
    {
        name: '!modUploadBlueprint',
        description: 'Upload the most recent !scanArea snapshot to the SynaBridge mod as a server-side blueprint. The mod tracks per-cell progress and serves "next missing cell" queries. Modes: "build" = bot constructs missing cells (mod blocks bot from breaking finished ones), "remodel" = protection suspended so bot/player can edit the plan content, "locked" = nobody can break done cells.',
        params: {
            'id': { type: 'string', description: 'Unique blueprint id, e.g. "house1".' },
            'mode': { type: 'string', description: '"build", "remodel", or "locked". Default "build".', optional: true },
            'use_edit_plan': { type: 'boolean', description: 'If true and edits_json is provided, only the planned placements/removals are uploaded; otherwise the raw snapshot is used.', optional: true },
            'edits_json': { type: 'string', description: 'Optional JSON edit array (same shape as !applyEdits) to use as the blueprint instead of the raw scan.', optional: true },
            'auto_clear': { type: 'boolean', description: 'If true the mod will replace existing blocks with air before placing blueprint blocks. Default false.', optional: true }
        },
        perform: runAsAction(async (agent, id, mode, use_edit_plan, edits_json, auto_clear) => {
            const snap = areaScanner.getLastScan(agent.bot);
            if (!snap) {
                skills.log(agent.bot, 'No snapshot found. Run !scanArea or !scanAroundMe first.');
                return;
            }
            let editPlan = null;
            if (use_edit_plan && edits_json) {
                try {
                    const edits = JSON.parse(edits_json);
                    editPlan = areaScanner.buildEditPlan(snap, edits);
                } catch (e) {
                    skills.log(agent.bot, `Invalid edits JSON: ${e.message}`);
                    return;
                }
            }
            const client = getModBlueprintClient();
            const res = await client.uploadSnapshot(id, snap, {
                mode: mode || 'build',
                autoClear: !!auto_clear,
                editPlan,
            });
            if (!res) {
                skills.log(agent.bot, `Upload failed: ${client.lastError || 'no response from mod'}`);
                return;
            }
            if (res.ok === false) {
                skills.log(agent.bot, `Upload rejected: ${res.error || JSON.stringify(res)}`);
                return;
            }
            skills.log(agent.bot, `Blueprint "${id}" uploaded (${res.cells ?? '?'} cells, mode=${res.mode || mode || 'build'}).`);
        }, false, 5)
    },
    {
        name: '!modListBlueprints',
        description: 'List blueprints currently registered with the SynaBridge mod (server-side).',
        params: {},
        perform: async function (agent) {
            const client = getModBlueprintClient();
            const res = await client.list();
            if (!res) return `Mod query failed: ${client.lastError || 'no response'}`;
            const items = res.blueprints || res.items || [];
            if (!items.length) return 'No blueprints registered on the server.';
            const lines = items.map(b => `  ${b.id}: mode=${b.mode}, total=${b.total}, placed=${b.placed}, remaining=${b.remaining}`);
            return `Server blueprints:\n${lines.join('\n')}`;
        }
    },
    {
        name: '!modBlueprintStatus',
        description: 'Get progress info for a server-side blueprint (total cells, placed, remaining).',
        params: {
            'id': { type: 'string', description: 'Blueprint id.' }
        },
        perform: async function (agent, id) {
            const client = getModBlueprintClient();
            const res = await client.status(id);
            if (!res) return `Mod query failed: ${client.lastError || 'no response'}`;
            if (res.ok === false) return `Status error: ${res.error || JSON.stringify(res)}`;
            return `Blueprint "${id}": mode=${res.mode}, total=${res.total}, placed=${res.placed}, remaining=${res.remaining}, origin=(${res.ox},${res.oy},${res.oz}).`;
        }
    },
    {
        name: '!modBuildNext',
        description: 'Ask the SynaBridge mod for the next missing cell of a blueprint and place that one block. Run repeatedly (or via !goal) to construct the building. The mod picks the closest cell to the bot, so the LLM never has to track coordinates.',
        params: {
            'id': { type: 'string', description: 'Blueprint id, or "any" to let the mod pick.' },
            'count': { type: 'int', description: 'How many blocks to place this call. Default 1.', optional: true, domain: [1, 65] }
        },
        perform: runAsAction(async (agent, id, count) => {
            const client = getModBlueprintClient();
            const n = Math.max(1, Math.min(64, Math.floor(count || 1)));
            const pos0 = agent.bot.entity.position;
            bpLog(`âââ?modBuildNext START âââ?id="${id}", count=${n}, botPos=(${Math.floor(pos0.x)},${Math.floor(pos0.y)},${Math.floor(pos0.z)}), gameMode=${agent.bot.game?.gameMode}`);
            // ĺćĽä¸ä¸čĺžçśćďźçĄŽčŽ¤modçŤŻćčżä¸Şčĺž
            const preStatus = await client.status(id);
            bpLog(`pre-status for "${id}":`, JSON.stringify(preStatus)?.slice(0, 300));
            let placed = 0;
            let skipped = 0;
            let consecutiveFails = 0;
            const MAX_CONSECUTIVE_FAILS = 5;
            const MAX_SKIPS_PER_CALL = 128;
            const failedCoords = new Set(); // čŽ°ĺ˝ćŹćŹĄč°ç¨ä¸­ĺ¤ąč´Ľçĺć ďźéżĺéĺ¤ĺ°čŻ?
            // âââ ćšĺćżć˘čĄ¨ďźéĺ°ćžä¸ä¸çćšĺćśďźç¨ćżäťŁĺäťŁćż âââ
            const BLOCK_SUBSTITUTES = {
                // čŁéĽ°ć§ć¤ç?â?č§č§čżäźźçĺŻćžç˝Žćšĺ
                'short_grass': 'fern',       // ç­č â?č¨ďźč¨ĺŻäťĽćžĺ¨ćłĽĺä¸ďź?                'grass': 'fern',
                'tall_grass': 'fern',
                'large_fern': 'fern',
                'dead_bush': 'air',
                'vine': 'oak_leaves',        // č¤č â?ćŠĄć¨ć ĺś
                'glow_lichen': 'air',
                'hanging_roots': 'air',
                'moss_carpet': 'green_carpet',
                'sweet_berry_bush': 'oak_leaves',
                'cave_vines': 'air',
                'cave_vines_plant': 'air',
                'spore_blossom': 'air',
                'azalea': 'oak_leaves',
                'flowering_azalea': 'oak_leaves',
                // č?â?ĺŻšĺşé˘č˛ççžćŻ?ĺ°ćŻŻďźćç´ćĽ air
                'poppy': 'red_wool',
                'dandelion': 'yellow_wool',
                'blue_orchid': 'light_blue_wool',
                'allium': 'magenta_wool',
                'azure_bluet': 'white_wool',
                'red_tulip': 'red_wool',
                'orange_tulip': 'orange_wool',
                'white_tulip': 'white_wool',
                'pink_tulip': 'pink_wool',
                'oxeye_daisy': 'white_wool',
                'cornflower': 'blue_wool',
                'lily_of_the_valley': 'white_wool',
                'torchflower': 'orange_wool',
                'pitcher_plant': 'cyan_wool',
                'wither_rose': 'black_wool',
                'sunflower': 'yellow_wool',
                'lilac': 'magenta_wool',
                'rose_bush': 'red_wool',
                'peony': 'pink_wool',
                'lily_pad': 'green_carpet',
                // ć°´çć¤çŠ
                'seagrass': 'air',
                'tall_seagrass': 'air',
                'kelp': 'air',
                'kelp_plant': 'air',
                // çšćŽćšĺ
                'small_dripleaf': 'air',
                'big_dripleaf': 'oak_leaves',
                'big_dripleaf_stem': 'oak_fence',
                'fern': 'air',              // ĺŚćč¨äšćžä¸ä¸ĺ°ąčˇłčż
                // ä¸ĺŻčˇĺçćšĺ?                'budding_amethyst': 'amethyst_block',
                'moving_piston': 'air',
            };

            // âââ éćéťĺĺďźčżäşćšĺä¸éčŚ?ä¸č˝ćžç˝Žďźç´ćĽčˇłčż?âââ
            const BLOCK_BLACKLIST = new Set([
                'air', 'cave_air', 'void_air',
                // čŁéĽ°ć§ć¤çŠďźčżäşćšĺéčŚçšĺŽĺĺŁ¤ćč˝ĺ­ć´ťďźćžç˝Žĺĺ¸¸çŤĺťćč˝ĺŻźč´ mod ĺĺ¤čżĺ
                'short_grass', 'grass', 'tall_grass', 'fern', 'large_fern',
                'dead_bush', 'seagrass', 'tall_seagrass', 'kelp', 'kelp_plant',
                'vine', 'glow_lichen', 'hanging_roots', 'moss_carpet',
                'small_dripleaf', 'big_dripleaf', 'big_dripleaf_stem',
                'sweet_berry_bush', 'cave_vines', 'cave_vines_plant',
                'spore_blossom', 'azalea', 'flowering_azalea',
                'poppy', 'dandelion', 'blue_orchid', 'allium', 'azure_bluet',
                'red_tulip', 'orange_tulip', 'white_tulip', 'pink_tulip',
                'oxeye_daisy', 'cornflower', 'lily_of_the_valley', 'torchflower',
                'pitcher_plant', 'wither_rose', 'sunflower', 'lilac',
                'rose_bush', 'peony', 'lily_pad',
                'structure_void', 'light',
                'barrier', 'jigsaw', 'structure_block',
                'moving_piston', 'budding_amethyst',
                // ĺ¸?minecraft: ĺçźçäšĺźĺŽš
                'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air',
                'minecraft:structure_void', 'minecraft:light',
                'minecraft:barrier', 'minecraft:jigsaw', 'minecraft:structure_block',
            ]);
            // čżčĄćśĺ¨ćéťĺĺďźćžç˝Žčżçť­ĺ¤ąč´Ľçćšĺçąťĺ
            const runtimeBlacklist = new Set();
            // ćŹĺ°ĺˇ˛čˇłčżĺć éĺďźĺ˝?/skip čŻˇćąĺ¤ąč´ĽćśďźćŹĺ°čŽ°ä˝čżäşĺć ďź?            // ä¸ćŹĄ mod ĺčżĺĺä¸ĺć ćśç´ćĽç¨ /setblock ćžç˝Žčä¸ćŻĺćŹĄĺ°čŻ?skip
            const localSkippedCoords = new Set();
            let lastReturnedCoord = null; // ćŁćľ?mod ĺĺ¤čżĺĺä¸ĺć 
            let sameCoordCount = 0;

            bpLog(`>>> ENTERING LOOP: n=${n}, skipped=${skipped}, loop bound=${n + skipped}`);
            for (let i = 0; i < n + skipped; i++) {
                // 防止跳过太多导致无限循环
                if (skipped >= MAX_SKIPS_PER_CALL) {
                    bpLog(`reached MAX_SKIPS_PER_CALL (${MAX_SKIPS_PER_CALL}), stopping`);
                    break;
                }
                if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                    skills.log(agent.bot, `čżçť­ ${MAX_CONSECUTIVE_FAILS} ćŹĄćžç˝Žĺ¤ąč´Ľďźĺć­˘ćŹč˝Žĺťşé ăĺŻč˝ćä¸ĺŻćžç˝Žçćšĺă`);
                    break;
                }
                const pos = agent.bot.entity.position;
                const res = await client.next(id, { x: pos.x, y: pos.y, z: pos.z });
                bpLog(`next(${id}) raw response: ${JSON.stringify(res)?.slice(0, 300)}`);
                if (!res) {
                    skills.log(agent.bot, `Mod query failed: ${client.lastError || 'no response'}`);
                    bpLog(`next() returned null/undefined, lastError=${client.lastError}`);
                    break;
                }
                if (res.ok === false) {
                    skills.log(agent.bot, `Build error: ${res.error || JSON.stringify(res)}`);
                    bpLog(`next() returned ok=false: ${JSON.stringify(res)}`);
                    break;
                }
                // ĺźĺŽš mod čżĺćĺšłć źĺź {ok,done,x,y,z,name} ĺĺľĺĽć źĺź?{ok,done,block:{x,y,z,name}}
                const block = res.block || (res.x !== undefined ? { x: res.x, y: res.y, z: res.z, name: res.name } : null);
                if (res.done || !block) {
                    skills.log(agent.bot, `Blueprint "${id}" complete (placed ${placed} this call).`);
                    return;
                }
                const { x, y, z, name } = block;
                bpLog(`modBuildNext: mod returned block={name:${name}, x:${x}, y:${y}, z:${z}}, placed_so_far=${res.placed_so_far}, total=${res.total}, done=${res.done}`);

                // âââ ćäščˇłčżéĺďźčˇ¨č°ç¨čŽ°ä˝ć ćłĺ¤ççĺć ďźĺżŤéčˇłčż?âââ
                const thisCoord = `${x},${y},${z}`;
                if (_persistentSkippedCoords.has(thisCoord)) {
                    // ééťčˇłčżďźä¸ĺˇćĽĺżďźçŹŹä¸ćŹĄäźĺ¨ä¸é˘ćĺ°ďź
                    skipped++;
                    continue;
                }
                // ——— 智能替换缓存命中：如果这类方块之前卡过，直接用缓存的替代品 ———
                const normalizedNameEarly = (name || '').replace(/^minecraft:/, '');
                if (_autoSubstitutes.has(normalizedNameEarly)) {
                    const cachedSub = _autoSubstitutes.get(normalizedNameEarly);
                    bpLog(`AUTO-SUBSTITUTE (cached): "${normalizedNameEarly}" -> "${cachedSub}" at (${x},${y},${z})`);
                    agent.bot.chat(`/setblock ${Math.floor(x)} ${Math.floor(y)} ${Math.floor(z)} ${cachedSub}`);
                    await new Promise(resolve => setTimeout(resolve, 50));
                    try { await client.skip(res.blueprint_id || id, x, y, z, name); } catch (_) {}
                    placed++;
                    consecutiveFails = 0;
                    sameCoordCount = 0;
                    lastReturnedCoord = null;
                    continue;
                }

                // ——— 检测 mod 重复返回同一坐标（卡住检测）———
                if (thisCoord === lastReturnedCoord) {
                    sameCoordCount++;
                    if (sameCoordCount >= 2) {
                        // mod 卡住了，用智能替换
                        bpLog(`mod STUCK at ${thisCoord} (${normalizedNameEarly}) ${sameCoordCount} times -> auto-substitute`);
                        let substitute = BLOCK_SUBSTITUTES[normalizedNameEarly];
                        if (!substitute) {
                            if (normalizedNameEarly.includes('chain')) substitute = 'iron_bars';
                            else if (normalizedNameEarly.includes('fence')) substitute = 'oak_fence';
                            else if (normalizedNameEarly.includes('wall')) substitute = 'cobblestone_wall';
                            else if (normalizedNameEarly.includes('slab')) substitute = 'smooth_stone_slab';
                            else if (normalizedNameEarly.includes('stair')) substitute = 'oak_stairs';
                            else if (normalizedNameEarly.includes('lantern')) substitute = 'glowstone';
                            else if (normalizedNameEarly.includes('candle')) substitute = 'torch';
                            else if (normalizedNameEarly.includes('sign')) substitute = 'oak_planks';
                            else if (normalizedNameEarly.includes('banner')) substitute = 'white_wool';
                            else if (normalizedNameEarly.includes('head') || normalizedNameEarly.includes('skull')) substitute = 'carved_pumpkin';
                            else if (normalizedNameEarly.includes('pot')) substitute = 'brick';
                            else substitute = 'stone';
                        }
                        _autoSubstitutes.set(normalizedNameEarly, substitute);
                        bpLog(`AUTO-SUBSTITUTE: "${normalizedNameEarly}" -> "${substitute}" (cached)`);
                        agent.bot.chat(`/setblock ${Math.floor(x)} ${Math.floor(y)} ${Math.floor(z)} ${substitute}`);
                        await new Promise(resolve => setTimeout(resolve, 50));
                        try { await client.skip(res.blueprint_id || id, x, y, z, name); } catch (_) {}
                        placed++;
                        consecutiveFails = 0;
                        sameCoordCount = 0;
                        lastReturnedCoord = null;
                        continue;
                    }
                } else {
                    sameCoordCount = 1;
                    lastReturnedCoord = thisCoord;
                }

                // âââ ćŹĺ°ĺˇ˛čˇłčżçĺć ďźç´ć?/setblock ä¸ĺĺ°čŻ skip âââ
                if (localSkippedCoords.has(thisCoord)) {
                    // ĺˇ˛çť setblock čżä˝ mod čżčżĺďźĺ ĺĽćäšéĺ
                    _persistentSkippedCoords.add(thisCoord);
                    skipped++;
                    continue;
                }

                // âââ éťĺĺćŁćĽďźĺ°čŻćżć˘ćčˇłčżä¸ĺŻćžç˝Žçćšĺ âââ
                const normalizedName = (name || '').replace(/^minecraft:/, '');
                if (BLOCK_BLACKLIST.has(name) || BLOCK_BLACKLIST.has(normalizedName) || runtimeBlacklist.has(normalizedName)) {
                    // ćĽćżć˘čĄ¨ďźĺŚććé?air çćżäťŁĺďźćžćżäťŁĺ?
                    const substitute = BLOCK_SUBSTITUTES[normalizedName] || BLOCK_SUBSTITUTES[name];
                    if (substitute && substitute !== 'air') {
                        // 如果替代品本身也在黑名单里（如 fern），直接当 air 跳过
                        if (BLOCK_BLACKLIST.has(substitute)) {
                            bpLog(`SUBSTITUTE "${substitute}" also blacklisted, skip "${normalizedName}" at (${x},${y},${z})`);
                            const bpId2 = res.blueprint_id || id;
                            const skipRes2 = await client.skip(bpId2, x, y, z, name);
                            if (!skipRes2 || skipRes2.ok === false) {
                                localSkippedCoords.add(thisCoord);
                            }
                            skipped++;
                            continue;
                        }
                        // ĺ°čŻćžç˝ŽćżäťŁćšĺ
                        bpLog(`SUBSTITUTE: "${normalizedName}" â?"${substitute}" at (${x},${y},${z})`);
                        let subOk = false;
                        try {
                            subOk = await skills.placeBlock(agent.bot, substitute, x, y, z);
                        } catch (e) {
                            bpLog(`substitute placeBlock threw: ${e.message}`);
                            subOk = false;
                        }
                        if (!subOk) {
                            // ćżäťŁĺäšćžä¸ä¸ďźç?/setblock ĺĺş
                            agent.bot.chat(`/setblock ${Math.floor(x)} ${Math.floor(y)} ${Math.floor(z)} ${substitute}`);
                            await new Promise(resolve => setTimeout(resolve, 50));
                        }
                        // 通知 mod 标记此方块为已完成，防止重复返回
                        try {
                            const bpId3 = res.blueprint_id || id;
                            await client.skip(bpId3, x, y, z, name);
                        } catch (_) {}
                        placed++;
                        consecutiveFails = 0;
                        continue;
                    }
                    // ćżäťŁĺćŻ air ćć˛ĄććżäťŁĺ â?čˇłčż
                    const bpId = res.blueprint_id || id;
                    const skipRes = await client.skip(bpId, x, y, z, name);
                    bpLog(`BLACKLIST skip: "${name}" at (${x},${y},${z}) â?skipRes=${JSON.stringify(skipRes)}`);
                    // ĺŚć skip čŻˇćąĺ¤ąč´Ľďźmod ć˛Ąć /skip čˇŻçąďźďźćŹĺ°čŽ°ä˝čżä¸Şĺć 
                    if (!skipRes || skipRes.ok === false) {
                        bpLog(`skip FAILED for (${x},${y},${z}), adding to localSkippedCoords`);
                        localSkippedCoords.add(thisCoord);
                        // air çąťćšĺä¸éčŚ?setblockďźç´ćĽçŽčˇłčż
                        if (normalizedName !== 'air' && normalizedName !== 'cave_air' && normalizedName !== 'void_air') {
                            agent.bot.chat(`/setblock ${Math.floor(x)} ${Math.floor(y)} ${Math.floor(z)} air`);
                            await new Promise(resolve => setTimeout(resolve, 50));
                        }
                    }
                    skipped++;
                    continue;
                }

                // validate coords are finite
                if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) {
                    skills.log(agent.bot, `[čŻć­] modčżĺçĺć ć ć? (${x},${y},${z}). čĺžoriginĺŻč˝ćŻundefinedďźčŻˇćŁćĽä¸äź ćśćŻĺŚäź äşĺć ă`);
                    break;
                }
                // skip already-failed coords this round
                const coordKey = `${x},${y},${z}`;
                if (failedCoords.has(coordKey)) {
                    bpLog(`skipping already-failed coord ${coordKey}, using /setblock fallback`);
                    agent.bot.chat(`/setblock ${Math.floor(x)} ${Math.floor(y)} ${Math.floor(z)} ${name}`);
                    await new Promise(resolve => setTimeout(resolve, 50));
                    placed++;
                    consecutiveFails = 0;
                    continue;
                }
                // ——— 距离判定：太远直接 /tp（简单可靠，避免 pathfinder 在地形里迂回） ———
                const dx = pos.x - x, dy = pos.y - y, dz = pos.z - z;
                const horizDist = Math.sqrt(dx*dx + dz*dz);
                const vertDist  = Math.abs(dy);
                if (horizDist > 3.0) {
                    const tx = Math.floor(x), ty = Math.floor(y), tz = Math.floor(z);
                    bpLog(`MOVE: horiz=${horizDist.toFixed(1)} vert=${vertDist.toFixed(1)}, /tp to (${tx},${ty},${tz})`);
                    agent.bot.chat(`/tp @s ${(tx+0.5).toFixed(1)} ${ty.toFixed(1)} ${(tz+0.5).toFixed(1)}`);
                    await new Promise(r => setTimeout(r, 150));
                }
                // 朝目标看一眼（增加真实感 + 帮 mineflayer 定位朝向）
                try {
                    const { Vec3 } = await import('vec3');
                    await agent.bot.lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5), true);
                } catch (_) {}

                skills.log(agent.bot, `Placing ${name} at (${x}, ${y}, ${z}) [cell ${res.placed_so_far ?? '?'}/${res.total ?? '?'}]`);
                let ok = false;
                try {
                    ok = await skills.placeBlock(agent.bot, name, x, y, z);
                } catch (e) {
                    bpLog(`placeBlock threw for ${name} at (${x},${y},${z}): ${e.message}`);
                    ok = false;
                }
                if (!ok) {
                    // Fallback: 用 /setblock 命令直接放置（创造模式/OP权限下必成功）
                    // 垂直伸手（vertDist>4）属于预期失败，静默处理；其他情况记 warning
                    const inReach = horizDist <= 3 && vertDist <= 4;
                    if (inReach) {
                        bpLog(`placeBlock failed in-reach for ${name} at (${x},${y},${z}) horiz=${horizDist.toFixed(1)} vert=${vertDist.toFixed(1)}, /setblock fallback`);
                    }
                    agent.bot.chat(`/setblock ${Math.floor(x)} ${Math.floor(y)} ${Math.floor(z)} ${name}`);
                    await new Promise(resolve => setTimeout(resolve, 50));
                    failedCoords.add(coordKey);
                    placed++;
                    consecutiveFails = 0;
                    continue;
                }
                placed++;
                consecutiveFails = 0;
            }
            bpLog(`âââ?modBuildNext END âââ?id="${id}", placed=${placed}, skipped=${skipped}, consecutiveFails=${consecutiveFails}, failedCoords=${failedCoords.size}, runtimeBlacklist=[${[...runtimeBlacklist].join(',')}]`);
            skills.log(agent.bot, `Done. Placed ${placed} block(s) for blueprint "${id}".`);
            // čŞĺ¨čŻć­ďźĺŚćä¸ä¸Şćšĺé˝ć˛ĄćžćĺďźĺbugćĽĺ
            if (placed === 0 && n > 0) {
                try {
                    const { writeBugReport } = await import('../debug_journal.js');
                    const status = await client.status(id);
                    const list = await client.list();
                    const pos = agent.bot.entity.position;
                    // ćśéćĺä¸ćŹĄnextč°ç¨çĺĺ§čżĺ?                    const lastNext = await client.next(id, { x: pos.x, y: pos.y, z: pos.z });
                    const result = writeBugReport({
                        error: `modBuildNext placed=0 for blueprint "${id}"`,
                        context: `ĺ°čŻĺťşé čĺ?"${id}"ďźčŻˇćąäş ${n} ä¸Şćšĺä˝ä¸ä¸Şé˝ć˛ĄćžćĺăBotä˝ç˝Ž: (${Math.floor(pos.x)},${Math.floor(pos.y)},${Math.floor(pos.z)})`,
                        analysis: `ĺŻč˝ĺĺ : 1) modčżĺçĺć ĺ¤Şčżbotĺ¤ä¸ĺ?2) ćšĺĺç§°ä¸ĺšéMCçćŹ 3) čĺžć°ćŽä¸şçŠş(schemč§Łćĺ¤ąč´Ľ) 4) çŽć ä˝ç˝Žč˘Ťéťć?5) modçnextćĽĺŁčżĺdoneä˝total=0`,
                        suggestion: 'ćŁćĽčĺžćŻĺŚć­ŁçĄŽä¸äź?total>0)ďźćŁćĽbotćŻĺŚĺ¨čĺžéčżďźćŁćĽćšĺĺćŻĺŚćć',
                        rawData: {
                            blueprintStatus: status,
                            serverList: list,
                            lastNextResponse: lastNext,
                            botPosition: { x: Math.floor(pos.x), y: Math.floor(pos.y), z: Math.floor(pos.z) }
                        }
                    });
                    skills.log(agent.bot, `[čŞĺ¨čŻć­] ${result}`);
                } catch (e) {
                    bpLog(`[modBuildNext] writeBugReport failed: ${e.message}`);
                }
            }
        }, false, 30)
    },
    {
        name: '!modSetBlueprintMode',
        description: 'Switch a server-side blueprint between modes. "build" = bot constructs it, mod blocks bot from breaking finished cells. "remodel" = protection off so the plan can be edited live. "locked" = no one can break finished cells.',
        params: {
            'id': { type: 'string', description: 'Blueprint id.' },
            'mode': { type: 'string', description: '"build", "remodel", or "locked".' }
        },
        perform: async function (agent, id, mode) {
            const client = getModBlueprintClient();
            const res = await client.setMode(id, mode);
            if (!res) return `Mod query failed: ${client.lastError || 'no response'}`;
            if (res.ok === false) return `Set mode error: ${res.error || JSON.stringify(res)}`;
            return `Blueprint "${id}" mode is now ${res.mode || mode}.`;
        }
    },
    {
        name: '!modRemoveBlueprint',
        description: 'Remove a blueprint from the SynaBridge mod registry (no longer protected, no longer tracked).',
        params: {
            'id': { type: 'string', description: 'Blueprint id to remove.' }
        },
        perform: async function (agent, id) {
            const client = getModBlueprintClient();
            const res = await client.remove(id);
            if (!res) return `Mod query failed: ${client.lastError || 'no response'}`;
            if (res.ok === false) return `Remove error: ${res.error || JSON.stringify(res)}`;
            return `Blueprint "${id}" removed.`;
        }
    },
    {
        name: '!buildGoal',
        description: 'Enter focused building mode. The bot will import a blueprint (or use an existing one) and continuously build it block by block without getting distracted. Only responds to "stop" or "cancel" commands from the player. Use this when the player asks to build something.',
        params: {
            'blueprint_id': { type: 'string', description: 'The blueprint id to build (from !modListBlueprints), or a schematic name from !listLibrary to import first.' },
            'x': { type: 'int', description: 'X coordinate for origin (optional, defaults to bot position).', optional: true },
            'y': { type: 'int', description: 'Y coordinate for origin.', optional: true },
            'z': { type: 'int', description: 'Z coordinate for origin.', optional: true }
        },
        perform: async function (agent, blueprint_id, x, y, z) {
            const client = getModBlueprintClient();
            // Check if blueprint already exists on server
            let serverList = await client.list();
            const items = serverList?.blueprints || serverList?.items || [];
            const exists = items.some(b => b.id === blueprint_id);

            if (!exists) {
                // Try to import from local library
                const pos = agent.bot.entity.position;
                const origin = {
                    x: (x != null && x !== '' && Number.isFinite(Number(x))) ? Math.floor(Number(x)) : Math.floor(pos.x),
                    y: (y != null && y !== '' && Number.isFinite(Number(y))) ? Math.floor(Number(y)) : Math.floor(pos.y),
                    z: (z != null && z !== '' && Number.isFinite(Number(z))) ? Math.floor(Number(z)) : Math.floor(pos.z),
                };
                bpLog(`buildGoal: computed origin=(${origin.x},${origin.y},${origin.z}) from params x=${x},y=${y},z=${z}, botPos=(${Math.floor(pos.x)},${Math.floor(pos.y)},${Math.floor(pos.z)})`);
                try {
                    const result = await importAndUpload(blueprint_id, origin, client);
                    blueprint_id = result.id;
                    skills.log(agent.bot, `Imported "${blueprint_id}" (${result.blockCount} blocks). Starting focused build...`);
                } catch (e) {
                    return `Cannot find or import blueprint "${blueprint_id}": ${e.message}. Use !listLibrary or !modListBlueprints to see available options.`;
                }
            }

            // Start focused building goal
            const buildPrompt = `ăĺťşç­ä¸ćł¨ć¨Ąĺźăä˝ ć­Łĺ¨ĺťşé čĺ?"${blueprint_id}"ă\nč§ĺďź\n1. ćŻćŹĄĺŞć§čĄ?!modBuildNext "${blueprint_id}" 64\n2. ć§čĄĺŽĺćŁć?!modBlueprintStatus "${blueprint_id}" çćŻĺŚĺŽć\n3. ĺŚćčżć˛ĄĺŽćďźçť§çť?!modBuildNext\n4. ĺŽćĺć§čĄ?!endGoal\n5. ä¸čŚĺäťťä˝ĺśäťäşćďźä¸čŚé˛čďźä¸čŚčľ°çĽ\n6. ĺŚććžç˝Žĺ¤ąč´Ľďźĺ !goToCoordinates ĺ°ĺ¤ąč´Ľä˝ç˝ŽéčżĺéčŻ`;
            agent.self_prompter.start(buildPrompt);
            return `čżĺĽĺťşç­ä¸ćł¨ć¨Ąĺźďźĺźĺ§ĺťşé?"${blueprint_id}"ăčŻ´"ĺ?ćç¨ !stop ĺŻäťĽä¸­ć­ă`;
        }
    },
    {
        name: '!listLibrary',
        description: 'List all schematic files available in the local blueprint library (.litematic, .schem, .nbt, .json). These can be imported and built with !importBlueprint.',
        params: {},
        perform: async function (agent) {
            return listAvailableBlueprints();
        }
    },
    {
        name: '!importBlueprint',
        description: 'Import a schematic file from the local blueprint library and upload it to the SynaBridge mod as a server-side blueprint. Supports .litematic, .schem, .nbt, and .json formats. After import, use !modBuildNext to construct it block by block.',
        params: {
            'name': { type: 'string', description: 'Name of the schematic file (without extension) from the library.' },
            'x': { type: 'int', description: 'X coordinate for the blueprint origin. If not given, uses bot position.', optional: true },
            'y': { type: 'int', description: 'Y coordinate for the blueprint origin.', optional: true },
            'z': { type: 'int', description: 'Z coordinate for the blueprint origin.', optional: true }
        },
        perform: runAsAction(async (agent, name, x, y, z) => {
            const pos = agent.bot.entity.position;
            const origin = {
                x: (x != null && !isNaN(x)) ? Math.floor(x) : Math.floor(pos.x),
                y: (y != null && !isNaN(y)) ? Math.floor(y) : Math.floor(pos.y),
                z: (z != null && !isNaN(z)) ? Math.floor(z) : Math.floor(pos.z),
            };
            const client = getModBlueprintClient();
            try {
                const result = await importAndUpload(name, origin, client);
                skills.log(agent.bot, `Blueprint "${result.id}" imported and uploaded (${result.blockCount} blocks at ${origin.x},${origin.y},${origin.z}). Use !modBuildNext "${result.id}" to start building.`);
            } catch (e) {
                skills.log(agent.bot, `Import failed: ${e.message}`);
            }
        }, false, 5)
    },
    {
        name: '!searchBlueprints',
        description: 'Search the online blueprint library by keyword (e.g. "medieval house", "castle", "farm"). Shows matching blueprints that can be downloaded with !downloadBlueprint.',
        params: {
            'keyword': { type: 'string', description: 'Search keyword (e.g. medieval, castle, farm, tower, ship, modern).' }
        },
        perform: async function (agent, keyword) {
            try {
                const { searchOnlineIndex } = await import('../library/blueprint_downloader.js');
                const results = searchOnlineIndex(keyword);
                if (results.length === 0) return `no online blueprints found matching "${keyword}". Try: medieval, castle, farm, tower, ship, modern, fantasy, cottage.`;
                const lines = results.slice(0, 8).map(e =>
                    `  ${e.name} [${e.size_estimate}] (${e.tags.slice(0, 3).join(', ')}): ${e.description}`
                );
                return `Found ${results.length} blueprint(s) matching "${keyword}":\n${lines.join('\n')}\n\nUse !downloadBlueprint "<name>" to download one.`;
            } catch (e) {
                return `Search failed: ${e.message}`;
            }
        }
    },
    {
        name: '!downloadBlueprint',
        description: 'Download a blueprint from the online library by keyword, then import and upload it to the SynaBridge mod ready for building. Combines search + download + import in one step.',
        params: {
            'keyword': { type: 'string', description: 'Search keyword to find the blueprint (e.g. "medieval house", "castle").' },
            'x': { type: 'int', description: 'X coordinate for origin (optional, defaults to bot position).', optional: true },
            'y': { type: 'int', description: 'Y coordinate for origin.', optional: true },
            'z': { type: 'int', description: 'Z coordinate for origin.', optional: true }
        },
        perform: runAsAction(async (agent, keyword, x, y, z) => {
            const pos = agent.bot.entity.position;
            const origin = {
                x: (x != null && !isNaN(x)) ? Math.floor(x) : Math.floor(pos.x),
                y: (y != null && !isNaN(y)) ? Math.floor(y) : Math.floor(pos.y),
                z: (z != null && !isNaN(z)) ? Math.floor(z) : Math.floor(pos.z),
            };
            try {
                skills.log(agent.bot, `Searching online library for "${keyword}"...`);
                const { entry, localPath } = await searchAndDownload(keyword);
                skills.log(agent.bot, `Downloaded "${entry.name}" (${entry.size_estimate}). Importing to mod...`);
                const client = getModBlueprintClient();
                const result = await importAndUpload(entry.name, origin, client);
                skills.log(agent.bot, `Blueprint "${result.id}" ready! ${result.blockCount} blocks at (${origin.x},${origin.y},${origin.z}). Use !buildGoal "${result.id}" to start building.`);
            } catch (e) {
                skills.log(agent.bot, `Download/import failed: ${e.message}`);
            }
        }, false, 10)
    },
    {
        name: '!randomBlueprint',
        description: 'Download a random blueprint from the online library and prepare it for building. Optionally filter by size: tiny, small, medium, large.',
        params: {
            'size': { type: 'string', description: 'Size filter: "tiny", "small", "medium", or "large". Optional.', optional: true },
            'x': { type: 'int', description: 'X coordinate for origin (optional).', optional: true },
            'y': { type: 'int', description: 'Y coordinate for origin.', optional: true },
            'z': { type: 'int', description: 'Z coordinate for origin.', optional: true }
        },
        perform: runAsAction(async (agent, size, x, y, z) => {
            const pos = agent.bot.entity.position;
            const origin = {
                x: (x != null && !isNaN(x)) ? Math.floor(x) : Math.floor(pos.x),
                y: (y != null && !isNaN(y)) ? Math.floor(y) : Math.floor(pos.y),
                z: (z != null && !isNaN(z)) ? Math.floor(z) : Math.floor(pos.z),
            };
            try {
                skills.log(agent.bot, `Picking a random blueprint${size ? ` (size: ${size})` : ''}...`);
                const { entry, localPath } = await downloadRandom(size || undefined);
                skills.log(agent.bot, `Got "${entry.name}" (${entry.size_estimate}, ${entry.tags.join(', ')}). Importing...`);
                const client = getModBlueprintClient();
                const result = await importAndUpload(entry.name, origin, client);
                skills.log(agent.bot, `Blueprint "${result.id}" ready! ${result.blockCount} blocks. Use !buildGoal "${result.id}" to start building.`);
            } catch (e) {
                skills.log(agent.bot, `Random blueprint failed: ${e.message}`);
            }
        }, false, 10)
    },
    {
        name: '!saveAsBlueprint',
        description: 'Save the most recent !scanArea snapshot as a named blueprint file in the local library. Useful for copying existing builds to reuse later.',
        params: {
            'name': { type: 'string', description: 'Name for the saved blueprint (e.g. "my_house", "cool_tower").' }
        },
        perform: async function (agent, name) {
            const snap = areaScanner.getLastScan(agent.bot);
            if (!snap) return 'No snapshot found. Run !scanArea or !scanAroundMe first to capture a build.';
            try {
                const filePath = saveScannedBlueprint(name, snap);
                return `Blueprint saved as "${name}" (${snap.blocks ? snap.blocks.length : '?'} blocks). You can import it later with !importBlueprint "${name}".`;
            } catch (e) {
                return `Save failed: ${e.message}`;
            }
        }
    },
    {
        name: '!listOnlineBlueprints',
        description: 'List all blueprints available for download from the online library.',
        params: {},
        perform: async function (agent) {
            try {
                return listOnlineBlueprints();
            } catch (e) {
                return `Failed to list online blueprints: ${e.message}`;
            }
        }
    },
    {
        name: '!replaceBlocks',
        description: 'Replace all blocks of one type with another in a cube around the bot. Uses /fill ... replace, so it is instant and works for huge volumes (e.g. swapping oak_leaves to cherry_leaves on a finished house). Defaults to a 30-block radius cube centered on the bot.',
        params: {
            'from_block': { type: 'BlockName', description: 'Block name to replace (e.g. oak_leaves, oak_planks, cobblestone). minecraft: prefix optional.' },
            'to_block': { type: 'BlockName', description: 'Block name to replace with (e.g. cherry_leaves, stone, glass).' },
            'radius': { type: 'int', description: 'Half-extent of the cube in blocks. Final volume is (2r+1)^3. Default 30. Max 80.', optional: true, domain: [1, 80] }
        },
        perform: async function (agent, from_block, to_block, radius) {
            const r = Math.max(1, Math.min(80, Math.floor(radius || 30)));
            const pos = agent.bot.entity.position;
            const cx = Math.floor(pos.x), cy = Math.floor(pos.y), cz = Math.floor(pos.z);
            const x1 = cx - r, y1 = Math.max(-64, cy - r), z1 = cz - r;
            const x2 = cx + r, y2 = Math.min(320, cy + r), z2 = cz + r;
            const norm = s => String(s || '').replace(/^minecraft:/, '').trim();
            const from = norm(from_block);
            const to = norm(to_block);
            if (!from || !to) return 'from_block and to_block are required.';
            const cmd = `/fill ${x1} ${y1} ${z1} ${x2} ${y2} ${z2} ${to} replace ${from}`;
            bpLog(`!replaceBlocks: ${cmd}`);
            agent.bot.chat(cmd);
            await new Promise(r => setTimeout(r, 200));
            return `Replacing ${from} -> ${to} in cube (${x1},${y1},${z1})..(${x2},${y2},${z2}) [r=${r}]. Command sent to server.`;
        }
    },
    {
        name: '!fillArea',
        description: 'Fill or replace a 3D box of the world with a given block. Wraps the vanilla /fill command. Useful for clearing space (use air), creating walls/floors, or bulk-replacing materials in a precise box. The bot must have OP/cheats permission for /fill to work.',
        params: {
            'x1': { type: 'int', description: 'First corner X.' },
            'y1': { type: 'int', description: 'First corner Y.' },
            'z1': { type: 'int', description: 'First corner Z.' },
            'x2': { type: 'int', description: 'Opposite corner X.' },
            'y2': { type: 'int', description: 'Opposite corner Y.' },
            'z2': { type: 'int', description: 'Opposite corner Z.' },
            'block': { type: 'BlockName', description: 'Block to fill with (e.g. air, stone, glass).' },
            'replace_filter': { type: 'BlockName', description: 'If given, only replace this block type (e.g. oak_leaves). Omit to overwrite everything.', optional: true }
        },
        perform: async function (agent, x1, y1, z1, x2, y2, z2, block, replace_filter) {
            const norm = s => String(s || '').replace(/^minecraft:/, '').trim();
            const b = norm(block);
            if (!b) return 'block is required.';
            const ax1 = Math.floor(x1), ay1 = Math.floor(y1), az1 = Math.floor(z1);
            const ax2 = Math.floor(x2), ay2 = Math.floor(y2), az2 = Math.floor(z2);
            const filter = replace_filter ? norm(replace_filter) : null;
            const cmd = filter
                ? `/fill ${ax1} ${ay1} ${az1} ${ax2} ${ay2} ${az2} ${b} replace ${filter}`
                : `/fill ${ax1} ${ay1} ${az1} ${ax2} ${ay2} ${az2} ${b}`;
            bpLog(`!fillArea: ${cmd}`);
            agent.bot.chat(cmd);
            await new Promise(r => setTimeout(r, 200));
            return `Sent: ${cmd}`;
        }
    },
    {
        name: '!reportBug',
        description: 'Record a bug/error to the debug journal for the developer to review later. Use this when you encounter an error you cannot solve, or when something unexpected happens.',
        params: {
            'error': { type: 'string', description: 'The error message or description of what went wrong.' },
            'context': { type: 'string', description: 'What you were doing when the error occurred.' },
            'analysis': { type: 'string', description: 'Your understanding of why this happened and possible cause.' },
            'suggestion': { type: 'string', description: 'Your suggestion for how to fix it (optional).', optional: true }
        },
        perform: async function (agent, error, context, analysis, suggestion) {
            try {
                const msg = writeBugReport({ error, context, analysis, suggestion });
                return msg;
            } catch (e) {
                return `Failed to write bug report: ${e.message}`;
            }
        }
    },
];
