import { getBlockId, getItemId } from "../../utils/mcdata.js";
import { actionsList } from './actions.js';
import { queryList } from './queries.js';

let suppressNoDomainWarning = true;

const commandList = queryList.concat(actionsList);
const commandMap = {};
for (let command of commandList) {
    commandMap[command.name] = command;
}

export function getCommand(name) {
    return commandMap[name];
}

export function blacklistCommands(commands) {
    const unblockable = ['!stop', '!stats', '!inventory', '!goal'];
    for (let command_name of commands) {
        if (unblockable.includes(command_name)){
            console.warn(`Command ${command_name} is unblockable`);
            continue;
        }
        delete commandMap[command_name];
        delete commandList.find(command => command.name === command_name);
    }
}

const commandRegex = /!(\w+)(?:\(((?:-?\d+(?:\.\d+)?|true|false|"[^"]*")(?:\s*,\s*(?:-?\d+(?:\.\d+)?|true|false|"[^"]*"))*)\))?/
const argRegex = /-?\d+(?:\.\d+)?|true|false|"[^"]*"/g;

export function containsCommand(message) {
    const commandMatch = message.match(commandRegex);
    if (commandMatch)
        return "!" + commandMatch[1];
    return null;
}

export function commandExists(commandName) {
    if (!commandName.startsWith("!"))
        commandName = "!" + commandName;
    return commandMap[commandName] !== undefined;
}

/**
 * Converts a string into a boolean.
 * @param {string} input
 * @returns {boolean | null} the boolean or `null` if it could not be parsed.
 * */
function parseBoolean(input) {
    switch(input.toLowerCase()) {
        case 'false': //These are interpreted as flase;
        case 'f':
        case '0':
        case 'off':
            return false;
        case 'true': //These are interpreted as true;
        case 't':
        case '1':
        case 'on':
            return true;
        default:
            return null;
    }
}

/**
 * @param {number} value - the value to check
 * @param {number} lowerBound
 * @param {number} upperBound
 * @param {string} endpointType - The type of the endpoints represented as a two character string. `'[)'` `'()'` 
 */
function checkInInterval(number, lowerBound, upperBound, endpointType) {
    switch (endpointType) {
        case '[)':
            return lowerBound <= number && number < upperBound;
        case '()':
            return lowerBound < number && number < upperBound;
        case '(]':
            return lowerBound < number && number <= upperBound;
        case '[]':
            return lowerBound <= number && number <= upperBound;
        default:
            throw new Error('Unknown endpoint type:', endpointType)
    }
}



// todo: handle arrays?
/**
 * Returns an object containing the command, the command name, and the comand parameters.
 * If parsing unsuccessful, returns an error message as a string.
 * @param {string} message - A message from a player or language model containing a command.
 * @returns {string | Object}
 */
export function parseCommandMessage(message) {
    const commandMatch = message.match(commandRegex);
    if (!commandMatch) return `Command is incorrectly formatted`;

    const commandName = "!"+commandMatch[1];

    let args;
    if (commandMatch[2]) args = commandMatch[2].match(argRegex);
    else args = [];

    const command = getCommand(commandName);
    if(!command) return `${commandName} is not a command.`

    const params = commandParams(command);
    const paramNames = commandParamNames(command);

    // optional 参数支持：必填数 ≤ args.length ≤ 总参数数
    const requiredCount = params.filter(p => !p.optional).length;
    if (args.length < requiredCount || args.length > params.length) {
        const expected = requiredCount === params.length
            ? `${params.length}`
            : `${requiredCount}-${params.length}`;
        return `Command ${command.name} was given ${args.length} args, but requires ${expected} args.`;
    }


    for (let i = 0; i < args.length; i++) {
        const param = params[i];
        //Remove any extra characters
        let arg = args[i].trim();
        if ((arg.startsWith('"') && arg.endsWith('"')) || (arg.startsWith("'") && arg.endsWith("'"))) {
            arg = arg.substring(1, arg.length-1);
        }
        
        //Convert to the correct type
        switch(param.type) {
            case 'int':
                arg = Number.parseInt(arg); break;
            case 'float':
                arg = Number.parseFloat(arg); break;
            case 'boolean':
                arg = parseBoolean(arg); break;
            case 'BlockName':
            case 'BlockOrItemName':
            case 'ItemName':
                if (arg.endsWith('plank') || arg.endsWith('seed'))
                    arg += 's'; // add 's' to for common mistakes like "oak_plank" or "wheat_seed"
            case 'string':
                break;
            default:
                throw new Error(`Command '${commandName}' parameter '${paramNames[i]}' has an unknown type: ${param.type}`);
        }
        if(arg === null || Number.isNaN(arg))
            return `Error: Param '${paramNames[i]}' must be of type ${param.type}.`

        if(typeof arg === 'number') { //Check the domain of numbers
            const domain = param.domain;
            if(domain) {
                /**
                 * Javascript has a built in object for sets but not intervals.
                 * Currently the interval (lowerbound,upperbound] is represented as an Array: `[lowerbound, upperbound, '(]']`
                 */
                if (!domain[2]) domain[2] = '[)'; //By default, lower bound is included. Upper is not.

                if(!checkInInterval(arg, ...domain)) {
                    return `Error: Param '${paramNames[i]}' must be an element of ${domain[2][0]}${domain[0]}, ${domain[1]}${domain[2][1]}.`;
                    //Alternatively arg could be set to the nearest value in the domain.
                }
            } else if (!suppressNoDomainWarning) {
                console.warn(`Command '${commandName}' parameter '${paramNames[i]}' has no domain set. Expect any value [-Infinity, Infinity].`)
                suppressNoDomainWarning = true; //Don't spam console. Only give the warning once.
            }
        } else if(param.type === 'BlockName') { //Check that there is a block with this name
            if(getBlockId(arg) == null) return  `Invalid block type: ${arg}.`
        } else if(param.type === 'ItemName') { //Check that there is an item with this name
            if(getItemId(arg) == null) return `Invalid item type: ${arg}.`
        } else if(param.type === 'BlockOrItemName') {
            if(getBlockId(arg) == null && getItemId(arg) == null) return  `Invalid block or item type: ${arg}.`
        }
        args[i] = arg;
    }
    
    return { commandName, args };
}

export function truncCommandMessage(message) {
    const commandMatch = message.match(commandRegex);
    if (commandMatch) {
        return message.substring(0, commandMatch.index + commandMatch[0].length);
    }
    return message;
}

export function isAction(name) {
    return actionsList.find(action => action.name === name) !== undefined;
}

/**
 * @param {Object} command
 * @returns {Object[]} The command's parameters.
 */
function commandParams(command) {
    if (!command.params)
        return [];
    return Object.values(command.params);
}

/**
 * @param {Object} command
 * @returns {string[]} The names of the command's parameters.
 */
function commandParamNames(command) {
    if (!command.params)
        return [];
    return Object.keys(command.params);
}

function numParams(command) {
    return commandParams(command).length;
}

const coreCommandNames = ['!stats', '!inventory', '!nearbyBlocks', '!entities', '!modFindBlock', '!modFindEntity', '!synaHorror', '!synaHorrorTakeover', '!synaHorrorChallenge', '!synaWait', '!mcRunPlan', '!mcRunStatus', '!mcRunStart', '!stop', '!goal', '!newAction', '!help'];
const horrorCommandNames = ['!synaHorror', '!synaHorrorTakeover', '!synaHorrorChallenge', '!synaWait', '!stop', '!stats', '!entities', '!goToPlayer', '!followPlayer', '!modFindEntity', '!help'];

const commandCategories = {
    movement: {
        keywords: ['go', 'move', 'follow', 'come', 'surface', 'up', 'down', 'stuck', 'unstuck', 'position', 'coordinates', 'player', '上去', '地表', '过来', '跟随', '移动', '坐标', '卡住', '出来'],
        commands: ['!goToPlayer', '!followPlayer', '!goToCoordinates', '!searchForBlock', '!searchForEntity', '!moveAway', '!rememberHere', '!goToRememberedPlace', '!digDown', '!goToSurface', '!lookAtPlayer', '!lookAtPosition']
    },
    mining: {
        keywords: ['mine', 'mining', 'dig', 'collect', 'block', 'ore', 'coal', 'iron', 'diamond', 'deepslate', 'cave', '挖', '采', '矿', '煤', '铁', '钻石', '洞穴', '深板岩'],
        commands: ['!collectBlocks', '!gatherFlint', '!searchForBlock', '!modFindBlock', '!digDown', '!goToSurface', '!newAction', '!nearbyBlocks', '!inventory']
    },
    crafting: {
        keywords: ['craft', 'make', 'recipe', 'table', 'pickaxe', 'sword', 'armor', 'bucket', 'shield', '合成', '制作', '做', '工作台', '镐', '剑', '盔甲', '桶', '盾'],
        commands: ['!craftRecipe', '!gatherFlint', '!getCraftingPlan', '!craftable', '!inventory', '!collectBlocks', '!newAction']
    },
    smelting: {
        keywords: ['smelt', 'furnace', 'fuel', 'cook', 'charcoal', 'raw', '烧', '熔炉', '燃料', '木炭', '烧铁', '烧制'],
        commands: ['!smeltItem', '!clearFurnace', '!craftRecipe', '!collectBlocks', '!inventory', '!nearbyBlocks']
    },
    combat: {
        keywords: ['attack', 'fight', 'kill', 'mob', 'zombie', 'skeleton', 'creeper', 'spider', 'enderman', 'defend', '怪', '攻击', '战斗', '僵尸', '骷髅', '苦力怕', '蜘蛛', '末影人', '防御'],
        commands: ['!attack', '!attackPlayer', '!equip', '!consume', '!moveAway', '!entities', '!modFindEntity', '!synaHorror', '!synaHorrorTakeover', '!synaHorrorChallenge', '!setMode']
    },
    survival: {
        keywords: ['health', 'hunger', 'eat', 'food', 'sleep', 'bed', 'night', 'danger', '生命', '血', '饥饿', '吃', '食物', '睡觉', '床', '晚上', '危险'],
        commands: ['!stats', '!inventory', '!consume', '!goToBed', '!setMode', '!moveAway', '!attack']
    },
    dragonrun: {
        keywords: ['通关', '末影龙', '末地', '要塞', '下界', '烈焰棒', '末影珍珠', '末影之眼', 'dragon', 'ender', 'end portal', 'nether', 'blaze', 'stronghold', 'speedrun', 'complete minecraft'],
        commands: ['!mcRunPlan', '!mcRunStatus', '!mcRunStart', '!mcRunStop', '!taskList', '!taskDone', '!inventory', '!nearbyBlocks', '!entities', '!collectBlocks', '!gatherFlint', '!craftRecipe', '!smeltItem', '!equip', '!consume', '!attack', '!searchForBlock', '!searchForEntity', '!makeObsidian', '!buildNetherPortal', '!useNetherPortal', '!findEndPortal', '!enterEndPortal', '!newAction']
    },
    storage: {
        keywords: ['chest', 'store', 'take', 'put', 'deposit', '箱子', '存', '拿', '放入', '取出'],
        commands: ['!viewChest', '!putInChest', '!takeFromChest', '!discard', '!modFindBlock', '!inventory']
    },
    trading: {
        keywords: ['villager', 'trade', 'emerald', '村民', '交易', '绿宝石'],
        commands: ['!showVillagerTrades', '!tradeWithVillager', '!searchForEntity', '!entities']
    },
    building: {
        keywords: ['build', 'place', 'torch', 'bridge', 'portal', 'nether', 'structure', '搭', '放', '火把', '桥', '传送门', '下界', '建筑'],
        commands: ['!placeHere', '!makeObsidian', '!newAction', '!collectBlocks', '!gatherFlint', '!craftRecipe', '!useOn', '!nearbyBlocks']
    },
    info: {
        keywords: ['wiki', 'help', 'command', 'mode', 'blueprint', '计划', '帮助', '命令', '模式', '蓝图', '查'],
        commands: ['!help', '!searchWiki', '!modes', '!getCraftingPlan', '!savedPlaces']
    }
};

function scoreCommandCategories(context) {
    const text = (context || '').toLowerCase();
    const scored = [];
    for (const [category, spec] of Object.entries(commandCategories)) {
        let score = 0;
        for (const keyword of spec.keywords) {
            if (text.includes(keyword.toLowerCase())) score += 1;
        }
        if (score > 0) scored.push({ category, score });
    }
    return scored.sort((a, b) => b.score - a.score).map(item => item.category);
}

function isHorrorContext(agent, context) {
    const text = (context || '').toLowerCase();
    const horrorSummary = String(agent?._lastHorrorSummary || '').toLowerCase();
    return text.includes('horror')
        || text.includes('追杀')
        || text.includes('求饶')
        || text.includes('原谅')
        || horrorSummary.includes('stage=storm')
        || horrorSummary.includes('stage=countdown')
        || horrorSummary.includes('stage=hunting');
}

function selectCommandsForRetrieval(agent, context) {
    if (isHorrorContext(agent, context)) {
        return { commandNames: new Set(horrorCommandNames), categories: ['horror-minimal'] };
    }
    const maxCategories = agent?.settings?.command_docs_max_categories ?? 4;
    const selected = new Set(coreCommandNames);
    const categories = scoreCommandCategories(context).slice(0, maxCategories);
    if (categories.length === 0) categories.push('movement', 'mining', 'crafting', 'survival');
    for (const category of categories) {
        for (const commandName of commandCategories[category].commands) selected.add(commandName);
    }
    return { commandNames: selected, categories };
}

export async function executeCommand(agent, message) {
    let parsed = parseCommandMessage(message);
    if (typeof parsed === 'string')
        return parsed; //The command was incorrectly formatted or an invalid input was given.
    else {
        console.log('parsed command:', parsed);
        const command = getCommand(parsed.commandName);
        const params = commandParams(command);
        const requiredCount = params.filter(p => !p.optional).length;
        let numArgs = 0;
        if (parsed.args) {
            numArgs = parsed.args.length;
        }
        if (numArgs < requiredCount || numArgs > params.length) {
            const expected = requiredCount === params.length
                ? `${params.length}`
                : `${requiredCount}-${params.length}`;
            return `Command ${command.name} was given ${numArgs} args, but requires ${expected} args.`;
        }
        const result = await command.perform(agent, ...parsed.args);
        return result;
    }
}

export function getCommandDocs(agent, context='') {
    const syntaxMode = agent?.settings?.show_command_syntax ?? agent?.profile?.show_command_syntax ?? 'full';
    if (syntaxMode === 'none') {
        return `\n*COMMAND DOCS\nCommands are available, but detailed command syntax is hidden to save tokens. Use known commands only, one per response, with double-quoted string arguments.\n*\n`;
    }

    const typeTranslations = {
        //This was added to keep the prompt the same as before type checks were implemented.
        //If the language model is giving invalid inputs changing this might help.
        'float':             'number',
        'int':               'number',
        'BlockName':         'string',
        'ItemName':          'string',
        'BlockOrItemName':   'string',
        'boolean':           'bool'
    }
    const retrieval = syntaxMode === 'retrieval' ? selectCommandsForRetrieval(agent, context) : null;
    let docs = `\n*COMMAND DOCS\n You can use the following commands to perform actions and get information about the world. 
    Use the commands with the syntax: !commandName or !commandName("arg1", 1.2, ...) if the command takes arguments.\n
    Do not use codeblocks. Use double quotes for strings. Only use one command in each response, trailing commands and comments will be ignored.\n`;
    if (retrieval) {
        docs += `Retrieval mode is enabled to save tokens. Showing core commands plus relevant categories: ${retrieval.categories.join(', ')}. In horror-minimal mode, prioritize voice, mercy, target, and !synaHorror control instead of broad tool use. If the listed commands are insufficient, use !help or !newAction for custom behavior.\n`;
    }
    for (let command of commandList) {
        if (agent.blocked_actions.includes(command.name)) {
            continue;
        }
        if (retrieval && !retrieval.commandNames.has(command.name)) {
            continue;
        }
        if (syntaxMode === 'shortened') {
            const params = command.params
                ? '(' + Object.entries(command.params).map(([name, spec]) => `${name}: ${typeTranslations[spec.type]??spec.type}`).join(', ') + ')'
                : '';
            docs += `${command.name}${params}: ${command.description}\n`;
            continue;
        }
        docs += command.name + ': ' + command.description + '\n';
        if (command.params) {
            docs += 'Params:\n';
            for (let param in command.params) {
                docs += `${param}: (${typeTranslations[command.params[param].type]??command.params[param].type}) ${command.params[param].description}\n`;
            }
        }
    }
    return docs + '*\n';
}

