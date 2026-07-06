import minecraftData from 'minecraft-data';
import settings from '../agent/settings.js';
import { createBot } from 'mineflayer';
import prismarine_items from 'prismarine-item';
import { pathfinder } from 'mineflayer-pathfinder';
import { plugin as pvp } from 'mineflayer-pvp';
import { plugin as collectblock } from 'mineflayer-collectblock';
import { plugin as autoEat } from 'mineflayer-auto-eat';
import plugin from 'mineflayer-armor-manager';
import * as modRegistry from '../agent/mod_registry.js';
const armorManager = plugin;
let mc_version = settings.minecraft_version;
let mcdata = null;
let Item = null;

/**
 * @typedef {string} ItemName
 * @typedef {string} BlockName
*/

export const WOOD_TYPES = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry'];
export const MATCHING_WOOD_BLOCKS = [
    'log',
    'planks',
    'sign',
    'boat',
    'fence_gate',
    'door',
    'fence',
    'slab',
    'stairs',
    'button',
    'pressure_plate',
    'trapdoor'
]
export const WOOL_COLORS = [
    'white',
    'orange',
    'magenta',
    'light_blue',
    'yellow',
    'lime',
    'pink',
    'gray',
    'light_gray',
    'cyan',
    'purple',
    'blue',
    'brown',
    'green',
    'red',
    'black'
]


export function initBot(username) {
    let port = settings.port;
    if (port == null || port < 0 || port >= 65536) {
        console.warn(`[initBot] Invalid port (${port}), falling back to 25565`);
        port = 25565;
    }
    const options = {
        username: username,
        host: settings.host || '127.0.0.1',
        port: port,
        auth: settings.auth || 'offline',
        version: mc_version,
        checkTimeoutInterval: 60000,  // 60s keep-alive check (default 30s) — reduces disconnects on slow servers
    }
    if (!mc_version || mc_version === "auto") {
        delete options.version;
    }

    const bot = createBot(options);

    // ─────────────────────────────────────────────────────────────────────
    // Forge / NeoForge 协议分支判断
    // ─────────────────────────────────────────────────────────────────────
    // FML3 hack（\0FML3\0 marker + login_plugin_request 手动握手）只适用于
    // Forge 1.18 ~ 1.20.1。1.20.2 起 NeoForge 引入 configuration state，
    // mineflayer 4.33 + minecraft-protocol 1.60 原生支持，无需任何 hack。
    // 1.21.x 服务端如果收到 \0FML3\0 marker 会直接断连，必须跳过。
    function isFml3Version(v) {
        if (!v || v === 'auto') return false;
        const m = String(v).match(/^1\.(\d+)(?:\.(\d+))?/);
        if (!m) return false;
        const minor = parseInt(m[1]);
        const patch = parseInt(m[2] || '0');
        if (minor < 18) return false;
        if (minor === 20 && patch >= 2) return false; // 1.20.2+ → modern
        if (minor > 20) return false;                  // 1.21+   → modern
        return true; // 1.18.x ~ 1.20.1
    }
    const useFml3Hack = isFml3Version(mc_version);
    console.log(`[mcdata] Protocol mode: ${useFml3Hack ? 'FML3 (Forge 1.20.1)' : 'modern (vanilla / NeoForge 1.20.2+ configuration state)'} for version=${mc_version}`);

    // --- Forge FML3 handshake injection ---
    // Forge 1.20.1 (fmlNetworkVersion=3) requires the handshake serverHost field
    // to contain "\0FML3\0" suffix, otherwise it resets the connection immediately.
    // We intercept the set_protocol packet and append the marker so vanilla
    // mineflayer can pass the initial handshake gate. The FmlBypass server-side mod
    // then handles the channel validation phase.
    if (useFml3Hack && bot._client && bot._client.write) {
        const _origWrite = bot._client.write.bind(bot._client);
        let fmlInjected = false;
        bot._client.write = function(name, data) {
            if (!fmlInjected && name === 'set_protocol') {
                fmlInjected = true;
                if (data && data.serverHost && !data.serverHost.includes('\0FML3\0')) {
                    data.serverHost = data.serverHost + '\0FML3\0';
                    console.log('[FML3] Injected FML3 marker into handshake serverHost');
                }
            }
            return _origWrite(name, data);
        };
    }

    // --- Forge login_plugin_request handler (FML3 only) ---
    // During login, Forge 1.20.1 sends login_plugin_request packets for channel negotiation.
    // minecraft-protocol's pluginChannels.js registers a default handler that sends
    // an empty "not understood" response. We must remove it to avoid double-responses
    // which cause "unexpected_query_response" kicks from Forge.
    //
    // ⚠️ 1.21 / NeoForge 21 不能 removeAllListeners — minecraft-protocol 1.60 自带的
    // login_plugin_request handler 是 vanilla configuration state 的入口，删掉会卡死握手。
    if (useFml3Hack && bot._client) {
        bot._client.removeAllListeners('login_plugin_request');
    }
    // === FML3 Handshake Handler for Forge 1.20.1 ===
    // Implements the full FML3 login handshake protocol so the bot can join
    // Forge servers (including those with Twilight Forest and other mods).
    // Protocol: first packet is S2CModList (discriminator=5), we reply with
    // C2SModListReply (discriminator=2, all counts=0). Subsequent packets
    // get an acknowledge (0x63). All wrapped in fml:loginwrapper format.
    if (useFml3Hack && bot._client) {
        // --- Varint helpers ---
        function fml3ReadVarInt(buffer, offset) {
            let value = 0, length = 0, currentByte;
            do {
                if (offset + length >= buffer.length) throw new Error('VarInt: buffer underflow');
                currentByte = buffer[offset + length];
                value |= (currentByte & 0x7F) << (length * 7);
                length++;
                if (length > 5) throw new Error('VarInt too big');
            } while (currentByte & 0x80);
            return { value, length };
        }
        function fml3WriteVarInt(value) {
            const bytes = [];
            do {
                let temp = value & 0x7F;
                value >>>= 7;
                if (value !== 0) temp |= 0x80;
                bytes.push(temp);
            } while (value !== 0);
            return Buffer.from(bytes);
        }
        function fml3ReadString(buffer, offset) {
            const { value: strLen, length: varIntLen } = fml3ReadVarInt(buffer, offset);
            const str = buffer.slice(offset + varIntLen, offset + varIntLen + strLen).toString('utf8');
            return { value: str, length: varIntLen + strLen };
        }
        function fml3WriteString(str) {
            const strBuf = Buffer.from(str, 'utf8');
            return Buffer.concat([fml3WriteVarInt(strBuf.length), strBuf]);
        }
        function fml3BuildLoginWrapper(channelName, payload) {
            const channelBuf = fml3WriteString(channelName);
            const lenBuf = fml3WriteVarInt(payload.length);
            return Buffer.concat([channelBuf, lenBuf, payload]);
        }
        function fml3ParseLoginWrapper(data) {
            let offset = 0;
            const { value: channelName, length: channelLen } = fml3ReadString(data, offset);
            offset += channelLen;
            const { value: payloadLen, length: plLen } = fml3ReadVarInt(data, offset);
            offset += plLen;
            const payload = data.slice(offset, offset + payloadLen);
            return { channelName, payload };
        }

        let fml3HandshakePhase = 'waiting';
        bot._client.on('login_plugin_request', (packet) => {
            console.log(`[FML3] login_plugin_request messageId=${packet.messageId} channel=${packet.channel}`);

            if (packet.channel === 'fml:loginwrapper' && packet.data) {
                try {
                    const { channelName, payload } = fml3ParseLoginWrapper(packet.data);
                    if (channelName === 'fml:handshake') {
                        // Read discriminator from payload
                        const { value: packetId } = fml3ReadVarInt(payload, 0);

                        if (fml3HandshakePhase === 'waiting' && packetId === 5) {
                            // S2CModList — reply with C2SModListReply (discriminator=2, all counts=0)
                            fml3HandshakePhase = 'modlist_received';
                            const reply = Buffer.concat([
                                fml3WriteVarInt(2),  // C2SModListReply discriminator
                                fml3WriteVarInt(0),  // mod count
                                fml3WriteVarInt(0),  // channel count
                                fml3WriteVarInt(0),  // registry count
                                fml3WriteVarInt(0),  // datapack registry count
                            ]);
                            const wrapped = fml3BuildLoginWrapper('fml:handshake', reply);
                            console.log(`[FML3] → C2SModListReply (${wrapped.length} bytes)`);
                            bot._client.write('login_plugin_response', {
                                messageId: packet.messageId,
                                data: wrapped
                            });
                            return;
                        } else {
                            // Subsequent packets (registry, config, etc.) — acknowledge
                            const ack = Buffer.from([0x63]); // discriminator 99 = acknowledge
                            const wrapped = fml3BuildLoginWrapper('fml:handshake', ack);
                            console.log(`[FML3] → Acknowledge for packetId=${packetId}`);
                            bot._client.write('login_plugin_response', {
                                messageId: packet.messageId,
                                data: wrapped
                            });
                            return;
                        }
                    }
                } catch (e) {
                    console.warn(`[FML3] Parse error: ${e.message}`);
                }
            }

            // Non-FML channels or parse failure: respond with undefined (not understood)
            console.log(`[FML3] → Not understood (channel=${packet.channel})`);
            bot._client.write('login_plugin_response', {
                messageId: packet.messageId,
                data: undefined
            });
        });
    }

    if (settings.position_packet_throttle) {
        // Throttle position packets for stricter Paper/Spigot servers only.
        // This is disabled by default for local LAN worlds because delayed position/look
        // packets can cause invalid movement kicks during sudden damage reactions.
        let lastPositionUpdate = 0;
        let pendingPositionPacket = null;
        const POSITION_THROTTLE_MS = settings.position_packet_throttle_ms || 50;
        const originalWrite = bot._client.write.bind(bot._client);
        bot._client.write = function(name, data) {
            if (name === 'position' || name === 'position_look' || name === 'look') {
                const now = Date.now();
                if (now - lastPositionUpdate < POSITION_THROTTLE_MS) {
                    if (pendingPositionPacket) {
                        clearTimeout(pendingPositionPacket);
                        pendingPositionPacket = null;
                    }
                    pendingPositionPacket = setTimeout(() => {
                        pendingPositionPacket = null;
                        lastPositionUpdate = Date.now();
                        originalWrite(name, data);
                    }, POSITION_THROTTLE_MS - (now - lastPositionUpdate));
                    return;
                }
                lastPositionUpdate = now;
                if (pendingPositionPacket) {
                    clearTimeout(pendingPositionPacket);
                    pendingPositionPacket = null;
                }
            }
            return originalWrite(name, data);
        };
    }

    // Suppress PartialReadError for non-critical packets
    // Paper servers sometimes send packets that node-minecraft-protocol
    // can't fully parse (scoreboard, resource_pack, custom_payload, etc.)
    // These errors crash the bot but the packets aren't needed for gameplay
    const originalEmit = bot._client.emit.bind(bot._client);
    bot._client.emit = function(event, ...args) {
        if (event === 'error' && args[0]) {
            const err = args[0];
            const errStr = err instanceof Error ? err.message : String(err);
            // Suppress parse errors from Forge mod packets (custom NBT, recipes, etc.)
            // These are non-critical: the bot still functions without parsing mod recipes
            if (errStr.includes('PartialReadError') || 
                errStr.includes('Read error') ||
                errStr.includes('Parse error for play.toClient')) {
                console.warn('[mcdata] Suppressed Forge parse error:', errStr.substring(0, 120));
                return true; // Swallow the error
            }
        }
        return originalEmit(event, ...args);
    };

    // --- Forge play-phase: respond to fml:handshake custom_payload (FML3 only) ---
    // After login, Forge 1.20.1 sends mod config sync via custom_payload on
    // channel "fml:handshake". The server may wait for acknowledgement before
    // proceeding. We auto-reply with an acknowledge (0x63) for each fml:handshake
    // payload received in play state.
    // 1.21 / NeoForge 21 用 neoforge:* channel 走 configuration state，不需要这段。
    if (useFml3Hack && bot._client) {
        bot._client.on('custom_payload', (packet) => {
            if (!packet || !packet.channel) return;
            const ch = packet.channel;
            // Respond to fml:handshake channel messages with acknowledge
            if (ch === 'fml:handshake' || ch === 'forge:handshake') {
                try {
                    // Build acknowledge payload: varint(1) for channel name length prefix + "fml:handshake" + varint(1) for payload + 0x63
                    // Actually in play state, custom_payload response is just writing back to the same channel
                    bot._client.write('custom_payload', {
                        channel: ch,
                        data: Buffer.from([0x63]) // Acknowledge discriminator
                    });
                    console.log(`[FML3-play] Auto-acknowledged ${ch} payload`);
                } catch (e) {
                    console.warn(`[FML3-play] Failed to ack ${ch}:`, e.message);
                }
            }
        });
    }

    bot.loadPlugin(pathfinder);
    bot.loadPlugin(pvp);
    bot.loadPlugin(collectblock);
    bot.loadPlugin(autoEat);
    bot.loadPlugin(armorManager); // auto equip armor
    bot.once('resourcePack', () => {
        bot.acceptResourcePack();
    });

    bot.once('login', () => {
        mc_version = bot.version;
        mcdata = minecraftData(mc_version);
        Item = prismarine_items(mc_version);

        // === Forge Spawn Diagnostic（仅警告，不强 emit）===
        // 之前这里曾经在 8s 没 spawn 时强制 emit('spawn') 并塞假坐标 (0,64,0)，
        // 结果在主世界随机让 syna 周围一片空（区块还没到），切暮色维度则把维度切换
        // 之前的旧坐标当 spawn 用，导致角色卡死、破坏方块时身体浮空。
        //
        // 实际诊断：position 包是 vanilla 包，从不会被 mod 的 PartialReadError 吞掉。
        // 区块加载慢只是慢，让 mineflayer 自己等就行。这里只在 30s 没 spawn 时打印告警。
        // 详见 docs/MOD_COMPAT_DIAGNOSIS.md
        const spawnDiagTimeout = setTimeout(() => {
            if (!bot.entity) {
                console.warn('[Forge-Spawn] 30s without spawn — server may be slow loading chunks. Continue waiting.');
            }
        }, 30000);
        bot.once('spawn', () => clearTimeout(spawnDiagTimeout));
    });

    // 抓取 mod 注册表（任何 Forge / Fabric 整合包都有效）
    try {
        modRegistry.attachToBot(bot, { host: `${settings.host}:${settings.port}` });
    } catch (e) {
        console.warn('[mcdata] attach mod_registry failed:', e && e.message);
    }

    return bot;
}

// ============== mod 兼容层：让 mcdata 查询失败时回退到 mod_registry ==============

/**
 * 解析任意物品名（vanilla 或 modid:xxx），返回 { name, displayName, source }。
 * source = 'vanilla' | 'mod' | null
 */
export function resolveAnyItem(name) {
    if (!name) return null;
    // 1) 原版 minecraft-data
    if (mcdata) {
        const stripped = name.includes(':') ? name.split(':')[1] : name;
        const v = mcdata.itemsByName[stripped];
        if (v) return { name: v.name, displayName: v.displayName || stripped, source: 'vanilla' };
    }
    // 2) mod registry
    const m = modRegistry.resolveModItem(name);
    if (m) return { name: m.name, displayName: m.displayName, source: 'mod' };
    return null;
}

export { modRegistry };

export function isHuntable(mob) {
    if (!mob || !mob.name) return false;
    const animals = ['chicken', 'cow', 'llama', 'mooshroom', 'pig', 'rabbit', 'sheep'];
    return animals.includes(mob.name.toLowerCase()) && !mob.metadata[16]; // metadata 16 is not baby
}

export function isHostile(mob) {
    if (!mob || !mob.name) return false;
    return  (mob.type === 'mob' || mob.type === 'hostile') && mob.name !== 'iron_golem' && mob.name !== 'snow_golem';
}

// blocks that don't work with collectBlock, need to be manually collected
export function mustCollectManually(blockName) {
    // all crops (that aren't normal blocks), torches, buttons, levers, redstone,
    const full_names = ['wheat', 'carrots', 'potatoes', 'beetroots', 'nether_wart', 'cocoa', 'sugar_cane', 'kelp', 'short_grass', 'fern', 'tall_grass', 'bamboo',
        'poppy', 'dandelion', 'blue_orchid', 'allium', 'azure_bluet', 'oxeye_daisy', 'cornflower', 'lilac', 'wither_rose', 'lily_of_the_valley', 'wither_rose',
        'lever', 'redstone_wire', 'lantern']
    const partial_names = ['sapling', 'torch', 'button', 'carpet', 'pressure_plate', 'mushroom', 'tulip', 'bush', 'vines', 'fern']
    return full_names.includes(blockName.toLowerCase()) || partial_names.some(partial => blockName.toLowerCase().includes(partial));
}

// 工具：去掉 modid: 前缀，给 vanilla minecraft-data 查询用
function _stripNs(name) {
    if (!name) return name;
    return String(name).includes(':') ? String(name).split(':')[1] : String(name);
}

export function getItemId(itemName) {
    if (!itemName) return null;
    if (mcdata) {
        let item = mcdata.itemsByName[_stripNs(itemName)];
        if (item) return item.id;
    }
    // 回退：mod 注册表（modid:xxx 或 后缀模糊匹配）
    try {
        const m = modRegistry.resolveModItem(itemName);
        if (m && typeof m.id === 'number') return m.id;
    } catch (_) {}
    return null;
}

export function getItemName(itemId) {
    if (mcdata) {
        let item = mcdata.items[itemId];
        if (item) return item.name;
    }
    try {
        const dn = modRegistry.getItemDisplayName(itemId);
        if (dn) {
            // 反查全名
            const all = modRegistry; // placeholder; 直接返回 displayName 作为兜底
            return dn;
        }
    } catch (_) {}
    return null;
}

export function getBlockId(blockName) {
    if (!blockName) return null;
    if (mcdata) {
        let block = mcdata.blocksByName[_stripNs(blockName)];
        if (block) return block.id;
    }
    return null;
}

export function getBlockName(blockId) {
    if (mcdata) {
        let block = mcdata.blocks[blockId];
        if (block) return block.name;
    }
    return null;
}

export function getEntityId(entityName) {
    if (!entityName) return null;
    if (mcdata) {
        let entity = mcdata.entitiesByName[_stripNs(entityName)];
        if (entity) return entity.id;
    }
    return null;
}

export function getAllItems(ignore) {
    if (!ignore) {
        ignore = [];
    }
    let items = []
    for (const itemId in mcdata.items) {
        const item = mcdata.items[itemId];
        if (!ignore.includes(item.name)) {
            items.push(item);
        }
    }
    return items;
}

export function getAllItemIds(ignore) {
    const items = getAllItems(ignore);
    let itemIds = [];
    for (const item of items) {
        itemIds.push(item.id);
    }
    return itemIds;
}

export function getAllBlocks(ignore) {
    if (!ignore) {
        ignore = [];
    }
    let blocks = []
    for (const blockId in mcdata.blocks) {
        const block = mcdata.blocks[blockId];
        if (!ignore.includes(block.name)) {
            blocks.push(block);
        }
    }
    return blocks;
}

export function getAllBlockIds(ignore) {
    const blocks = getAllBlocks(ignore);
    let blockIds = [];
    for (const block of blocks) {
        blockIds.push(block.id);
    }
    return blockIds;
}

export function getAllBiomes() {
    return mcdata.biomes;
}

export function getItemCraftingRecipes(itemName) {
    let itemId = getItemId(itemName);
    if (!mcdata.recipes[itemId]) {
        return null;
    }

    let recipes = [];
    for (let r of mcdata.recipes[itemId]) {
        let recipe = {};
        let ingredients = [];
        if (r.ingredients) {
            ingredients = r.ingredients;
        } else if (r.inShape) {
            ingredients = r.inShape.flat();
        }
        for (let ingredient of ingredients) {
            let ingredientName = getItemName(ingredient);
            if (ingredientName === null) continue;
            if (!recipe[ingredientName])
                recipe[ingredientName] = 0;
            recipe[ingredientName]++;
        }
        recipes.push([
            recipe,
            {craftedCount : r.result.count}
        ]);
    }
    // sort recipes by if their ingredients include common items
    const commonItems = ['oak_planks', 'oak_log', 'coal', 'cobblestone'];
    recipes.sort((a, b) => {
        let commonCountA = Object.keys(a[0]).filter(key => commonItems.includes(key)).reduce((acc, key) => acc + a[0][key], 0);
        let commonCountB = Object.keys(b[0]).filter(key => commonItems.includes(key)).reduce((acc, key) => acc + b[0][key], 0);
        return commonCountB - commonCountA;
    });

    return recipes;
}

export function isSmeltable(itemName) {
    const misc_smeltables = ['beef', 'chicken', 'cod', 'mutton', 'porkchop', 'rabbit', 'salmon', 'tropical_fish', 'potato', 'kelp', 'sand', 'cobblestone', 'clay_ball'];
    return itemName.includes('raw') || itemName.includes('log') || misc_smeltables.includes(itemName);
}

export function getSmeltingFuel(bot) {
    let fuel = bot.inventory.items().find(i => i.name === 'coal' || i.name === 'charcoal' || i.name === 'blaze_rod')
    if (fuel)
        return fuel;
    fuel = bot.inventory.items().find(i => i.name.includes('log') || i.name.includes('planks'))
    if (fuel)
        return fuel;
    return bot.inventory.items().find(i => i.name === 'coal_block' || i.name === 'lava_bucket');
}

export function getFuelSmeltOutput(fuelName) {
    if (fuelName === 'coal' || fuelName === 'charcoal')
        return 8;
    if (fuelName === 'blaze_rod')
        return 12;
    if (fuelName.includes('log') || fuelName.includes('planks'))
        return 1.5
    if (fuelName === 'coal_block')
        return 80;
    if (fuelName === 'lava_bucket')
        return 100;
    return 0;
}

export function getItemSmeltingIngredient(itemName) {
    return {    
        baked_potato: 'potato',
        steak: 'raw_beef',
        cooked_chicken: 'raw_chicken',
        cooked_cod: 'raw_cod',
        cooked_mutton: 'raw_mutton',
        cooked_porkchop: 'raw_porkchop',
        cooked_rabbit: 'raw_rabbit',
        cooked_salmon: 'raw_salmon',
        dried_kelp: 'kelp',
        iron_ingot: 'raw_iron',
        gold_ingot: 'raw_gold',
        copper_ingot: 'raw_copper',
        glass: 'sand'
    }[itemName];
}

export function getItemBlockSources(itemName) {
    let itemId = getItemId(itemName);
    let sources = [];
    for (let block of getAllBlocks()) {
        if (block.drops.includes(itemId)) {
            sources.push(block.name);
        }
    }
    return sources;
}

export function getItemAnimalSource(itemName) {
    return {    
        raw_beef: 'cow',
        raw_chicken: 'chicken',
        raw_cod: 'cod',
        raw_mutton: 'sheep',
        raw_porkchop: 'pig',
        raw_rabbit: 'rabbit',
        raw_salmon: 'salmon',
        leather: 'cow',
        wool: 'sheep'
    }[itemName];
}

export function getBlockTool(blockName) {
    let block = mcdata.blocksByName[blockName];
    if (!block || !block.harvestTools) {
        return null;
    }
    return getItemName(Object.keys(block.harvestTools)[0]);  // Double check first tool is always simplest
}

export function makeItem(name, amount=1) {
    return new Item(getItemId(name), amount);
}

/**
 * Returns the number of ingredients required to use the recipe once.
 * 
 * @param {Recipe} recipe
 * @returns {Object<mc.ItemName, number>} an object describing the number of each ingredient.
 */
export function ingredientsFromPrismarineRecipe(recipe) {
    let requiredIngedients = {};
    if (recipe.inShape)
        for (const ingredient of recipe.inShape.flat()) {
            if(ingredient.id<0) continue; //prismarine-recipe uses id -1 as an empty crafting slot
            const ingredientName = getItemName(ingredient.id);
            requiredIngedients[ingredientName] ??=0;
            requiredIngedients[ingredientName] += ingredient.count;
        }
    if (recipe.ingredients)
        for (const ingredient of recipe.ingredients) {
            if(ingredient.id<0) continue;
            const ingredientName = getItemName(ingredient.id);
            requiredIngedients[ingredientName] ??=0;
            requiredIngedients[ingredientName] -= ingredient.count;
            //Yes, the `-=` is intended.
            //prismarine-recipe uses positive numbers for the shaped ingredients but negative for unshaped.
            //Why this is the case is beyond my understanding.
        }
    return requiredIngedients;
}

/**
 * Calculates the number of times an action, such as a crafing recipe, can be completed before running out of resources.
 * @template T - doesn't have to be an item. This could be any resource.
 * @param {Object.<T, number>} availableItems - The resources available; e.g, `{'cobble_stone': 7, 'stick': 10}`
 * @param {Object.<T, number>} requiredItems - The resources required to complete the action once; e.g, `{'cobble_stone': 3, 'stick': 2}`
 * @param {boolean} discrete - Is the action discrete?
 * @returns {{num: number, limitingResource: (T | null)}} the number of times the action can be completed and the limmiting resource; e.g `{num: 2, limitingResource: 'cobble_stone'}`
 */
export function calculateLimitingResource(availableItems, requiredItems, discrete=true) {
    let limitingResource = null;
    let num = Infinity;
    for (const itemType in requiredItems) {
        if (availableItems[itemType] < requiredItems[itemType] * num) {
            limitingResource = itemType;
            num = availableItems[itemType] / requiredItems[itemType];
        }
    }
    if(discrete) num = Math.floor(num);
    return {num, limitingResource}
}

let loopingItems = new Set();

export function initializeLoopingItems() {

    loopingItems = new Set(['coal',
        'wheat',
        'bone_meal',
        'diamond',
        'emerald',
        'raw_iron',
        'raw_gold',
        'redstone',
        'blue_wool',
        'packed_mud',
        'raw_copper',
        'iron_ingot',
        'dried_kelp',
        'gold_ingot',
        'slime_ball',
        'black_wool',
        'quartz_slab',
        'copper_ingot',
        'lapis_lazuli',
        'honey_bottle',
        'rib_armor_trim_smithing_template',
        'eye_armor_trim_smithing_template',
        'vex_armor_trim_smithing_template',
        'dune_armor_trim_smithing_template',
        'host_armor_trim_smithing_template',
        'tide_armor_trim_smithing_template',
        'wild_armor_trim_smithing_template',
        'ward_armor_trim_smithing_template',
        'coast_armor_trim_smithing_template',
        'spire_armor_trim_smithing_template',
        'snout_armor_trim_smithing_template',
        'shaper_armor_trim_smithing_template',
        'netherite_upgrade_smithing_template',
        'raiser_armor_trim_smithing_template',
        'sentry_armor_trim_smithing_template',
        'silence_armor_trim_smithing_template',
        'wayfinder_armor_trim_smithing_template']);
}


/**
 * Gets a detailed plan for crafting an item considering current inventory
 */
export function getDetailedCraftingPlan(targetItem, count = 1, current_inventory = {}) {
    initializeLoopingItems();
    if (!targetItem || count <= 0 || !getItemId(targetItem)) {
        return "Invalid input. Please provide a valid item name and positive count.";
    }

    if (isBaseItem(targetItem)) {
        const available = current_inventory[targetItem] || 0;
        if (available >= count) return "You have all required items already in your inventory!";
        return `${targetItem} is a base item, you need to find ${count - available} more in the world`;
    }

    const inventory = { ...current_inventory };
    const leftovers = {};
    const plan = craftItem(targetItem, count, inventory, leftovers);
    return formatPlan(targetItem, plan);
}

function isBaseItem(item) {
    return loopingItems.has(item) || getItemCraftingRecipes(item) === null;
}

function craftItem(item, count, inventory, leftovers, crafted = { required: {}, steps: [], leftovers: {} }) {
    // Check available inventory and leftovers first
    const availableInv = inventory[item] || 0;
    const availableLeft = leftovers[item] || 0;
    const totalAvailable = availableInv + availableLeft;

    if (totalAvailable >= count) {
        // Use leftovers first, then inventory
        const useFromLeft = Math.min(availableLeft, count);
        leftovers[item] = availableLeft - useFromLeft;
        
        const remainingNeeded = count - useFromLeft;
        if (remainingNeeded > 0) {
            inventory[item] = availableInv - remainingNeeded;
        }
        return crafted;
    }

    // Use whatever is available
    const stillNeeded = count - totalAvailable;
    if (availableLeft > 0) leftovers[item] = 0;
    if (availableInv > 0) inventory[item] = 0;

    if (isBaseItem(item)) {
        crafted.required[item] = (crafted.required[item] || 0) + stillNeeded;
        return crafted;
    }

    const recipe = getItemCraftingRecipes(item)?.[0];
    if (!recipe) {
        crafted.required[item] = stillNeeded;
        return crafted;
    }

    const [ingredients, result] = recipe;
    const craftedPerRecipe = result.craftedCount;
    const batchCount = Math.ceil(stillNeeded / craftedPerRecipe);
    const totalProduced = batchCount * craftedPerRecipe;

    // Add excess to leftovers
    if (totalProduced > stillNeeded) {
        leftovers[item] = (leftovers[item] || 0) + (totalProduced - stillNeeded);
    }

    // Process each ingredient
    for (const [ingredientName, ingredientCount] of Object.entries(ingredients)) {
        const totalIngredientNeeded = ingredientCount * batchCount;
        craftItem(ingredientName, totalIngredientNeeded, inventory, leftovers, crafted);
    }

    // Add crafting step
    const stepIngredients = Object.entries(ingredients)
        .map(([name, amount]) => `${amount * batchCount} ${name}`)
        .join(' + ');
    crafted.steps.push(`Craft ${stepIngredients} -> ${totalProduced} ${item}`);

    return crafted;
}

function formatPlan(targetItem, { required, steps, leftovers }) {
    const lines = [];

    if (Object.keys(required).length > 0) {
        lines.push('You are missing the following items:');
        Object.entries(required).forEach(([item, count]) => 
            lines.push(`- ${count} ${item}`));
        lines.push('\nOnce you have these items, here\'s your crafting plan:');
    } else {
        lines.push('You have all items required to craft this item!');
        lines.push('Here\'s your crafting plan:');
    }

    lines.push('');
    lines.push(...steps);

    if (Object.keys(required).some(item => item.includes('oak')) && !targetItem.includes('oak')) {
        lines.push('Note: Any varient of wood can be used for this recipe.');
    }

    if (Object.keys(leftovers).length > 0) {
        lines.push('\nYou will have leftover:');
        Object.entries(leftovers).forEach(([item, count]) => 
            lines.push(`- ${count} ${item}`));
    }

    return lines.join('\n');
}
