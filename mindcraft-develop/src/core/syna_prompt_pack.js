import { readFileSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const promptPath = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    '../../prompts/syna_pure_mod.json',
);

function asLines(value, key) {
    if (!Array.isArray(value) || value.some(line => typeof line !== 'string')) {
        throw new Error(`Prompt section ${key} must be an array of strings.`);
    }
    return value.map(line => line.trim()).filter(Boolean);
}

export function loadPromptPack() {
    let parsed;
    try {
        parsed = JSON.parse(readFileSync(promptPath, 'utf8'));
    } catch (error) {
        throw new Error(`Cannot load ${promptPath}: ${error?.message || error}`);
    }

    const settings = parsed.settings || {};
    const pack = {
        settings: {
            max_chars: Math.max(12, Math.min(240, Number(settings.max_chars) || 64)),
            max_sentences: Math.max(1, Math.min(8, Number(settings.max_sentences) || 3)),
            silence_token: String(settings.silence_token || '<silence>').trim(),
            fallback_reply: String(settings.fallback_reply || '').trim(),
        },
        persona: asLines(parsed.persona, 'persona'),
    };
    for (const key of ['intent_decision', 'normal_reply', 'action_receipt_reply',
        'proactive_event_reply', 'observation_reply', 'episode_help_reply', 'horror_reply']) {
        pack[key] = asLines(parsed[key], key);
    }
    return pack;
}

export function buildSystemPrompt(pack, section, { horror = false, limits = null } = {}) {
    if (!Array.isArray(pack?.[section])) throw new Error(`Unknown prompt section: ${section}`);
    const variables = {
        silence_token: pack.settings.silence_token,
        max_chars: String(limits?.maxChars ?? pack.settings.max_chars),
        max_sentences: String(limits?.maxSentences ?? pack.settings.max_sentences),
    };
    const lines = [...pack.persona, ...pack[section]];
    if (horror && section !== 'intent_decision') lines.push(...pack.horror_reply);
    if (section === 'intent_decision') lines.push('动作接口、服务器状态和回执事实只读。');
    else lines.push(`硬限制：最多 {{max_chars}} 个字符、{{max_sentences}} 句话。事实字段只读。`);
    return lines.join('\n').replace(/\{\{(\w+)\}\}/g, (_, key) => variables[key] ?? `{{${key}}}`);
}

export { promptPath };
