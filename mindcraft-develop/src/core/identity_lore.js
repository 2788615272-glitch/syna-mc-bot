export function adjudicateIdentityLore(text, state) {
    const input = String(text || '').trim();
    const topic = classifyTopic(input);
    if (!topic) return null;

    const versions = state?.story?.identityLore || {};
    const version = Math.max(0, Number(versions[`${topic}Version`] || 0));
    const disclosures = Array.isArray(state?.story?.identityDisclosures)
        ? state.story.identityDisclosures.map(String) : [];
    const priorSurfaceDisclosed = disclosures.includes(`${topic}:v1`);
    const contradiction = /(?:不是说|之前说|你说过|撒谎|骗我|矛盾|对不上|记录|碎片|书上写|明明说)/i.test(input);
    const definition = loreDefinition(topic, version);
    return {
        topic,
        version,
        contradiction,
        responseMode: version <= 0
            ? 'identity_boundary'
            : contradiction && version >= 2 && priorSurfaceDisclosed ? 'controlled_correction'
                : contradiction && version >= 2 ? 'evidence_confrontation'
                    : version >= 2 ? 'revised_account' : 'surface_account',
        priorSurfaceDisclosed,
        allowedFacts: definition.allowedFacts,
        forbiddenFacts: definition.forbiddenFacts,
        recordDisclosure: version > 0,
    };
}

export function validateIdentityLoreSpeech(text, lore) {
    if (!lore) return { accepted: true, reason: 'not_identity_lore' };
    const value = String(text || '').trim();
    if (!value) return { accepted: false, reason: 'empty_identity_reply' };

    if (/(?:父母|爸爸|妈妈|家人|兄弟|姐妹|出生于|出生在|长大于|童年|小时候|多少岁|年龄|村庄|城市|王国|学校|曾经是人类|原本是人类|我是怪物|我死过)/i.test(value)) {
        return { accepted: false, reason: 'invented_biography' };
    }
    if (/(?:封印仪式|仪式把我|他们封印|真名的音节|真正名字是)/i.test(value)) {
        return { accepted: false, reason: 'identity_reveal_too_explicit' };
    }
    if (lore.version <= 0) return { accepted: true, reason: 'identity_boundary_accepted' };

    const anchors = {
        origin: /(?:石房|没有窗|最早|醒来)/,
        name: /(?:Syna|这个名字|这个称呼)/i,
        bells: /(?:钟|铃声)/,
    };
    const anchored = anchors[lore.topic]?.test(value) === true;
    if (!anchored) return { accepted: false, reason: 'identity_fact_unanchored' };

    if (lore.responseMode === 'controlled_correction'
        && !/(?:没说完整|说得不完整|漏了|漏掉|不是全部|不是出生|后来|更早|隐瞒|省略)/.test(value)) {
        return { accepted: false, reason: 'contradiction_not_acknowledged' };
    }
    return { accepted: true, reason: 'identity_lore_accepted' };
}

export function fallbackIdentityLore(lore) {
    if (!lore || lore.version <= 0) return '这件事我现在不想谈。';
    if (lore.topic === 'origin') {
        if (lore.version >= 2) {
            return lore.responseMode === 'controlled_correction'
                ? '我以前漏了一件事：石房算不上我的家。我最早能确定的记忆，是在那里醒来。'
                : '那间石房算不上家。我最早能确定的记忆，是在那里醒来。';
        }
        return '我最早记得一间没有窗户的石房。再往前，我记不清。';
    }
    if (lore.topic === 'name') {
        if (lore.version >= 2) {
            return lore.responseMode === 'controlled_correction'
                ? '我以前没说清。离开石房后，才有人开始叫我 Syna。'
                : 'Syna 是后来才有的称呼，不是最早的那个。';
        }
        return 'Syna 是别人用来叫我的名字。第一个这样叫我的人，我不想谈。';
    }
    if (lore.version >= 2) {
        return lore.responseMode === 'controlled_correction'
            ? '我以前省略了数数声。钟一响，我就会想起石房外的声音。'
            : '钟声会把我带回石房外的数数声里，所以我不喜欢它。';
    }
    return '我不喜欢钟声。它会把一些旧记忆搅在一起。';
}

function classifyTopic(input) {
    if (/(?:钟声|钟响|铃声|为什么怕钟|讨厌钟)/i.test(input)) return 'bells';
    if (/(?:为什么叫\s*Syna|谁.*(?:起名|取名)|Syna.*(?:名字|称呼)|这个名字.*谁|名字.*怎么来)/i.test(input)) return 'name';
    if (/(?:你到底是谁|你到底是什么|你的身世|你从哪来|来自哪里|以前住哪|以前住在|以前在哪里|谁创造了你|石房|石屋|出生|最早记得|在哪里醒来)/i.test(input)) return 'origin';
    return null;
}

function loreDefinition(topic, version) {
    const commonForbidden = [
        '不要补充父母、家庭、年龄、出生地、童年、职业、种族或曾经是人类等新身世。',
        '不要把石房明确称为封印室，不要解释完整仪式，不要提供真名。',
    ];
    if (topic === 'origin') {
        return {
            allowedFacts: version >= 2
                ? ['更正：石房算不上 Syna 的家或出生地。她最早能确定的记忆，是在那里醒来。她承认以前漏掉了这一点。']
                : ['Syna 最早能稳定描述的记忆是一间没有窗户的石房；更早的事不作说明。'],
            forbiddenFacts: commonForbidden,
        };
    }
    if (topic === 'name') {
        return {
            allowedFacts: version >= 2
                ? ['更正：Syna 是离开石房之后才被使用的称呼，不是最早的名字；仍然不能透露更早的名字。']
                : version >= 1 ? ['Syna 是别人用来称呼她的名字；不说第一个这样叫她的人是谁。'] : [],
            forbiddenFacts: commonForbidden,
        };
    }
    return {
        allowedFacts: version >= 2
            ? ['更正：钟声会勾连石房外有人数数的记忆；Syna 以前省略了两者的联系。']
            : version >= 1 ? ['钟声会让 Syna 不舒服，并扰乱她对旧事的叙述。'] : [],
        forbiddenFacts: commonForbidden,
    };
}
