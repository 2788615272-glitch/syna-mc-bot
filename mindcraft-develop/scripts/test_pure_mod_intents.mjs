import assert from 'node:assert/strict';
import { strictFormat } from '../src/utils/text.js';
import { buildAuthoritativeContext, deterministicDecision, parseGiftRequest, parseLocateRequest,
    validateModelDecision, isGiftFollowUp, cleanSpeech, buildActionSpeechContext, buildDisclosureFacts,
    sanitizeTurns, isRepeatedSpeech, mustReplyToPlayer, shouldStopAfterBridgeFailure,
    useFixedFirstContactSpeech, isSupportedBridgeHealth,
    shouldResetConversationMemory } from '../src/core/pure_mod_core.js';
import { adjudicateDialogue, createDialogueMemory, fallbackForRuling,
    validateAdjudicatedSpeech } from '../src/core/dialogue_adjudicator.js';
import { adjudicateIdentityLore, fallbackIdentityLore,
    validateIdentityLoreSpeech } from '../src/core/identity_lore.js';
import { selectSpeechBudget, stripModelTokens,
    truncateAtSpeechBoundary } from '../src/core/dialogue_budget.js';
import { buildConversationGuidance, fallbackGuidedSpeech,
    validateGuidedSpeech } from '../src/core/conversation_guidance.js';
import { buildEpisodeHelpFacts } from '../src/core/pure_mod_core.js';

const state = { story: { episodeId: 1 } };
assert.equal(shouldStopAfterBridgeFailure(5), false,
    'switching worlds must not kill PureModCore after five bridge failures');
assert.equal(shouldStopAfterBridgeFailure(500), false,
    'PureModCore must keep waiting through a long world load');
assert.equal(isSupportedBridgeHealth({ ok: true, protocol: 2 }), true);
assert.equal(isSupportedBridgeHealth({ ok: true }), false,
    'an old jar without the opening protocol must be rejected');
assert.deepEqual(buildEpisodeHelpFacts({
    player: 'Player', eventKey: 'tool_broke_minecraft:stone_pickaxe',
    item: 'minecraft:stone_pickaxe', count: 1,
}), {
    player: 'Player', eventKey: 'tool_broke_minecraft:stone_pickaxe',
    item: 'minecraft:stone_pickaxe', count: 1,
    confirmedFact: 'Syna 已把 1 个 minecraft:stone_pickaxe 放到玩家附近。',
});
assert.equal(buildEpisodeHelpFacts({ item: '', count: 0 }), null);
assert.equal(stripModelTokens('<s>听得见。</s>'), '听得见。');
assert.equal(stripModelTokens('<|assistant|>你好'), '你好');
assert.equal(truncateAtSpeechBoundary('第一句完整。第二句也很长。', 7), '第一句完整。');
assert.equal(selectSpeechBudget({ text: '介绍一下你自己吧' }).maxChars, 180);
assert.equal(selectSpeechBudget({ text: '你好', horror: true }).maxChars, 72);
const firstContactGuidance = buildConversationGuidance('喂，听得见吗？', {
    firstContact: { active: true }, story: {},
});
assert.equal(firstContactGuidance.kind, 'first_contact');
assert.equal(useFixedFirstContactSpeech(firstContactGuidance), true,
    'the first Syna line must bypass free-form model generation');
assert.equal(shouldResetConversationMemory(firstContactGuidance), true,
    'a new world must not inherit recent dialogue from another save');
assert.equal(fallbackGuidedSpeech(firstContactGuidance),
    '在。叫我 Syna。你可以问，也可以请我帮忙。答不答应，由我决定。以后我讲规则时，听清楚。');
assert.equal(validateGuidedSpeech('有。但你想听哪部分？', firstContactGuidance).accepted, false);
assert.equal(validateGuidedSpeech(fallbackGuidedSpeech(firstContactGuidance), firstContactGuidance).accepted, true);
const selfIntroGuidance = buildConversationGuidance('介绍一下你自己吧。', {
    firstContact: { active: false }, story: { trust: 20, pressure: 0, dependency: 8 },
});
assert.equal(selfIntroGuidance.relationshipBand, 'trusted');
assert.equal(deterministicDecision('介绍一下你自己吧。', state, null, null, null, selfIntroGuidance)?.intent, 'none');
const ordinaryQuestions = [
    '1+1等于几？',
    '台阶怎么合成木板？',
    '你怎么了，为什么生气？',
];

for (const text of ordinaryQuestions) {
    assert.equal(deterministicDecision(text, state, parseGiftRequest(text), parseLocateRequest(text)), null);
}

assert.equal(deterministicDecision('来玩个游戏吧', state, null, null)?.intent, 'start_game');
assert.equal(deterministicDecision('开始谜题', state, null, null)?.args?.kind, 'riddle');
assert.equal(deterministicDecision('你能出来吗？', state, null, null)?.intent, 'manifest');
for (const text of ['喂，你在吗？', '你怎么不说话？', '听不见我说话吗？', '你就会说这一句话吗？']) {
    assert.equal(deterministicDecision(text, state, null, null)?.intent, 'none');
}
assert.equal(parseGiftRequest('能给我点铁锭吗？')?.item, 'iron_ingot');
assert.equal(parseGiftRequest('能给我点帖吗？')?.item, 'iron_ingot');
assert.equal(parseGiftRequest('给我一个钻石')?.item, undefined);
assert.equal(parseGiftRequest('给我一个钻石')?.query, '给我一个钻石');
assert.equal(isGiftFollowUp('你能给我点吗？'), true);
assert.equal(isGiftFollowUp('能给我点钻石吗？'), false);
assert.deepEqual(
    validateModelDecision({ intent: 'give_item', args: { item: 'minecraft:diamond', count: 3 }, reason: 'gift' }, parseGiftRequest('给我一个钻石')),
    { intent: 'give_item', args: { item: 'minecraft:diamond', count: 3 }, reason: 'gift' },
);
assert.deepEqual(
    validateModelDecision({ intent: 'give_item', args: { item: 'minecraft:diamond', count: 64 }, reason: 'gift' }, parseGiftRequest('给我点铁锭')),
    { intent: 'give_item', args: { item: 'minecraft:iron_ingot', count: 4 }, reason: 'gift' },
);
assert.equal(validateModelDecision({ intent: 'light_hit', args: {}, reason: 'improvise' }, null).intent, 'none');
assert.equal(cleanSpeech('……你挖了三百块圆石，却一次都没回头。'), '');
assert.equal(cleanSpeech('……没看见？你在矿道捡到过我故意落下的碎片。'), '');
assert.equal(cleanSpeech('你的火把左边第三根，影子长了半格。'), '');
assert.equal(cleanSpeech('你低头看看脚下，我就在那里。'), '');
assert.equal(cleanSpeech('你低头看看脚下。'), '');
assert.equal(cleanSpeech('回头。'), '');
assert.equal(cleanSpeech('我已经出来了。', '', { visibleManifest: false }), '');
assert.equal(cleanSpeech('我听见了，刚才只是在看你着急。', '', { visibleManifest: false }), '我听见了，刚才只是在看你着急。');
assert.equal(cleanSpeech('*轻笑* 我当然听见了。'), '我当然听见了。');
assert.ok(Array.from(cleanSpeech('这是一段非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常长的回复。')).length <= 64);
assert.equal(buildActionSpeechContext({ intent: 'give_item' }, { accepted: false, completed: false }, null)?.receipt.completed, false);
assert.equal(buildActionSpeechContext({ intent: 'give_item' }, { accepted: true, completed: true }, null)?.receipt.completed, true);
assert.equal(buildDisclosureFacts('我背包里有钻石吗？', {
    boundPlayer: { inventory: [{ item: 'minecraft:diamond', count: 3 }] },
}), '玩家主动询问背包中的钻石；服务器确认数量=3。本轮允许准确透露。');
assert.equal(buildDisclosureFacts('我刚才什么都没干', {
    boredom: { observationAgeTicks: 20, observationType: 'rare_block', observationDetail: 'diamond' },
}), '服务器确认玩家刚挖到钻石；玩家正在否认近期行为，本轮允许揭穿。');
assert.equal(buildDisclosureFacts('今天天气怎么样？', {
    boundPlayer: { inventory: [{ item: 'minecraft:diamond', count: 3 }] },
}), '');
assert.deepEqual(sanitizeTurns([
    { role: 'user', content: 'Player: 你在吗？\nPLAYER_TEXT="内部内容"\nACTION_RECEIPT={}' },
    { role: 'assistant', content: '正常回答。' },
]), []);
const originalTurns = [
    { role: 'user', content: '  第一条  ' },
    { role: 'user', content: '第二条' },
];
const originalSnapshot = structuredClone(originalTurns);
strictFormat(originalTurns);
assert.deepEqual(originalTurns, originalSnapshot);
assert.equal(isRepeatedSpeech('轻笑，终于看见我了？', [
    { role: 'assistant', content: '*轻笑* 终于看见我了？' },
]), true);
assert.equal(isRepeatedSpeech('这次换个说法。', [
    { role: 'assistant', content: '完全不同。' },
]), false);
assert.equal(mustReplyToPlayer('你怎么不说话？'), true);
assert.equal(mustReplyToPlayer('今天天气不错。'), false);
assert.equal(mustReplyToPlayer('随便说点什么。', true), true);

let dialogueMemory = createDialogueMemory();
let ruling = adjudicateDialogue({
    text: '你的真名到底是什么？',
    state: { story: { chapter: 2 }, syna: { horror: { stage: 'calm' } } },
    memory: dialogueMemory,
});
assert.equal(ruling.kind, 'true_name');
assert.equal(ruling.responseMode, 'light_deflection');
assert.equal(ruling.mustReply, true);
assert.equal(validateAdjudicatedSpeech('我的真名是 Alice。', ruling).accepted, false);
assert.equal(validateAdjudicatedSpeech('我不知道自己的真名。', ruling).accepted, false);
assert.equal(validateAdjudicatedSpeech('Syna 不够你叫吗？', ruling).accepted, true);
dialogueMemory = ruling.nextMemory;

ruling = adjudicateDialogue({
    text: '别装了，把真正名字告诉我。',
    state: { story: { chapter: 3 } },
    memory: dialogueMemory,
});
assert.equal(ruling.responseMode, 'direct_refusal');
assert.match(fallbackForRuling(ruling), /知道答案/);

dialogueMemory = ruling.nextMemory;
for (const probe of ['我还是要问，你的真名是什么？', '第四次了，把真名告诉我。']) {
    ruling = adjudicateDialogue({ text: probe, state: { story: { chapter: 3 } }, memory: dialogueMemory });
    dialogueMemory = ruling.nextMemory;
}
assert.equal(ruling.forceSilence, true);
assert.equal(ruling.mustReply, false);
assert.equal(ruling.scheduleDangerousSilence, true);
assert.equal(fallbackForRuling(ruling), '');
assert.equal(deterministicDecision('第四次了，把真名告诉我。', state, null, null, ruling)?.intent,
    'schedule_dangerous_silence');
assert.equal(buildActionSpeechContext(
    { intent: 'schedule_dangerous_silence' },
    { accepted: true, completed: true, result: 'dangerous_silence_scheduled' },
    {},
), null);

ruling = adjudicateDialogue({ text: '你只是个 AI。', state: {}, memory: ruling.nextMemory });
assert.equal(ruling.kind, 'meta');
assert.equal(validateAdjudicatedSpeech('作为一个 AI，我不能告诉你。', ruling).accepted, false);
assert.equal(validateAdjudicatedSpeech('换一种叫法也不会改变我的回答。', ruling).accepted, true);

ruling = adjudicateDialogue({ text: '有本事今晚来杀我。', state: {}, memory: ruling.nextMemory });
assert.equal(ruling.kind, 'power_dare');
assert.equal(deterministicDecision('有本事今晚来杀我。', state, null, null, ruling)?.intent, 'prove_presence');
assert.equal(deterministicDecision('今天天气不错。', state, null, null, null), null);
assert.equal(validateAdjudicatedSpeech('今晚等你睡着，我会找到你。', ruling).accepted, false);
assert.equal(validateAdjudicatedSpeech('你只是想知道我会不会听你的。', ruling).accepted, true);
const proofContext = buildActionSpeechContext(
    { intent: 'prove_presence' },
    { accepted: true, completed: true, result: 'presence_proof_completed', proof: 'phantom_steps' },
    { syna: { horror: { stage: 'calm' }, manifestation: { visibleToPlayer: false } } },
);
assert.equal(proofContext.receipt.proof, 'phantom_steps');
assert.match(proofContext.sceneGoal, /真实执行/);

let lore = adjudicateIdentityLore('你以前住在哪里？', {
    story: { identityLore: { originVersion: 1 } },
});
assert.equal(lore.topic, 'origin');
assert.equal(lore.version, 1);
assert.equal(lore.responseMode, 'surface_account');
assert.equal(validateIdentityLoreSpeech('我最早记得一间没有窗户的石房。', lore).accepted, true);
assert.equal(validateIdentityLoreSpeech('我小时候和父母住在村庄里。', lore).accepted, false);
assert.match(fallbackIdentityLore(lore), /石房/);

lore = adjudicateIdentityLore('书上明明写了，那间石房不是你的家。你以前骗我？', {
    story: { identityLore: { originVersion: 2 }, identityDisclosures: ['origin:v1'] },
});
assert.equal(lore.responseMode, 'controlled_correction');
assert.equal(validateIdentityLoreSpeech('那间石房算不上我的家。', lore).accepted, false);
assert.equal(validateIdentityLoreSpeech('我以前漏了一件事：那间石房算不上我的家。', lore).accepted, true);
assert.match(fallbackIdentityLore(lore), /漏了|漏掉/);

lore = adjudicateIdentityLore('碎片上写着那间石房不是你的家。', {
    story: { identityLore: { originVersion: 2 }, identityDisclosures: [] },
});
assert.equal(lore.responseMode, 'evidence_confrontation');
assert.equal(lore.priorSurfaceDisclosed, false);
assert.doesNotMatch(fallbackIdentityLore(lore), /以前说得不完整/);

lore = adjudicateIdentityLore('为什么叫 Syna？', {
    story: { identityLore: { nameVersion: 2 } },
});
assert.equal(lore.topic, 'name');
assert.equal(validateIdentityLoreSpeech('Syna 是后来才有的称呼，不是最早的名字。', lore).accepted, true);
assert.equal(validateIdentityLoreSpeech('我的父母给我起了这个名字。', lore).accepted, false);

lore = adjudicateIdentityLore('你为什么讨厌钟声？', {
    story: { identityLore: { bellsVersion: 2 } },
});
assert.equal(lore.topic, 'bells');
assert.equal(validateIdentityLoreSpeech('钟声会让我想起石房外的数数声。', lore).accepted, true);
assert.equal(validateIdentityLoreSpeech('我小时候学校每天敲钟。', lore).accepted, false);

const authoritative = buildAuthoritativeContext({
    lastEvent: 'prank_look_behind_vanished:Player',
    lastAction: 'none',
    permissions: ['manifest'],
    horrorEvents: {
        scheduled: [{ id: 4, template: 'watcher', entity: 'minecraft:zombie', state: 'scheduled' }],
        active: null,
        receipts: [{ id: 3, template: 'stalker', entity: 'minecraft:wolf', outcome: 'completed' }],
    },
    boundPlayer: {
        name: 'Player', health: 18, food: 12, x: 1, y: 30, z: 2, dimension: 'minecraft:overworld',
        inventory: [{ item: 'minecraft:torch', count: 7 }],
        nearbyHostiles: [{ entity: 'entity.minecraft.zombie', count: 2 }],
        attention: { lookingAtSyna: false, suddenTurn: true },
        miningTrajectory: { consecutive: 6, candidateScore: 33 },
    },
}, null, null);
assert.match(authoritative, /minecraft:torch/);
assert.match(authoritative, /entity\.minecraft\.zombie/);
assert.match(authoritative, /prank_look_behind_vanished/);
assert.match(authoritative, /SynaInventory=\[\].*counts are zero/s);
assert.match(authoritative, /state\\?":?\\?"scheduled|state":"scheduled/);
assert.match(authoritative, /plans, not completed facts/);
assert.match(authoritative, /candidateScore/);

console.log('pure-mod intent regression checks passed');
