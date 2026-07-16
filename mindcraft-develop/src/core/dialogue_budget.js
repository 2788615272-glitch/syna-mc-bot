export function selectSpeechBudget({ text, dialogueRuling, identityLore, firstContact, conversationGuidance, horror, actionContext }) {
    const input = String(text || '');
    if (firstContact?.active) return { maxChars: 180, maxSentences: 6, kind: 'first_contact' };
    if (conversationGuidance?.relationshipBand === 'guarded') {
        return { maxChars: 80, maxSentences: 3, kind: 'guarded_introduction' };
    }
    if (/(?:介绍一下你自己|介绍自己|说说你自己|讲讲你自己|你自己说一些|我对你完全不了解)/i.test(input)) {
        return { maxChars: 180, maxSentences: 6, kind: 'self_introduction' };
    }
    if (identityLore) return { maxChars: 110, maxSentences: 4, kind: 'identity_lore' };
    if (dialogueRuling?.forceSilence) return { maxChars: 0, maxSentences: 0, kind: 'forced_silence' };
    if (horror) return { maxChars: 72, maxSentences: 3, kind: 'horror' };
    if (dialogueRuling?.kind === 'true_name' || dialogueRuling?.kind === 'power_dare') {
        return { maxChars: 64, maxSentences: 3, kind: 'guarded' };
    }
    if (actionContext) return { maxChars: 96, maxSentences: 4, kind: 'action_receipt' };
    return { maxChars: 110, maxSentences: 4, kind: 'ordinary' };
}

export function stripModelTokens(value) {
    return String(value || '')
        .replace(/<\/?s>/gi, '')
        .replace(/<\|[^|>]+\|>/g, '')
        .replace(/<\/?(?:assistant|user|system)>/gi, '')
        .trim();
}

export function truncateAtSpeechBoundary(value, maxChars) {
    const text = String(value || '');
    const limit = Math.max(1, Number(maxChars) || 1);
    if (Array.from(text).length <= limit) return text;
    const sentences = text.match(/[^。！？!?]+[。！？!?]?/gu) || [text];
    let result = '';
    for (const sentence of sentences) {
        if (Array.from(result + sentence).length > limit) break;
        result += sentence;
    }
    if (result.trim()) return result.trim();
    const chars = Array.from(text).slice(0, limit - 1).join('');
    const soft = Math.max(chars.lastIndexOf('，'), chars.lastIndexOf('；'), chars.lastIndexOf('、'));
    return (soft >= Math.floor(limit * 0.55) ? chars.slice(0, soft) : chars).trim() + '。';
}
