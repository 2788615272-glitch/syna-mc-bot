/**
 * mod_registry.js
 *
 * 监听底层 mineflayer client 收到的 login / registry_data 包，
 * 把服务器下发的完整注册表（含所有 mod 的 modid:xxx）落盘 + 暴露查询接口。
 *
 * 设计目标：让任意 Forge / Fabric / Connector 整合包里的 mod 物品、方块、实体
 * 名都能被 syna 在 prompt 里"知道"，并且能在 mcdata 回退时拿到 modid:itemid。
 *
 * 与 vanilla minecraft-data 不冲突，作为补充层。
 */

import fs from 'fs';
import path from 'path';

// 内存中的注册表索引：
//   items: Map<numericId, { name, displayName }>
//   itemsByName: Map<'modid:item', { id, name, displayName }>
//   blocks / entities 同上
const _state = {
    captured: false,
    host: null,
    items: new Map(),
    itemsByName: new Map(),
    blocks: new Map(),
    blocksByName: new Map(),
    entities: new Map(),
    entitiesByName: new Map(),
    namespaces: new Set(),
    rawDumpPath: null,
};

function _safeIdent(s) {
    return String(s || 'unknown').replace(/[^a-zA-Z0-9_.-]/g, '_');
}

function _ingestList(typeKey, entries) {
    if (!Array.isArray(entries)) return;
    const idxKey = typeKey === 'item' ? 'items'
                 : typeKey === 'block' ? 'blocks'
                 : typeKey === 'entity' ? 'entities'
                 : null;
    if (!idxKey) return;
    const byNameKey = idxKey + 'ByName';
    for (const e of entries) {
        if (!e || !e.name) continue;
        const fullName = e.name; // 形如 "minecraft:dirt" 或 "tacz:modern_kinetic_gun"
        const numericId = (typeof e.id === 'number') ? e.id : null;
        const display = e.displayName || _displayFromPath(fullName);
        const rec = { id: numericId, name: fullName, displayName: display };
        if (numericId !== null) _state[idxKey].set(numericId, rec);
        _state[byNameKey].set(fullName, rec);
        const ns = fullName.includes(':') ? fullName.split(':', 1)[0] : 'minecraft';
        _state.namespaces.add(ns);
    }
}

function _displayFromPath(fullName) {
    const path = fullName.includes(':') ? fullName.split(':')[1] : fullName;
    return path.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

/**
 * 解析 mineflayer 登录后 bot.registry / bot._client 上能拿到的注册表数据。
 * 不同 mineflayer 版本暴露字段不同，这里尽量做兼容。
 */
function _harvestFromBot(bot) {
    try {
        const reg = bot && bot.registry;
        if (!reg) return false;
        // mineflayer 1.20+ registry 通常带 itemsArray / blocksArray / entitiesArray
        if (Array.isArray(reg.itemsArray)) {
            _ingestList('item', reg.itemsArray.map(it => ({
                id: it.id,
                name: it.name && it.name.includes(':') ? it.name : `minecraft:${it.name}`,
                displayName: it.displayName,
            })));
        }
        if (Array.isArray(reg.blocksArray)) {
            _ingestList('block', reg.blocksArray.map(b => ({
                id: b.id,
                name: b.name && b.name.includes(':') ? b.name : `minecraft:${b.name}`,
                displayName: b.displayName,
            })));
        }
        if (Array.isArray(reg.entitiesArray)) {
            _ingestList('entity', reg.entitiesArray.map(en => ({
                id: en.id,
                name: en.name && en.name.includes(':') ? en.name : `minecraft:${en.name}`,
                displayName: en.displayName,
            })));
        }
    } catch (e) {
        console.warn('[mod_registry] harvestFromBot failed:', e && e.message);
        return false;
    }
    return true;
}

/**
 * 处理 1.20.1 配置阶段（configuration / login）下发的 registry_data 包。
 * 这是 mod 命名空间真正出现的地方（mineflayer 自身只解析 vanilla）。
 */
function _harvestFromRegistryDataPacket(pkt) {
    try {
        if (!pkt) return;
        // 1.20.2+ : 包含 codec.value 多个 type
        // 1.20.1  : codec 是 NBT，里面有 minecraft:dimension_type 等；item/block 实际通过 tags + login
        // 我们只对 ".../item"、".../block_entity_type"、".../entity_type" 感兴趣
        const codec = pkt.codec || pkt;
        if (codec && typeof codec === 'object') {
            for (const k of Object.keys(codec)) {
                const v = codec[k];
                if (!v || !v.value) continue;
                const arr = Array.isArray(v.value) ? v.value : [];
                if (k.endsWith('item') || k.endsWith('items')) {
                    _ingestList('item', arr.map(e => ({ id: e.id, name: e.name })));
                } else if (k.endsWith('block') || k.endsWith('blocks')) {
                    _ingestList('block', arr.map(e => ({ id: e.id, name: e.name })));
                } else if (k.endsWith('entity_type')) {
                    _ingestList('entity', arr.map(e => ({ id: e.id, name: e.name })));
                }
            }
        }
    } catch (e) {
        // 静默：不同版本 packet 结构差异大
    }
}

/**
 * 主入口：在 mineflayer bot 创建后调用。
 * 它会挂多个监听，确保最终拿到完整注册表。
 */
export function attachToBot(bot, opts = {}) {
    if (!bot || !bot._client) return;
    _state.host = opts.host || 'unknown';

    // 1) 监听底层 raw packet，捕获 registry_data
    const client = bot._client;
    const pktHandler = (data, meta) => {
        try {
            if (!meta || !meta.name) return;
            if (meta.name === 'registry_data' || meta.name === 'login' || meta.name === 'tags') {
                _harvestFromRegistryDataPacket(data);
            }
        } catch (e) { /* swallow */ }
    };
    client.on('packet', pktHandler);

    // 2) bot 'spawn' 后再从 mineflayer 高级 registry 抓一遍（最稳）
    bot.once('spawn', () => {
        const ok = _harvestFromBot(bot);
        if (ok) {
            _state.captured = true;
            _dumpToDisk();
            console.log(`[mod_registry] captured: items=${_state.items.size} blocks=${_state.blocks.size} entities=${_state.entities.size} namespaces=${_state.namespaces.size}`);
        }
    });
}

function _dumpToDisk() {
    try {
        const dir = path.resolve(process.cwd(), 'logs');
        if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
        const file = path.join(dir, `mod_registry_${_safeIdent(_state.host)}.json`);
        const out = {
            captured_at: new Date().toISOString(),
            host: _state.host,
            namespaces: Array.from(_state.namespaces).sort(),
            counts: {
                items: _state.items.size,
                blocks: _state.blocks.size,
                entities: _state.entities.size,
            },
            items: Array.from(_state.itemsByName.values()),
            blocks: Array.from(_state.blocksByName.values()),
            entities: Array.from(_state.entitiesByName.values()),
        };
        fs.writeFileSync(file, JSON.stringify(out, null, 2), 'utf8');
        _state.rawDumpPath = file;
    } catch (e) {
        console.warn('[mod_registry] dump failed:', e && e.message);
    }
}

// ============== 公开查询 API ==============

export function getAllNamespaces() {
    return Array.from(_state.namespaces).sort();
}

export function getModNamespaces() {
    return getAllNamespaces().filter(n => n !== 'minecraft');
}

/** 名字解析：'ak47' → 'tacz:ak47'（粗略匹配后缀） */
export function resolveModItem(name) {
    if (!name) return null;
    const lower = String(name).toLowerCase();
    if (_state.itemsByName.has(lower)) return _state.itemsByName.get(lower);
    // 按 path 后缀模糊匹配
    for (const [full, rec] of _state.itemsByName) {
        const p = full.includes(':') ? full.split(':')[1] : full;
        if (p === lower) return rec;
    }
    for (const [full, rec] of _state.itemsByName) {
        const p = full.includes(':') ? full.split(':')[1] : full;
        if (p.includes(lower)) return rec;
    }
    return null;
}

export function getItemDisplayName(numericIdOrName) {
    if (typeof numericIdOrName === 'number') {
        const r = _state.items.get(numericIdOrName);
        return r ? r.displayName : null;
    }
    const r = resolveModItem(numericIdOrName);
    return r ? r.displayName : null;
}

/**
 * 给 prompt 用的紧凑摘要：列出 mod 命名空间 + 每个 mod 抽 6 个示例物品。
 * 不要太长，放在 system prompt 末尾。
 */
export function buildPromptSnippet({ maxItemsPerMod = 6, maxMods = 25 } = {}) {
    if (!_state.captured) return '';
    const mods = getModNamespaces();
    if (mods.length === 0) return '';
    const lines = [];
    lines.push('[当前世界检测到的 mod 命名空间，共 ' + mods.length + ' 个]');
    lines.push(mods.slice(0, maxMods).join(', ') + (mods.length > maxMods ? ', ...' : ''));
    lines.push('[mod 物品示例（你说的物品名如果不是原版的，按下面 modid:path 格式调用）]');
    let shown = 0;
    for (const ns of mods) {
        if (shown >= maxMods) break;
        const samples = [];
        for (const [full] of _state.itemsByName) {
            if (full.startsWith(ns + ':')) {
                samples.push(full);
                if (samples.length >= maxItemsPerMod) break;
            }
        }
        if (samples.length > 0) {
            lines.push('- ' + ns + ': ' + samples.join(', '));
            shown++;
        }
    }
    return lines.join('\n');
}

/**
 * 通过 numeric entityType ID 或 name 查找 mod 实体。
 * mineflayer 对 mod 实体会返回 "unknown"，这里用注册表做二次查找。
 */
export function resolveModEntity(entityTypeIdOrName) {
    if (entityTypeIdOrName == null) return null;
    if (typeof entityTypeIdOrName === 'number') {
        return _state.entities.get(entityTypeIdOrName) || null;
    }
    const lower = String(entityTypeIdOrName).toLowerCase();
    if (_state.entitiesByName.has(lower)) return _state.entitiesByName.get(lower);
    for (const [full, rec] of _state.entitiesByName) {
        const p = full.includes(':') ? full.split(':')[1] : full;
        if (p === lower) return rec;
    }
    return null;
}

export function isCaptured() {
    return _state.captured;
}

export function getDumpPath() {
    return _state.rawDumpPath;
}
