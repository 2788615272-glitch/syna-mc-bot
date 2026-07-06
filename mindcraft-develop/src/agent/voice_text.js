import { containsCommand } from './commands/index.js';

function isSpeechNoise(text) {
    const compact = String(text || '').replace(/\s+/g, '');
    if (!compact) return true;
    if (compact === '\\t' || compact.toLowerCase() === 't') return true;
    if (/^[\\ttrn]+$/i.test(compact)) return true;
    if (/^[!?.,;:，。！？、…~`'"“”‘’()[\]{}<>\-_=+*/\\|\d]+$/.test(compact)) return true;
    return false;
}

function looksLikeCommandFragment(text) {
    const s = String(text || '').trim();
    if (!s) return true;
    if (/^!?\w+\s*\(/.test(s)) return true;
    if (/^[\w.:-]+\s*\([^)]*$/.test(s)) return true;
    if (/^[\w.:-]+\s*\([^)]*\)?\s*$/.test(s) && !/[\u4e00-\u9fff]/.test(s)) return true;
    if (/^[\w.:-]+,\s*$/.test(s) && !/[\u4e00-\u9fff]/.test(s)) return true;
    if (/^(true|false|null|undefined)\)?\s*$/i.test(s)) return true;
    if (/^[\w.:-]+["']?\)?\s*$/.test(s) && /[A-Za-z_]/.test(s) && !/[\u4e00-\u9fff]/.test(s)) return true;
    return false;
}

export function extractVoiceText(message) {
    if (!message) return '';

    let text = String(message).replace(/\u0000/g, '');
    text = text.replace(/\\t/g, ' ').replace(/\t/g, ' ');
    if (isSpeechNoise(text)) return '';

    // [SAY]/[THINK] control tags route behavior; they are not spoken.
    if (/^\s*\[THINK\]/i.test(text)) return '';
    text = text.replace(/\[SAY\]/gi, '');
    text = text.replace(/\[THINK\]/gi, '');

    // Remove fenced code blocks. They are for tools/code execution, not speech.
    text = text.replace(/```[\s\S]*?```/g, ' ');
    if (looksLikeCommandFragment(text)) return '';

    // Keep only the natural-language prefix before the first Mindcraft command.
    const commandName = containsCommand(text);
    if (commandName) {
        text = text.substring(0, text.indexOf(commandName));
    }

    text = text
        .replace(/!\w+\s*\([^\n]*\)/g, ' ')
        .replace(/!\w+/g, ' ')
        .replace(/[a-zA-Z_]\w*\(\s*[-\d.,\s"']*\)/g, ' ')
        .replace(/[a-zA-Z_]\w*\(\s*$/g, ' ')
        .replace(/^!\s*$/g, '')
        .replace(/^\s*"[^"]*"\s*\)?\s*$/g, '')
        .replace(/"\s*\)?\s*$/g, '')
        .replace(/[\[【](Wink|生气|疑惑|惊讶|担忧|平静|happy|angry|sad|think)[\]】]/gi, '')
        .replace(/[*_`>#]+/g, ' ')
        .replace(/^[\d\s,.\-()，。]+$/g, '')
        .replace(/^\s*,\s*[-\d.]+\s*[,]?\)?\s*$/g, '')
        .replace(/\s+/g, ' ')
        .trim();

    if (looksLikeCommandFragment(text)) return '';
    if (isSpeechNoise(text)) return '';
    if (text.length <= 1) return '';
    return text;
}