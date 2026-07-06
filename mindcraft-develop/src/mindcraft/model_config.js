import { readFileSync, writeFileSync, existsSync } from 'fs';
import path from 'path';

const rootDir = process.cwd();
const keysPath = path.join(rootDir, 'keys.json');
const profilePath = path.join(rootDir, 'profiles', 'syna.json');
const registryPath = path.join(rootDir, 'src', 'models', 'model_registry.json');

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

function normalizeBaseUrl(url) {
    return String(url || '').trim().replace(/\/+$/, '');
}

function safeParams(params) {
    if (!params) return {};
    if (typeof params === 'object' && !Array.isArray(params)) return params;
    return {};
}

export function readModelConfig() {
    const keys = readJson(keysPath, {});
    const profile = readJson(profilePath, {});
    const registry = readJson(registryPath, {});
    const model = profile.model || {};
    const apiConfig = registry[model.api] || {};

    return {
        profilePath,
        keysPath,
        active: {
            api: model.api || '',
            baseURL: model.url || apiConfig.baseURL || '',
            model: model.model || apiConfig.defaultModel || '',
            params: safeParams(model.params || apiConfig.defaultParams),
            apiKeyName: apiConfig.apiKeyName || '',
            hasKey: Boolean(apiConfig.apiKeyName && keys[apiConfig.apiKeyName]),
        },
        presets: Object.fromEntries(Object.entries(registry)
            .filter(([name]) => !name.startsWith('_'))
            .map(([name, cfg]) => [name, {
                description: cfg.description || name,
                baseURL: cfg.baseURL || '',
                model: cfg.defaultModel || '',
                params: safeParams(cfg.defaultParams),
                apiKeyName: cfg.apiKeyName || '',
                hasKey: Boolean(cfg.apiKeyName && keys[cfg.apiKeyName]),
            }])),
        profileExists: existsSync(profilePath),
        keysExists: existsSync(keysPath),
    };
}

export function writeModelConfig(input = {}) {
    const api = String(input.api || 'custom').trim() || 'custom';
    const baseURL = normalizeBaseUrl(input.baseURL);
    const modelName = String(input.model || '').trim();
    const apiKey = String(input.apiKey || '').trim();
    const params = safeParams(input.params);

    if (!baseURL) {
        throw new Error('baseURL is required');
    }
    if (!modelName) {
        throw new Error('model is required');
    }

    const registry = readJson(registryPath, {});
    const existingProvider = registry[api] || {};
    const apiKeyName = api === 'custom'
        ? 'CUSTOM_OPENAI_API_KEY'
        : (existingProvider.apiKeyName || `${api.toUpperCase()}_API_KEY`);

    registry[api] = {
        description: existingProvider.description || (api === 'custom' ? 'Custom OpenAI-compatible endpoint' : api),
        baseURL,
        apiKeyName,
        defaultModel: modelName,
        defaultParams: params,
        thinking: existingProvider.thinking || { type: 'disabled' },
    };
    writeJson(registryPath, registry);

    const keys = readJson(keysPath, {});
    if (apiKey) {
        keys[apiKeyName] = apiKey;
        writeJson(keysPath, keys);
    } else if (!existsSync(keysPath)) {
        writeJson(keysPath, keys);
    }

    const profile = readJson(profilePath, {});
    profile.model = {
        api,
        model: modelName,
        url: baseURL,
        params,
    };
    writeJson(profilePath, profile);

    return readModelConfig();
}
