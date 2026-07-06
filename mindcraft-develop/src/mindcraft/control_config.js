import { existsSync, readFileSync, writeFileSync } from 'fs';
import path from 'path';
import { serverInfo } from './mcserver.js';

const rootDir = process.cwd();
const configPath = path.join(rootDir, 'control_config.json');

const defaults = {
    minecraft: {
        host: '127.0.0.1',
        port: null,
        minecraft_version: 'auto',
        last_test: null,
    },
};

function readJson(filePath, fallback = {}) {
    try {
        return JSON.parse(readFileSync(filePath, 'utf8'));
    } catch {
        return fallback;
    }
}

function writeJson(filePath, value) {
    writeFileSync(filePath, JSON.stringify(value, null, 4) + '\n', 'utf8');
}

function normalizePort(port) {
    if (port === '' || port === null || port === undefined) return null;
    const parsed = Number(port);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
        throw new Error('Minecraft port must be a number from 1 to 65535.');
    }
    return parsed;
}

function normalizeMinecraft(input = {}) {
    const host = String(input.host || defaults.minecraft.host).trim();
    if (!host) throw new Error('Minecraft host is required.');

    return {
        host,
        port: normalizePort(input.port),
        minecraft_version: String(input.minecraft_version || 'auto').trim() || 'auto',
        last_test: input.last_test || null,
    };
}

export function readControlConfig() {
    const existing = readJson(configPath, {});
    return {
        configPath,
        minecraft: normalizeMinecraft({
            ...defaults.minecraft,
            ...(existing.minecraft || {}),
        }),
    };
}

export function writeMinecraftConfig(input = {}) {
    const current = readControlConfig();
    const next = {
        ...current,
        minecraft: normalizeMinecraft({
            ...current.minecraft,
            ...input,
            last_test: input.last_test === undefined ? current.minecraft.last_test : input.last_test,
        }),
    };
    writeJson(configPath, { minecraft: next.minecraft });
    return next;
}

export function applyControlConfigToSettings(settings) {
    const cfg = readControlConfig().minecraft;
    if (cfg.host) settings.host = cfg.host;
    if (cfg.port !== null) settings.port = cfg.port;
    if (cfg.minecraft_version) settings.minecraft_version = cfg.minecraft_version;
    return settings;
}

export function validateMinecraftLaunchConfig(settings) {
    const port = normalizePort(settings.port);
    const host = String(settings.host || '').trim();
    if (!host) throw new Error('Minecraft host is required before starting the bot.');
    if (port === null) throw new Error('Minecraft LAN port is required before starting the bot.');
    return { host, port, minecraft_version: settings.minecraft_version || 'auto' };
}

export async function testMinecraftConnection(input = {}) {
    const minecraft = normalizeMinecraft(input);
    if (minecraft.port === null) {
        throw new Error('Minecraft LAN port is required before testing.');
    }

    const found = await serverInfo(minecraft.host, minecraft.port, 1500, false);
    if (!found) {
        throw new Error(`No Minecraft server responded at ${minecraft.host}:${minecraft.port}.`);
    }

    const result = {
        ok: true,
        host: found.host,
        port: found.port,
        name: found.name,
        ping: found.ping,
        version: found.version,
        tested_at: new Date().toISOString(),
    };

    writeMinecraftConfig({
        ...minecraft,
        minecraft_version: minecraft.minecraft_version === 'auto' ? 'auto' : minecraft.minecraft_version,
        last_test: result,
    });

    return result;
}

export function hasRecentSuccessfulMinecraftTest(settings, maxAgeMs = 5 * 60 * 1000) {
    const cfg = readControlConfig().minecraft;
    const last = cfg.last_test;
    if (!last?.ok || !last.tested_at) return false;
    if (String(settings.host) !== String(last.host)) return false;
    if (Number(settings.port) !== Number(last.port)) return false;
    const testedAt = Date.parse(last.tested_at);
    return Number.isFinite(testedAt) && Date.now() - testedAt <= maxAgeMs;
}

export function ensureControlConfigFile() {
    if (!existsSync(configPath)) {
        writeJson(configPath, { minecraft: defaults.minecraft });
    }
}
