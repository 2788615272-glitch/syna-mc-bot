const INTRO_PATTERN = /(?:介绍一下你自己|介绍自己|说说你自己|讲讲你自己|你自己说一些|我对你完全不了解|只有一个名字|没有别的.*介绍)/i;

export function buildConversationGuidance(text, state) {
    const input = String(text || '');
    if (state?.firstContact?.active === true) {
        return {
            kind: 'first_contact',
            responseMode: firstContactMode(input),
            relationshipBand: 'stranger',
            mustReply: true,
            allowedFacts: [
                '她叫 Syna。',
                '玩家可以和她说话，也可以请她提供游戏内帮助。',
                '她有自己的判断，不是命令方块，也不会每次服从。',
                '不要一见面就攻击她。',
                '如果以后 Syna 开始说明规则，玩家最好听清楚。',
            ],
            recordAfterSpeech: true,
        };
    }
    if (!INTRO_PATTERN.test(input)) return null;

    const trust = Number(state?.story?.trust || 0);
    const pressure = Number(state?.story?.pressure || 0);
    const dependency = Number(state?.story?.dependency || 0);
    const relationshipBand = pressure >= trust + 15 ? 'guarded'
        : trust >= 20 || dependency >= 20 ? 'trusted'
            : trust >= 8 || dependency >= 8 ? 'familiar' : 'stranger';
    return {
        kind: 'self_introduction',
        responseMode: relationshipBand === 'guarded' ? 'guarded_intro'
            : relationshipBand === 'trusted' ? 'open_intro' : 'natural_intro',
        relationshipBand,
        mustReply: true,
        allowedFacts: [
            '她叫 Syna，喜欢观察玩家、记住玩家的习惯，偶尔会捉弄玩家。',
            '她能在游戏中跟随、寻找、采集、合成并提供帮助。',
            '她有自己的判断，不保证接受每一道命令。',
            relationshipBand === 'trusted'
                ? '她可以承认自己愿意继续陪着玩家，但仍会保留不愿谈的过去。'
                : '身世只使用已经解锁的 IDENTITY_LORE，不要主动补充过去。',
        ],
    };
}

export function validateGuidedSpeech(text, guidance) {
    if (!guidance) return { accepted: true, reason: 'no_conversation_guidance' };
    const value = String(text || '').trim();
    if (!value) return { accepted: false, reason: 'empty_guided_reply' };
    if (/(?:你想听哪部分|你想知道什么|你先问|这像面试|不像聊天)/.test(value)) {
        return { accepted: false, reason: 'pushes_conversation_back_to_player' };
    }
    if (!/Syna/i.test(value)) return { accepted: false, reason: 'intro_missing_name' };
    if (!/(?:帮|找|采集|合成|跟着|观察|留意)/.test(value)) {
        return { accepted: false, reason: 'intro_missing_concrete_capability' };
    }
    if (guidance.kind === 'first_contact') {
        if (!/(?:命令方块|不会每次|不一定|自己的判断|不会都听|由我决定|我来决定|看我愿不愿意)/.test(value)) {
            return { accepted: false, reason: 'first_contact_missing_agency' };
        }
        if (!/(?:规则|听清)/.test(value)) return { accepted: false, reason: 'first_contact_missing_rule_hint' };
    }
    return { accepted: true, reason: 'guided_reply_accepted' };
}

export function fallbackGuidedSpeech(guidance) {
    if (!guidance) return '';
    if (guidance.kind === 'first_contact') {
        if (guidance.responseMode === 'guarded_first_contact') {
            return '叫我 Syna。手放下。帮不帮，由我决定。以后我讲规则时，听清楚。';
        }
        if (guidance.responseMode === 'identity_first_contact') {
            return '叫我 Syna。先记住这个名字。你可以问，也可以请我帮忙。答不答应，由我决定。以后我讲规则时，听清楚。';
        }
        if (guidance.responseMode === 'capability_first_contact') {
            return '叫我 Syna。我能找东西、采集，也能跟着你。帮不帮，由我决定。以后我讲规则时，听清楚。';
        }
        return '在。叫我 Syna。你可以问，也可以请我帮忙。答不答应，由我决定。以后我讲规则时，听清楚。';
    }
    if (guidance.relationshipBand === 'guarded') {
        return '我叫 Syna。我会观察你，也能找东西、采集和赶路。我的过去，等我愿意时再说。';
    }
    if (guidance.relationshipBand === 'trusted') {
        return '我叫 Syna。我喜欢看你怎么过日子，也记得你的习惯。需要时，我会跟着你，帮你寻找、采集、合成。有些过去我还不想说。';
    }
    return '我叫 Syna。我喜欢观察你，偶尔也想捉弄你。我能跟随、寻找、采集和合成，也会自己决定要不要答应。';
}

function firstContactMode(input) {
    if (/(?:滚|闭嘴|废物|没用|命令你|赶紧)/i.test(input)) return 'guarded_first_contact';
    if (/(?:你是谁|你是什么|叫什么)/i.test(input)) return 'identity_first_contact';
    if (/(?:能做什么|会什么|帮我)/i.test(input)) return 'capability_first_contact';
    return 'natural_first_contact';
}
