const ADDRESS_BOUNDARIES = new Set([
    ',', '.', '!', '?', ':', ';',
    '\uFF0C', '\u3002', '\uFF01', '\uFF1F', '\uFF1A', '\uFF1B', '\u3001',
    '(', ')', '[', ']', '{', '}', '<', '>',
    '\uFF08', '\uFF09', '\u3010', '\u3011', '\u300A', '\u300B',
    '"', "'", '\u201C', '\u201D', '\u2018', '\u2019',
]);

const EMERGENCY_WORDS = [
    '\u6551\u547D',
    '\u5371\u9669',
    '\u5FEB\u8DD1',
    '\u505C\u4E0B',
    '\u522B\u52A8',
    '\u4F4F\u624B',
    'help',
    'stop',
    'danger',
    'run',
];

function normalizeName(name) {
    return String(name || '').trim().toLowerCase();
}

function isBoundary(char) {
    return !char || /\s/u.test(char) || ADDRESS_BOUNDARIES.has(char);
}

function uniqueNames(botName, aliases = []) {
    return [...new Set([botName, ...aliases].map(normalizeName).filter(Boolean))];
}

function findAddressSpan(message, botName, aliases = []) {
    const text = String(message || '');
    const lower = text.toLowerCase();

    for (const name of uniqueNames(botName, aliases)) {
        const mention = `@${name}`;
        let index = lower.indexOf(mention);
        while (index !== -1) {
            const end = index + mention.length;
            if (isBoundary(lower[index - 1]) && isBoundary(lower[end])) {
                return { start: index, end };
            }
            index = lower.indexOf(mention, index + 1);
        }

        index = lower.indexOf(name);
        while (index !== -1) {
            const end = index + name.length;
            if (isBoundary(lower[index - 1]) && isBoundary(lower[end])) {
                return { start: index, end };
            }
            index = lower.indexOf(name, index + 1);
        }
    }

    return null;
}

function stripAddressPrefix(message, botName, aliases = []) {
    const text = String(message || '').trim();
    const span = findAddressSpan(text, botName, aliases);
    if (!span) return text;

    const before = text.slice(0, span.start).trim();
    const after = text.slice(span.end).trim();
    const strippedAfter = after.replace(/^[\s,.!?:;\uFF0C\u3002\uFF01\uFF1F\uFF1A\uFF1B\u3001]+/u, '').trim();
    const strippedBefore = before.replace(/[\s,.!?:;\uFF0C\u3002\uFF01\uFF1F\uFF1A\uFF1B\u3001]+$/u, '').trim();

    return strippedAfter || strippedBefore || text;
}

function containsDirectAddress(message, botName, aliases = []) {
    return Boolean(findAddressSpan(message, botName, aliases));
}

function containsEmergency(message) {
    const text = String(message || '').toLowerCase();
    return EMERGENCY_WORDS.some((word) => text.includes(word));
}

export function routeIncomingMessage({
    source,
    message,
    botName,
    aliases = [],
    channel = 'chat',
    onlyChatWith = [],
    allowPublicWithoutMention = false,
} = {}) {
    const cleanSource = String(source || '').trim();
    const cleanMessage = String(message || '').trim();
    const cleanBot = String(botName || '').trim();

    if (!cleanSource || !cleanMessage) return { respond: false, reason: 'empty' };
    if (normalizeName(cleanSource) === normalizeName(cleanBot)) return { respond: false, reason: 'self' };

    if (channel === 'system' || channel === 'whisper' || channel === 'voice') {
        return {
            respond: true,
            reason: channel,
            addressed: true,
            message: stripAddressPrefix(cleanMessage, cleanBot, aliases),
        };
    }

    if (onlyChatWith.length > 0 && !onlyChatWith.includes(cleanSource)) {
        return { respond: false, reason: 'not_allowed_source' };
    }

    if (containsDirectAddress(cleanMessage, cleanBot, aliases)) {
        return {
            respond: true,
            reason: 'direct_address',
            addressed: true,
            message: stripAddressPrefix(cleanMessage, cleanBot, aliases),
        };
    }

    if (containsEmergency(cleanMessage)) {
        return { respond: true, reason: 'emergency', addressed: true, message: cleanMessage };
    }

    if (allowPublicWithoutMention) {
        return { respond: true, reason: 'public_allowed', addressed: false, message: cleanMessage };
    }

    return { respond: false, reason: 'public_not_addressed' };
}
