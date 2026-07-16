import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'fs';
import path from 'path';
import { readModelConfig, writeModelConfig } from '../src/mindcraft/model_config.js';
import { readControlConfig, writeMinecraftConfig } from '../src/mindcraft/control_config.js';

const rootDir = process.cwd();
const keysPath = path.join(rootDir, 'keys.json');
const settingsPath = path.join(rootDir, 'settings.js');
const voiceConfigPath = path.join(rootDir, 'launcher', 'voice_config.json');
const launcherConfigPath = path.join(rootDir, 'launcher', 'launcher_config.json');

function readJson(filePath, fallback = {}) {
    try { return JSON.parse(readFileSync(filePath, 'utf8')); }
    catch { return fallback; }
}

function writeJson(filePath, value) {
    writeFileSync(filePath, JSON.stringify(value, null, 4) + '\n', 'utf8');
}

function readStdin() {
    return new Promise((resolve) => {
        let body = '';
        process.stdin.setEncoding('utf8');
        process.stdin.on('data', (chunk) => body += chunk);
        process.stdin.on('end', () => resolve(body));
    });
}

function normalizeBaseUrl(value) {
    return String(value || '').trim().replace(/\/+$/, '');
}

function readSettingsText() {
    try { return readFileSync(settingsPath, 'utf8'); }
    catch { return ''; }
}

function readMindserverPort() {
    const text = readSettingsText();
    const match = text.match(/"mindserver_port"\s*:\s*(\d+)/);
    return match ? Number(match[1]) : 8081;
}

function findObjectRange(text, objectName) {
    const marker = `"${objectName}"`;
    const markerIndex = text.indexOf(marker);
    if (markerIndex < 0) return null;
    const start = text.indexOf('{', markerIndex);
    if (start < 0) return null;
    let depth = 0;
    let inString = false;
    let escaped = false;
    for (let i = start; i < text.length; i++) {
        const ch = text[i];
        if (inString) {
            if (escaped) escaped = false;
            else if (ch === '\\') escaped = true;
            else if (ch === '"') inString = false;
            continue;
        }
        if (ch === '"') inString = true;
        else if (ch === '{') depth++;
        else if (ch === '}') {
            depth--;
            if (depth === 0) return { start, end: i + 1 };
        }
    }
    return null;
}

function replaceObjectProperty(objectText, key, jsValue) {
    const property = new RegExp(`("${key}"\\s*:\\s*)(?:"(?:\\\\.|[^"])*"|true|false|null|-?\\d+(?:\\.\\d+)?)`);
    if (property.test(objectText)) {
        return objectText.replace(property, `$1${jsValue}`);
    }
    const insertAt = objectText.lastIndexOf('}');
    const prefix = objectText.slice(0, insertAt).trimEnd();
    const needsComma = !prefix.endsWith('{') && !prefix.endsWith(',');
    return objectText.slice(0, insertAt) + (needsComma ? ',' : '') + `\n        "${key}": ${jsValue}\n    ` + objectText.slice(insertAt);
}

function updateSettingsObject(objectName, updates) {
    let text = readSettingsText();
    if (!text) return false;
    const range = findObjectRange(text, objectName);
    if (!range) return false;
    let objectText = text.slice(range.start, range.end);
    for (const [key, value] of Object.entries(updates)) {
        objectText = replaceObjectProperty(objectText, key, JSON.stringify(value));
    }
    text = text.slice(0, range.start) + objectText + text.slice(range.end);
    writeFileSync(settingsPath, text, 'utf8');
    return true;
}

function readVoiceConfig() {
    const keys = readJson(keysPath, {});
    const saved = readJson(voiceConfigPath, {});
    const settings = readSettingsText();
    const voiceBase = (settings.match(/"syna_voice"\s*:\s*\{[\s\S]*?"base_url"\s*:\s*"([^"]+)"/) || [])[1] || 'http://127.0.0.1:8766';
    const voiceEnabled = !/"syna_voice"\s*:\s*\{[\s\S]*?"enabled"\s*:\s*false/.test(settings);
    const mindcraftUrl = saved.mindcraft_url || `http://127.0.0.1:${readMindserverPort()}`;

    return {
        enabled: voiceEnabled,
        voiceBaseUrl: voiceBase,
        mindcraftUrl,
        defaultAgent: saved.defaultAgent || 'syna',
        senderName: saved.senderName || 'SynaMic',
        rmsThreshold: saved.rmsThreshold || 800,
        inputDevice: saved.inputDevice ?? '',
        volcAppId: keys.VOLC_APP_ID || process.env.VOLC_APP_ID || '',
        volcAccessToken: keys.VOLC_ACCESS_TOKEN || process.env.VOLC_ACCESS_TOKEN || '',
        volcVoiceId: keys.VOLC_VOICE_ID || process.env.VOLC_VOICE_ID || '',
        volcCluster: keys.VOLC_CLUSTER || process.env.VOLC_CLUSTER || 'volcano_icl',
        volcSpeed: keys.VOLC_SPEED || process.env.VOLC_SPEED || 1.0,
        volcAsrResourceId: keys.VOLC_ASR_RESOURCE_ID || process.env.VOLC_ASR_RESOURCE_ID || 'volc.seedasr.sauc.duration',
    };
}

function saveVoiceConfig(input = {}) {
    const keys = readJson(keysPath, {});
    const map = {
        VOLC_APP_ID: input.volcAppId,
        VOLC_ACCESS_TOKEN: input.volcAccessToken,
        VOLC_VOICE_ID: input.volcVoiceId,
        VOLC_CLUSTER: input.volcCluster,
        VOLC_SPEED: input.volcSpeed,
        VOLC_ASR_RESOURCE_ID: input.volcAsrResourceId,
    };
    for (const [key, value] of Object.entries(map)) {
        if (value !== undefined && String(value).trim() !== '') keys[key] = value;
    }
    writeJson(keysPath, keys);

    updateSettingsObject('syna_voice', {
        enabled: input.enabled !== false,
        base_url: normalizeBaseUrl(input.voiceBaseUrl || 'http://127.0.0.1:8766'),
    });

    writeJson(voiceConfigPath, {
        mindcraft_url: normalizeBaseUrl(input.mindcraftUrl || `http://127.0.0.1:${readMindserverPort()}`),
        defaultAgent: String(input.defaultAgent || 'syna').trim(),
        senderName: String(input.senderName || 'SynaMic').trim(),
        rmsThreshold: Number(input.rmsThreshold || 800),
        inputDevice: input.inputDevice === undefined || input.inputDevice === null || String(input.inputDevice).trim() === '' ? null : Number(input.inputDevice),
    });
}


function findBundledModJar() {
    const candidates = [
        path.resolve(rootDir, '..', 'syna_mod', 'build', 'libs', 'synabridge-0.1.0.jar'),
        path.resolve(rootDir, '..', 'syna_mod', 'built-jars', 'synabridge-0.1.0.jar'),
        path.resolve(rootDir, 'syna_mod', 'build', 'libs', 'synabridge-0.1.0.jar'),
        path.resolve(rootDir, 'syna_mod', 'built-jars', 'synabridge-0.1.0.jar'),
    ];
    return candidates.find((candidate) => existsSync(candidate)) || '';
}

function readLauncherConfig() {
    const saved = readJson(launcherConfigPath, {});
    return {
        runMode: saved.runMode === 'mod_mf' ? 'mod_mf' : 'pure_mod',
        modsDir: saved.modsDir || '',
        modJar: findBundledModJar(),
    };
}

function saveLauncherConfig(input = {}) {
    const saved = readJson(launcherConfigPath, {});
    if (input.runMode !== undefined) {
        saved.runMode = input.runMode === 'mod_mf' ? 'mod_mf' : 'pure_mod';
    }
    if (input.modsDir !== undefined) {
        saved.modsDir = String(input.modsDir || '').trim();
    }
    writeJson(launcherConfigPath, saved);
    return readLauncherConfig();
}

function installSynaMod(input = {}) {
    const modsDir = String(input.modsDir || '').trim();
    if (!modsDir) throw new Error('请选择 Minecraft 的 mods 文件夹。');

    const sourceJar = findBundledModJar();
    if (!sourceJar) {
        throw new Error('没有找到 synabridge mod jar。请先在 syna_mod 目录构建，或确认 built-jars/synabridge-0.1.0.jar 存在。');
    }

    mkdirSync(modsDir, { recursive: true });
    const target = path.join(modsDir, path.basename(sourceJar));
    copyFileSync(sourceJar, target);
    saveLauncherConfig({ modsDir });
    return { ok: true, source: sourceJar, target };
}
async function readAll() {
    const modelConfig = readModelConfig();
    const keys = readJson(keysPath, {});
    const apiKeyName = modelConfig.active.apiKeyName;
    const minecraft = readControlConfig().minecraft;
    return {
        appDir: rootDir,
        mindserverPort: readMindserverPort(),
        minecraft: {
            host: minecraft.host || '127.0.0.1',
            port: minecraft.port ?? null,
            minecraft_version: minecraft.minecraft_version || 'auto',
            last_test: minecraft.last_test || null,
        },
        model: {
            api: modelConfig.active.api || '',
            baseURL: modelConfig.active.baseURL || '',
            model: modelConfig.active.model || '',
            apiKeyName: apiKeyName || '',
            hasKey: Boolean(apiKeyName && keys[apiKeyName]),
            apiKey: apiKeyName ? (keys[apiKeyName] || process.env[apiKeyName] || '') : '',
        },
        voice: readVoiceConfig(),
        launcher: readLauncherConfig(),
    };
}

async function probeJson(url, timeoutMs = 700) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        const response = await fetch(url, { signal: controller.signal });
        if (!response.ok) return null;
        return await response.json();
    } catch {
        return null;
    } finally {
        clearTimeout(timer);
    }
}

async function detectLocalModel() {
    const ollama = await probeJson('http://127.0.0.1:11434/api/tags');
    if (ollama?.models?.length) {
        return {
            found: true,
            kind: 'ollama',
            api: 'ollama',
            baseURL: 'http://127.0.0.1:11434',
            model: ollama.models[0].name || '',
            apiKey: '',
            message: 'Detected local Ollama service.',
        };
    }

    const lmstudio = await probeJson('http://127.0.0.1:1234/v1/models');
    if (lmstudio?.data?.length) {
        return {
            found: true,
            kind: 'lmstudio',
            api: 'custom',
            baseURL: 'http://127.0.0.1:1234/v1',
            model: lmstudio.data[0].id || '',
            apiKey: 'local',
            message: 'Detected local LM Studio OpenAI-compatible service.',
        };
    }

    return { found: false, message: 'No supported local model service detected.' };
}

async function saveAll(input) {
    if (input.minecraft) {
        writeMinecraftConfig(input.minecraft);
    }
    if (input.model) {
        const hasModelConfig = String(input.model.baseURL || '').trim() && String(input.model.model || '').trim();
        if (hasModelConfig) {
            writeModelConfig(input.model);
        }
    }
    if (input.voice) {
        saveVoiceConfig(input.voice);
    }
    if (input.launcher) {
        saveLauncherConfig(input.launcher);
    }
    return readAll();
}

async function main() {
    const command = process.argv[2] || 'read';
    if (command === 'read') {
        console.log(JSON.stringify(await readAll()));
        return;
    }
    if (command === 'detect-local-model') {
        console.log(JSON.stringify(await detectLocalModel()));
        return;
    }
    if (command === 'save') {
        const body = await readStdin();
        const cleanBody = body.replace(/^\uFEFF/, '').trim();
        const input = cleanBody ? JSON.parse(cleanBody) : {};
        await saveAll(input);
        console.log(JSON.stringify({ ok: true }));
        return;
    }
    if (command === 'install-mod') {
        const body = await readStdin();
        const cleanBody = body.replace(/^\uFEFF/, '').trim();
        const input = cleanBody ? JSON.parse(cleanBody) : {};
        console.log(JSON.stringify(installSynaMod(input)));
        return;
    }
    throw new Error(`Unknown command: ${command}`);
}

main().catch((error) => {
    console.error(JSON.stringify({ ok: false, error: String(error?.message || error) }));
    process.exit(1);
});







