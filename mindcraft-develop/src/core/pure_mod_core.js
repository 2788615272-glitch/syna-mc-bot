import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { createModel } from '../models/_model_map.js';
import { readModelConfig } from '../mindcraft/model_config.js';
import { buildSystemPrompt, loadPromptPack } from './syna_prompt_pack.js';
import { adjudicateDialogue, createDialogueMemory, fallbackForRuling,
    validateAdjudicatedSpeech } from './dialogue_adjudicator.js';
import { adjudicateIdentityLore, fallbackIdentityLore,
    validateIdentityLoreSpeech } from './identity_lore.js';
import { selectSpeechBudget, stripModelTokens, truncateAtSpeechBoundary } from './dialogue_budget.js';
import { buildConversationGuidance, fallbackGuidedSpeech,
    validateGuidedSpeech } from './conversation_guidance.js';

const bridgeUrl = String(process.env.SYNA_BRIDGE_URL || 'http://127.0.0.1:8765').replace(/\/+$/, '');
const memoryPath = path.resolve('bots', 'syna', 'pure_mod_memory.json');
const auditPath = path.resolve('logs', 'pure_mod_core_audit.jsonl');
const pollMs = 500;
const maxReconnectFailures = 5;
let running = true;
let lastEventId = 0;
let turns = [];
let bridgeOnline = true;
let pollFailureCount = 0;
let lastWaitingLogAt = 0;
let lastReceipt = null;
let lastGiftRequest = null;
let lastOpportunityId = 0;
let lastObservationId = 0;
let lastProactiveAt = 0;
let dialogueMemory = createDialogueMemory();
const promptPack = loadPromptPack();
const intentPrompt = buildSystemPrompt(promptPack, 'intent_decision');

function audit(kind, data = {}) {
    try {
        mkdirSync(path.dirname(auditPath), { recursive: true });
        appendFileSync(auditPath, JSON.stringify({ at: new Date().toISOString(), kind, ...data }) + '\n', 'utf8');
    } catch {}
}

function loadMemory() {
    if (!existsSync(memoryPath)) return;
    try {
        const saved = JSON.parse(readFileSync(memoryPath, 'utf8'));
        turns = sanitizeTurns(saved.turns);
        lastReceipt = saved.lastReceipt && typeof saved.lastReceipt === 'object' ? saved.lastReceipt : null;
        lastGiftRequest = saved.lastGiftRequest && typeof saved.lastGiftRequest === 'object'
            ? saved.lastGiftRequest : null;
        lastOpportunityId = Math.max(0, Number(saved.lastOpportunityId || 0));
        lastObservationId = Math.max(0, Number(saved.lastObservationId || 0));
        dialogueMemory = createDialogueMemory(saved.dialogueMemory);
    } catch (error) {
        console.warn('[PureModCore] memory load failed:', error?.message || error);
    }
}

function sanitizeTurns(savedTurns) {
    if (!Array.isArray(savedTurns)) return [];
    const polluted = savedTurns.some(turn => typeof turn?.content === 'string'
        && /(?:^|\n)(?:PLAYER_TEXT|SCENE_GOAL|ACTION_RECEIPT|HORROR_FACTS|DISCLOSURE_FACTS|AUTHORITATIVE_CONTEXT)=/m.test(turn.content));
    if (polluted) return [];
    return savedTurns.filter(turn => {
        if (!turn || !['user', 'assistant'].includes(turn.role) || typeof turn.content !== 'string') return false;
        return true;
    }).map(turn => ({ role: turn.role, content: turn.content.slice(0, 500) })).slice(-24);
}

function saveMemory() {
    mkdirSync(path.dirname(memoryPath), { recursive: true });
    writeFileSync(memoryPath, JSON.stringify({
        turns: turns.slice(-24), lastReceipt, lastGiftRequest, lastOpportunityId, lastObservationId,
        dialogueMemory,
    }, null, 2) + '\n', 'utf8');
}

async function requestJson(url, init = {}, timeoutMs = 2500) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        const response = await fetch(url, { ...init, signal: controller.signal });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return await response.json();
    } finally {
        clearTimeout(timer);
    }
}

async function sendCommand(command) {
    return await requestJson(`${bridgeUrl}/command`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(command),
    });
}

async function speak(text, horror = false) {
    if (!text) return;
    await requestJson('http://127.0.0.1:8766/say', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, interrupt: true, speed: horror ? 0.78 : 1.0 }),
    }, 1200).catch(() => null);
}

function parseDecision(raw) {
    const text = String(raw || '').trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
    let parsed;
    try {
        parsed = JSON.parse(text);
    } catch {
        const start = text.indexOf('{');
        const end = text.lastIndexOf('}');
        if (start >= 0 && end > start) parsed = JSON.parse(text.slice(start, end + 1));
        else parsed = { intent: 'none', args: {}, reason: 'unstructured_model_reply' };
    }
    const allowed = new Set(['none', 'manifest', 'leave', 'give_item', 'locate_block', 'set_horror_stage',
        'disable_horror_fx', 'enable_horror_fx', 'start_game', 'light_hit']);
    return {
        intent: allowed.has(parsed.intent) ? parsed.intent : 'none',
        args: parsed.args && typeof parsed.args === 'object' ? parsed.args : {},
        reason: String(parsed.reason || '').trim().slice(0, 160),
    };
}

function deterministicDecision(text, state, requestedGift, locate, dialogueRuling = null, conversationGuidance = null) {
    const input = String(text || '');
    if (conversationGuidance) {
        return { intent: 'none', args: {}, reason: conversationGuidance.kind };
    }
    if (dialogueRuling?.kind === 'power_dare') {
        return { intent: 'prove_presence', args: {}, reason: 'explicit_power_dare' };
    }
    if (dialogueRuling?.scheduleDangerousSilence) {
        return { intent: 'schedule_dangerous_silence', args: {}, reason: 'repeated_true_name_probe' };
    }
    if (/(\u51fa\u6765|\u8fc7\u6765|\u73b0\u8eab|\u9732\u9762|\u8ba9\u6211\u770b\u770b\u4f60|come here|show yourself)/i.test(input)) {
        return { intent: 'manifest', args: {}, reason: 'explicit_manifest_request' };
    }
    if (/(\u6d88\u5931|\u79bb\u5f00|\u56de\u53bb|\u522b\u51fa\u73b0|go away|leave)/i.test(input)) {
        return { intent: 'leave', args: {}, reason: 'explicit_leave_request' };
    }
    if (/(?:在吗|说话|不说话|听不见|没听见|不会说|只会说|这一句话|回我|理我|回答我|are you there|say something|can you hear me)/i.test(input)) {
        return { intent: 'none', args: {}, reason: 'ordinary_conversation' };
    }
    if (/(\u5173\u6389|\u5173\u4e86|\u505c\u6b62).*(\u6050\u6016|\u97f3\u6548|\u6c1b\u56f4)|disable.*horror/i.test(input)) {
        return { intent: 'disable_horror_fx', args: {}, reason: 'explicit_fx_request' };
    }
    if (/(\u6253\u5f00|\u6062\u590d).*(\u6050\u6016|\u97f3\u6548|\u6c1b\u56f4)|enable.*horror/i.test(input)) {
        return { intent: 'enable_horror_fx', args: {}, reason: 'explicit_fx_request' };
    }
    if (/(\u522b\u8ffd|\u51b7\u9759|\u6062\u590d\u6b63\u5e38|\u505c\u6b62\u8ffd\u6740)/i.test(input)) {
        return { intent: 'set_horror_stage', args: { stage: 'calm' }, reason: 'explicit_calm_request' };
    }
    if (/(\u5f00\u59cb\u8c1c\u9898|\u73a9\u8c1c\u9898|riddle)/i.test(input)) {
        return { intent: 'start_game', args: { kind: 'riddle' }, reason: 'explicit_game_request' };
    }
    if (/(\u72e9\u730e\u6e05\u5355|\u5f00\u59cb\u72e9\u730e|hunt game)/i.test(input)) {
        return { intent: 'start_game', args: { kind: 'hunt' }, reason: 'explicit_game_request' };
    }
    if (/(\u73a9.*\u6e38\u620f|\u6765.*\u6e38\u620f|\u6e38\u620f\u5427|\u6311\u6218\u6211|play a game)/i.test(input)) {
        const kind = Number(state?.story?.episodeId || 1) % 2 === 1 ? 'riddle' : 'hunt';
        return { intent: 'start_game', args: { kind }, reason: 'explicit_game_request' };
    }
    if (locate) return { intent: 'locate_block', args: {}, reason: 'supported_location_request' };
    return null;
}

export { buildAuthoritativeContext, deterministicDecision, parseGiftRequest, parseLocateRequest,
    validateModelDecision, isGiftFollowUp, cleanSpeech, buildActionSpeechContext, buildDisclosureFacts,
    sanitizeTurns, isRepeatedSpeech, mustReplyToPlayer, buildEpisodeHelpFacts,
    shouldStopAfterBridgeFailure, useFixedFirstContactSpeech, isSupportedBridgeHealth,
    shouldResetConversationMemory };

function shouldStopAfterBridgeFailure(failureCount) {
    return false;
}

function useFixedFirstContactSpeech(conversationGuidance) {
    return conversationGuidance?.kind === 'first_contact';
}

function isSupportedBridgeHealth(health) {
    return health?.ok === true && Number(health?.protocol || 0) >= 2;
}

function shouldResetConversationMemory(conversationGuidance) {
    return conversationGuidance?.kind === 'first_contact';
}

function buildEpisodeHelpFacts(event) {
    const item = String(event?.item || '').trim();
    const count = Math.max(0, Number.parseInt(event?.count, 10) || 0);
    if (!item || count <= 0) return null;
    return {
        player: String(event?.player || 'Player').slice(0, 64),
        eventKey: String(event?.eventKey || '').slice(0, 96),
        item,
        count,
        confirmedFact: `Syna 已把 ${count} 个 ${item} 放到玩家附近。`,
    };
}

function validateModelDecision(decision, requestedGift) {
    const allowed = requestedGift
        ? new Set(['none', 'manifest', 'give_item'])
        : new Set(['none', 'manifest', 'leave']);
    if (!allowed.has(decision.intent)) {
        return { intent: 'none', args: {}, reason: 'model_intent_not_authorized' };
    }
    if (decision.intent === 'give_item') {
        const proposed = String(requestedGift.item || decision.args?.item || '').trim().toLowerCase();
        const item = proposed.includes(':') ? proposed : `minecraft:${proposed}`;
        if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(item)) {
            return { intent: 'none', args: {}, reason: 'invalid_model_item_id' };
        }
        const requestedCount = requestedGift.count ?? decision.args?.count ?? 1;
        const count = Math.max(1, Math.min(16, Number.parseInt(requestedCount, 10) || 1));
        return {
            intent: 'give_item',
            args: { item, count },
            reason: decision.reason || 'llm_chose_validated_gift',
        };
    }
    return { ...decision, args: {} };
}

function buildAuthoritativeContext(state, requestedGift, locate) {
    const story = state?.story;
    const storyContext = story?.available
        ? `Story: chapter=${story.chapter}/${story.chapterName}, scene=${story.scene}, trust=${story.trust}, pressure=${story.pressure}, dependency=${story.dependency}, episode=${story.episodeId}, proactiveHelp=${story.proactiveHelpCount}/2, usedEvents=${(story.episodeEvents || []).join('|') || 'none'}, clues=${(story.clues || []).join('|') || 'none'}, outcome=${story.outcome}, cooldownTicks=${story.cooldownTicks}.`
        : 'Story: unavailable.';
    const bodyContext = state?.syna?.horror
        ? `Body: present=true, visible=${state.syna.manifestation?.visibleToPlayer}, remainingTicks=${state.syna.manifestation?.lifetimeTicks}, task=${state.syna.task}, form=${state.syna.horror.form}, horror=${state.syna.horror.stage}, horrorFx=${state.syna.horrorFxEnabled}, anger=${state.syna.horror.anger}, beat=${state.syna.horror.beat}, game=${state.syna.horror.challengeKind}, gameTarget=${state.syna.horror.challengeTarget}, gameClue=${state.syna.horror.challengeClue}, gameProgress=${state.syna.horror.challengeProgress}/${state.syna.horror.challengeRequired}, gameSeconds=${state.syna.horror.challengeSeconds}. SynaInventory=${JSON.stringify(state.syna.inventory?.items || [])}.`
        : 'Body: present=false, visible=false. SynaInventory=[] (all item counts are zero).';
    const player = state?.boundPlayer;
    const playerContext = player
        ? `Player: name=${player.name}, health=${player.health}, food=${player.food}, position=${player.x},${player.y},${player.z}, dimension=${player.dimension}, inventory=${JSON.stringify(player.inventory || [])}, nearbyHostiles=${JSON.stringify(player.nearbyHostiles || [])}, attention=${JSON.stringify(player.attention || {})}, miningTrajectory=${JSON.stringify(player.miningTrajectory || {})}.`
        : 'Player: authoritative state unavailable.';
    const boredom = state?.boredom || {};
    const boredomContext = boredom.available
        ? `Boredom: value=${boredom.boredom}/100, cycle=${boredom.cycle}, phase=${boredom.phase}, pressureFloor=${boredom.pressureFloor}, horrorChance=${boredom.horrorChance}, lastActivity=${boredom.lastActivity}, lastGain=${boredom.lastGain}, lockedRule=${boredom.ruleKind || 'none'}:${boredom.ruleTarget || 'none'}:${boredom.ruleRequired || 0}. Exact rule facts must not be changed.`
        : 'Boredom: unavailable.';
    const horrorEvents = state?.horrorEvents || {};
    const horrorEventContext = `HorrorEvents: scheduled=${JSON.stringify(horrorEvents.scheduled || [])}, active=${JSON.stringify(horrorEvents.active || null)}, receipts=${JSON.stringify(horrorEvents.receipts || [])}. Scheduled events are plans, not completed facts.`;
    const requestContext = requestedGift
        ? `Gift request: player wording=${JSON.stringify(requestedGift.query || '')}, resolvedItem=${requestedGift.item || 'model_must_resolve_registered_id'}, suggestedCount=${requestedGift.count || 'model_may_choose_1_to_16'}. Syna may grant, manifest without granting, or refuse.`
        : 'No gift request.';
    const locateContext = locate ? `Supported resource scan names=${locate}.` : 'No supported resource-location request.';
    return `${storyContext}\n${boredomContext}\n${bodyContext}\n${playerContext}\n${horrorEventContext}\nLast real event=${state?.lastEvent || 'unknown'}. Last completed action=${state?.lastAction || JSON.stringify(lastReceipt) || 'none'}. Permissions=${(state?.permissions || []).join(',')}.\n${requestContext}\n${locateContext}`;
}

function buildActionSpeechContext(decision, receipt, finalState) {
    const intent = String(decision?.intent || 'none');
    const accepted = receipt?.accepted === true && receipt?.completed === true;
    const visibleToPlayer = finalState?.syna?.manifestation?.visibleToPlayer === true;
    if (intent === 'none' || intent === 'schedule_dangerous_silence') return null;
    const goals = {
        give_item: accepted
            ? '确认礼物确实已交付。除非回执明确确认玩家已看见 Syna，否则不要声称玩家看见她，也不要描述她的位置。'
            : '回应礼物没有交付。可以是 Syna 主动拒绝或执行失败，但必须符合回执原因。',
        manifest: accepted && visibleToPlayer
            ? '服务器确认实体已生成且玩家视线确认看见 Syna；可以自然回应，但不要编造具体相对位置。'
            : accepted
                ? '实体已安全生成，但尚未确认玩家客户端看见。正常回应玩家当前的话，但不要说已经出现、不要描述位置，也不要声称玩家看见了。'
                : '回应本次没有现身，不得假装已经出现。',
        leave: accepted ? '回应已经离开或结束现身。' : '回应离开未完成。',
        disable_horror_fx: accepted ? '确认恐怖效果已经关闭。' : '说明本次未能关闭恐怖效果。',
        enable_horror_fx: accepted ? '确认恐怖效果已经恢复。' : '说明本次未能恢复恐怖效果。',
        locate_block: '根据扫描回执回应是否找到目标；可以概括数量，但不要编造坐标。',
        start_game: accepted ? '以 Syna 的口吻开始服务器给定的游戏，并准确转述规则线索。' : '回应游戏没有开始。',
        set_horror_stage: accepted ? '回应阶段变更已经生效。' : '回应阶段变更没有生效。',
        light_hit: accepted ? '把已发生的轻微攻击作为恐怖互动回应，不要扩大伤害。' : '不要声称攻击发生。',
        prove_presence: accepted
            ? '服务器已经真实执行一次受控的存在证明。用一句极短、克制的话回应，例如反问玩家现在是否满意；不要声称玩家一定看见或听见，也不要追加新的威胁。'
            : '存在证明没有执行。不要假装发生了异常；可以拒绝继续表演，若是冷却则不要解释数值机制。',
    };
    const safeReceipt = {
        intent,
        accepted: receipt?.accepted === true,
        completed: receipt?.completed === true,
        result: String(receipt?.result || 'unknown').slice(0, 160),
        visibleToPlayer,
    };
    if (intent === 'locate_block') safeReceipt.matchCount = Array.isArray(receipt?.matches) ? receipt.matches.length : 0;
    if (intent === 'start_game') {
        safeReceipt.challengeKind = String(finalState?.syna?.horror?.challengeKind || '');
        safeReceipt.challengeClue = String(finalState?.syna?.horror?.challengeClue || '');
        safeReceipt.challengeRequired = Number(finalState?.syna?.horror?.challengeRequired || 0);
    }
    if (intent === 'prove_presence') safeReceipt.proof = String(receipt?.proof || 'none');
    return {
        sceneGoal: goals[intent] || '根据真实执行结果自然回应。',
        receipt: safeReceipt,
        horrorFacts: summarizeHorrorFacts(finalState),
    };
}

function summarizeHorrorFacts(state) {
    const horror = state?.syna?.horror || {};
    return {
        stage: String(horror.stage || 'calm'),
        form: String(horror.form || 'normal'),
        beat: String(horror.beat || ''),
        challengeKind: String(horror.challengeKind || ''),
        challengeProgress: Number(horror.challengeProgress || 0),
        challengeRequired: Number(horror.challengeRequired || 0),
        challengeSeconds: Number(horror.challengeSeconds || 0),
    };
}

function evaluateSpeech(raw, fallback = promptPack.settings.fallback_reply, permissions = {}, limits = null) {
    let text = stripModelTokens(raw)
        .replace(/^```(?:text)?\s*/i, '')
        .replace(/\s*```$/, '')
        .replace(/^\s*(?:\*+\s*)?(?:[（(]?\s*(?:轻笑|冷笑|笑|叹气|歪头)\s*[）)]?)(?:\s*\*+)?\s*[:：,，]?\s*/u, '')
        .replace(/[…\.]{2,}/g, '')
        .replace(/\s+/g, ' ')
        .trim();
    if (!text) return { text: fallback, reason: 'empty_model_reply' };
    if (text === promptPack.settings.silence_token) return { text: '', reason: 'model_silence' };
    const forbidden = [
        [!permissions.recentObservation, /(你(?:刚)?挖(?:了|到)|挖了[一二三四五六七八九十百千万\d]+|你刚(?:去|进入|杀|击杀))/, 'unconfirmed_recent_observation'],
        [!permissions.inventory, /(?:背包|包里|身上).*(?:有|没有|数量|个|零)/, 'unconfirmed_inventory'],
        [!permissions.worldDetail, /(?:火把|影子|脚下|身后|背后|左边|右边|面前|头顶|回头|低头)/, 'unconfirmed_world_detail'],
        [permissions.visibleManifest === false, /(?:我(?:已经|早就)?(?:出来|出现|现身|在这|在这里)|你(?:终于|已经)?(?:看见|看到)我|我就在)/, 'unconfirmed_manifest_visibility'],
        [true, /矿道.*(?:捡|看见)|我故意落下|你以为.*模组|跟着我出现|从没想过/, 'unsupported_shared_history'],
    ];
    for (const [enabled, pattern, reason] of forbidden) {
        if (enabled && pattern.test(text)) return { text: fallback, reason };
    }
    const maxSentences = Math.max(1, Number(limits?.maxSentences || promptPack.settings.max_sentences));
    const sentences = text.split(/(?<=[。！？!?])/u).filter(Boolean)
        .slice(0, maxSentences).join('');
    text = sentences || text;
    const maxChars = Math.max(12, Number(limits?.maxChars || promptPack.settings.max_chars));
    text = truncateAtSpeechBoundary(text, maxChars);
    return { text: text || fallback, reason: text ? 'accepted' : 'empty_after_cleanup' };
}

function cleanSpeech(raw, fallback = promptPack.settings.fallback_reply, permissions = {}) {
    return evaluateSpeech(raw, fallback, permissions).text;
}

function normalizeSpeech(text) {
    return String(text || '').toLowerCase()
        .replace(/[\s*（）()「」『』“”‘’。，！？!?、…~～]/g, '');
}

function isRepeatedSpeech(text, history = turns) {
    const normalized = normalizeSpeech(text);
    if (!normalized) return false;
    const recent = history.filter(turn => turn?.role === 'assistant').slice(-4);
    return recent.some(turn => normalizeSpeech(turn.content) === normalized);
}

function mustReplyToPlayer(text, hasActionContext = false) {
    return hasActionContext
        || /(?:在吗|说话|不说话|听不见|没听见|不会说|只会说|回我|理我|回答我|吗[？?]?\s*$|are you there|say something|can you hear me)/i.test(String(text || ''));
}

function buildDisclosureFacts(text, state) {
    const input = String(text || '').replace(/\s+/g, '');
    const inventory = Array.isArray(state?.boundPlayer?.inventory) ? state.boundPlayer.inventory : [];
    const itemRules = [
        [/钻石|diamond/i, 'diamond', '钻石'], [/铁|iron/i, 'iron', '铁'],
        [/煤|coal/i, 'coal', '煤'], [/铜|copper/i, 'copper', '铜'],
        [/红石|redstone/i, 'redstone', '红石'], [/绿宝石|emerald/i, 'emerald', '绿宝石'],
    ];
    if (/(背包|包里|身上).*(有|没有|多少)|我有.*吗/i.test(input)) {
        for (const [pattern, key, label] of itemRules) {
            if (!pattern.test(input)) continue;
            const count = inventory
                .filter(entry => String(entry?.item || '').toLowerCase().includes(key))
                .reduce((sum, entry) => sum + Number(entry?.count || 0), 0);
            return `玩家主动询问背包中的${label}；服务器确认数量=${count}。本轮允许准确透露。`;
        }
    }
    const age = Number(state?.boredom?.observationAgeTicks ?? Number.POSITIVE_INFINITY);
    const type = String(state?.boredom?.observationType || '');
    const detail = String(state?.boredom?.observationDetail || '');
    if (age <= 20 * 60 && /(我刚才.*(?:没|没有|什么都没)|我没挖到|我没去过|刚才做了什么)/i.test(input)) {
        const facts = {
            'rare_block:diamond': '服务器确认玩家刚挖到钻石；玩家正在否认近期行为，本轮允许揭穿。',
            'rare_block:ancient_debris': '服务器确认玩家刚挖到远古残骸；玩家正在否认近期行为，本轮允许揭穿。',
            'rare_block:emerald': '服务器确认玩家刚挖到绿宝石；玩家正在否认近期行为，本轮允许揭穿。',
            'dimension:minecraft:the_nether': '服务器确认玩家刚进入过下界；玩家正在否认近期行为，本轮允许揭穿。',
            'dimension:minecraft:the_end': '服务器确认玩家刚进入过末地；玩家正在否认近期行为，本轮允许揭穿。',
        };
        return facts[`${type}:${detail}`] || '';
    }
    return '';
}

function buildDialogueState(state) {
    const boredom = Number(state?.boredom?.boredom || 0);
    return {
        synaPresent: state?.syna != null,
        synaVisibleToPlayer: state?.syna?.manifestation?.visibleToPlayer === true,
        horror: summarizeHorrorFacts(state),
        boredomBand: boredom >= 85 ? 'critical' : boredom >= 70 ? 'high' : boredom >= 40 ? 'medium' : 'low',
        storyChapter: Number(state?.story?.chapter || 0),
        storyScene: String(state?.story?.scene || ''),
    };
}

async function executeDecision(decision, player, locate) {
    if (decision.intent === 'locate_block') {
        if (!locate) return { accepted: false, completed: false, result: 'no_supported_resource_requested' };
        const scan = await requestJson(`${bridgeUrl}/scan/blocks?names=${encodeURIComponent(locate)}&player=${encodeURIComponent(player)}&radius=96&count=3`, {}, 5000).catch(() => null);
        return { accepted: true, completed: true, result: scan?.matches?.length ? 'locations_found' : 'no_loaded_location_found', matches: scan?.matches || [] };
    }
    return await requestJson(`${bridgeUrl}/intent`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ intent: decision.intent, args: decision.args, reason: decision.reason, player }),
    }, 4000);
}

async function handlePlayerChat(model, event) {
    const state = await requestJson(`${bridgeUrl}/state`).catch(() => null);
    const conversationGuidance = buildConversationGuidance(event.text, state);
    if (shouldResetConversationMemory(conversationGuidance)) {
        turns = [];
        dialogueMemory = createDialogueMemory();
        lastReceipt = null;
        lastGiftRequest = null;
    }
    const dialogueRuling = adjudicateDialogue({ text: event.text, state, memory: dialogueMemory });
    const identityLore = conversationGuidance?.kind === 'first_contact'
        ? null : adjudicateIdentityLore(event.text, state);
    dialogueMemory = dialogueRuling.nextMemory;
    const parsedGift = parseGiftRequest(event.text || '');
    const giftFollowUp = isGiftFollowUp(event.text || '')
        && lastGiftRequest && Date.now() - Number(lastGiftRequest.at || 0) <= 120000;
    const requestedGift = giftFollowUp
        ? { item: lastGiftRequest.item, count: lastGiftRequest.count, query: lastGiftRequest.query }
        : parsedGift;
    if (requestedGift) lastGiftRequest = { ...requestedGift, at: Date.now() };
    const locate = parseLocateRequest(event.text || '');
    const context = buildAuthoritativeContext(state, requestedGift, locate);
    const routed = deterministicDecision(event.text, state, requestedGift, locate, dialogueRuling, conversationGuidance);
    let decision = routed;
    if (!decision) {
        try {
            const intentMessages = [{
                role: 'user',
                content: `${event.player}: ${event.text}\nINTENT_INTERFACE=none, manifest, leave, give_item, locate_block, set_horror_stage, disable_horror_fx, enable_horror_fx, start_game, light_hit. prove_presence is reserved for the deterministic dialogue adjudicator and must never be selected by the model. give_item args={item, count}; set_horror_stage args={stage}; start_game args={kind}.\nAUTHORITATIVE_CONTEXT=${context}`,
            }];
            const rawDecision = await model.sendRequest(intentMessages, intentPrompt);
            decision = validateModelDecision(parseDecision(rawDecision), requestedGift);
        } catch (error) {
            decision = { intent: 'none', args: {}, reason: `intent_model_failed:${error?.message || error}` };
        }
    }
    if (decision.intent === 'locate_block' && !locate) decision.intent = 'none';

    let receipt;
    try {
        receipt = await executeDecision(decision, event.player, locate);
    } catch (error) {
        receipt = { accepted: false, completed: false, result: `execution_failed:${error?.message || error}` };
    }
    lastReceipt = { intent: decision.intent, accepted: receipt?.accepted === true, completed: receipt?.completed === true, result: receipt?.result || 'unknown' };
    const finalState = receipt?.state || await requestJson(`${bridgeUrl}/state`).catch(() => null);
    const actionContext = buildActionSpeechContext(decision, receipt, finalState);
    const disclosureFacts = buildDisclosureFacts(event.text, finalState) || '无；不要主动展示额外的全知信息。';
    const speechPermissions = {
        inventory: disclosureFacts.includes('主动询问背包'),
        recentObservation: disclosureFacts.includes('允许揭穿'),
        visibleManifest: actionContext?.receipt?.visibleToPlayer !== false,
    };
    const mustReply = !dialogueRuling.forceSilence
        && (conversationGuidance?.mustReply || dialogueRuling.mustReply
            || mustReplyToPlayer(event.text, actionContext != null));
    const horror = finalState?.syna?.horror?.stage && finalState.syna.horror.stage !== 'calm';
    const section = actionContext ? 'action_receipt_reply' : 'normal_reply';
    const speechBudget = selectSpeechBudget({
        text: event.text, dialogueRuling, identityLore, firstContact: finalState?.firstContact,
        conversationGuidance, horror, actionContext,
    });
    const speechPrompt = buildSystemPrompt(promptPack, section, { horror, limits: speechBudget });
    const speechContext = actionContext
        ? `PLAYER_TEXT=${JSON.stringify(String(event.text || '').slice(0, 240))}\nSCENE_GOAL=${actionContext.sceneGoal}\nACTION_RECEIPT=${JSON.stringify(actionContext.receipt)}\nHORROR_FACTS=${JSON.stringify(actionContext.horrorFacts)}\nDISCLOSURE_FACTS=${disclosureFacts}\nDIALOGUE_RULING=${JSON.stringify(dialogueRuling)}\nIDENTITY_LORE=${JSON.stringify(identityLore)}\nCONVERSATION_GUIDANCE=${JSON.stringify(conversationGuidance)}`
        : `PLAYER_TEXT=${JSON.stringify(String(event.text || '').slice(0, 240))}\nDISCLOSURE_FACTS=${disclosureFacts}\nDIALOGUE_STATE=${JSON.stringify(buildDialogueState(finalState))}\nDIALOGUE_RULING=${JSON.stringify(dialogueRuling)}\nIDENTITY_LORE=${JSON.stringify(identityLore)}\nCONVERSATION_GUIDANCE=${JSON.stringify(conversationGuidance)}`;
    let say = '';
    let rawSpeech = '';
    let filterReason = 'not_generated';
    let rewriteAttempted = false;
    try {
        if (dialogueRuling.forceSilence) {
            filterReason = receipt?.accepted === true
                ? 'dangerous_silence_scheduled'
                : `dangerous_silence_not_scheduled:${receipt?.result || 'unknown'}`;
        } else if (useFixedFirstContactSpeech(conversationGuidance)) {
            say = fallbackGuidedSpeech(conversationGuidance);
            filterReason = 'fixed_first_contact';
        } else {
            const recentTurns = turns.slice(-6);
            rawSpeech = await model.sendRequest([...recentTurns, { role: 'user', content: speechContext }], speechPrompt);
            let evaluated = evaluateSpeech(rawSpeech, promptPack.settings.fallback_reply, speechPermissions, speechBudget);
            say = evaluated.text;
            filterReason = evaluated.reason;
            if (say) {
                const adjudicated = validateAdjudicatedSpeech(say, dialogueRuling);
                if (!adjudicated.accepted) {
                    say = '';
                    filterReason = adjudicated.reason;
                }
            }
            if (say) {
                const loreCheck = validateIdentityLoreSpeech(say, identityLore);
                if (!loreCheck.accepted) {
                    say = '';
                    filterReason = loreCheck.reason;
                }
            }
            if (say) {
                const guidanceCheck = validateGuidedSpeech(say, conversationGuidance);
                if (!guidanceCheck.accepted) {
                    say = '';
                    filterReason = guidanceCheck.reason;
                }
            }
            if ((mustReply && !say) || (say && isRepeatedSpeech(say))) {
                rewriteAttempted = true;
                const recentLines = turns.filter(turn => turn.role === 'assistant').slice(-4).map(turn => turn.content);
                const rewriteReason = !say
                    ? `玩家正在直接等待回答，不能沉默。上次候选失败原因=${filterReason}。`
                    : '刚才的候选台词与近期台词重复。';
                const rewriteContext = `${speechContext}\nREWRITE_REASON=${rewriteReason}必须换一种内容和句式，不得复用：${JSON.stringify(recentLines)}`;
                rawSpeech = await model.sendRequest([{ role: 'user', content: rewriteContext }], speechPrompt);
                evaluated = evaluateSpeech(rawSpeech, promptPack.settings.fallback_reply, speechPermissions, speechBudget);
                say = evaluated.text;
                filterReason = evaluated.reason;
                if (say) {
                    const adjudicated = validateAdjudicatedSpeech(say, dialogueRuling);
                    if (!adjudicated.accepted) {
                        say = '';
                        filterReason = adjudicated.reason;
                    }
                }
                if (say) {
                    const loreCheck = validateIdentityLoreSpeech(say, identityLore);
                    if (!loreCheck.accepted) {
                        say = '';
                        filterReason = loreCheck.reason;
                    }
                }
                if (say) {
                    const guidanceCheck = validateGuidedSpeech(say, conversationGuidance);
                    if (!guidanceCheck.accepted) {
                        say = '';
                        filterReason = guidanceCheck.reason;
                    }
                }
                if (say && isRepeatedSpeech(say)) {
                    say = '';
                    filterReason = 'repeated_after_rewrite';
                }
            }
        }
    } catch (error) {
        filterReason = `speech_model_failed:${error?.message || error}`;
        console.warn('[PureModCore] speech model failed:', error?.message || error);
    }
    if (mustReply && !say) {
        say = conversationGuidance ? fallbackGuidedSpeech(conversationGuidance)
            : identityLore ? fallbackIdentityLore(identityLore) : fallbackForRuling(dialogueRuling);
        if (say) filterReason = `safe_fallback:${filterReason}`;
    }
    if (say) await sendCommand({ type: 'say', text: say });
    if (say) await speak(say, horror);
    if (say && identityLore?.recordDisclosure) {
        await executeDecision({
            intent: 'record_identity_disclosure',
            args: { topic: identityLore.topic, version: identityLore.version },
            reason: 'validated_identity_reply',
        }, event.player, null).catch(() => null);
    }
    if (say && conversationGuidance?.recordAfterSpeech) {
        await executeDecision({
            intent: 'record_first_contact', args: {}, reason: 'validated_first_contact_reply',
        }, event.player, null).catch(() => null);
    }
    audit('player_turn', {
        player: event.player,
        text: String(event.text || '').slice(0, 240),
        intent: decision.intent,
        receipt: actionContext?.receipt || lastReceipt,
        disclosure: disclosureFacts,
        rawSpeech: String(rawSpeech || '').slice(0, 500),
        filterReason,
        rewriteAttempted,
        mustReply,
        dialogueKind: dialogueRuling.kind,
        responseMode: dialogueRuling.responseMode,
        identityTopic: identityLore?.topic || '',
        identityVersion: identityLore?.version || 0,
        conversationKind: conversationGuidance?.kind || '',
        relationshipBand: conversationGuidance?.relationshipBand || '',
        speechBudget,
        spoken: say,
    });
    turns.push({ role: 'user', content: `${event.player}: ${event.text}` });
    if (say) turns.push({ role: 'assistant', content: say });
    turns = turns.slice(-24);
    saveMemory();
    console.log(`[PureModCore] ${event.player}: ${event.text} -> ${decision.intent} (${receipt?.result || 'unknown'})`);
}

async function handleProactiveState(model) {
    const now = Date.now();
    if (now - lastProactiveAt < 20000) return;
    lastProactiveAt = now;
    const state = await requestJson(`${bridgeUrl}/state`).catch(() => null);
    const opportunityId = Number(state?.boredom?.opportunityId || 0);
    if (opportunityId <= lastOpportunityId) return;
    if (state?.boredom?.opportunityAccepted !== true) return;
    const scene = String(state?.boredom?.opportunityScene || 'unknown');
    if (scene === 'watcher' || scene === 'stalker') {
        const activeTemplate = String(state?.horrorEvents?.active?.template || '');
        if (activeTemplate !== scene) return;
    } else if (scene !== 'brief_darkness') {
        audit('proactive_suppressed', { opportunityId, scene, reason: 'effect_not_client_confirmable' });
        return;
    }
    lastOpportunityId = opportunityId;
    const boredom = Number(state?.boredom?.boredom || 0);
    const sceneGoals = {
        observe: '让玩家隐约意识到自己正被留意，但不解释观察机制。',
        footsteps: '回应刚发生的脚步异常；可误导或捉弄玩家，也可以沉默。',
        watcher: '利用远处观察者已经出现这一事实制造被注视感，不提前泄露位置。',
        disappear: '针对玩家错过或看见消失瞬间作出反应，也可以让空白本身生效。',
        stalker: '让跟踪场景保持压力，不虚构攻击或伤害。',
        phantom_steps: '幽灵脚步已发生；多数情况下沉默更自然。',
        distant_knock: '远处敲击已发生；可试探玩家是否注意到。',
        cave_breath: '洞穴呼吸声已发生；避免直接解释来源。',
        brief_darkness: '短暂黑暗已结束；可轻描淡写地捉弄玩家。',
    };
    const sceneGoal = sceneGoals[scene];
    if (!sceneGoal) return;
    const horrorFacts = summarizeHorrorFacts(state);
    const prompt = buildSystemPrompt(promptPack, 'proactive_event_reply', { horror: true });
    const input = `SCENE_FACTS=${JSON.stringify({ opportunityId, scene, boredom, activeEvent: state?.horrorEvents?.active || null })}\nSCENE_GOAL=${sceneGoal}\nHORROR_FACTS=${JSON.stringify(horrorFacts)}`;
    let say = '';
    try {
        say = cleanSpeech(await model.sendRequest([...turns.slice(-4), { role: 'user', content: input }], prompt));
    } catch (error) {
        console.warn('[PureModCore] proactive speech failed:', error?.message || error);
    }
    if (!say) return;
    audit('proactive_spoken', { opportunityId, scene, spoken: say });
    await sendCommand({ type: 'say', text: say });
    await speak(say, boredom >= 70);
    turns.push({ role: 'assistant', content: `[主动] ${say}` });
    turns = turns.slice(-24);
    saveMemory();
    console.log(`[PureModCore] proactive opportunity=${opportunityId}:${scene}`);
}

async function handleObservationState(model) {
    const state = await requestJson(`${bridgeUrl}/state`).catch(() => null);
    const id = Number(state?.boredom?.observationId || 0);
    if (id <= lastObservationId) return;
    lastObservationId = id;
    const type = String(state?.boredom?.observationType || '');
    const detail = String(state?.boredom?.observationDetail || '');
    const key = `${type}:${detail}`;
    const facts = {
        'rare_block:diamond': '玩家刚发现或采掘了钻石。',
        'rare_block:ancient_debris': '玩家刚发现或采掘了远古残骸。',
        'rare_block:emerald': '玩家刚发现或采掘了绿宝石矿。',
        'dimension:minecraft:the_nether': '玩家刚进入下界。',
        'dimension:minecraft:the_end': '玩家刚进入末地。',
        'major_kill:entity.minecraft.ender_dragon': '玩家刚击杀末影龙。',
        'major_kill:entity.minecraft.wither': '玩家刚击杀凋灵。',
        'major_kill:entity.minecraft.warden': '玩家刚击杀监守者。',
    };
    if (!facts[key]) return;
    const prompt = buildSystemPrompt(promptPack, 'observation_reply', {
        horror: state?.syna?.horror?.stage && state.syna.horror.stage !== 'calm',
    });
    const input = `OBSERVATION_FACTS=${JSON.stringify({ id, type, detail, confirmedFact: facts[key] })}\nHORROR_FACTS=${JSON.stringify(summarizeHorrorFacts(state))}`;
    let say = '';
    try {
        say = cleanSpeech(
            await model.sendRequest([...turns.slice(-4), { role: 'user', content: input }], prompt),
            promptPack.settings.fallback_reply,
            { recentObservation: true },
        );
    } catch (error) {
        console.warn('[PureModCore] observation speech failed:', error?.message || error);
    }
    if (!say) return;
    audit('observation_spoken', { id, type, detail, spoken: say });
    await sendCommand({ type: 'say', text: say });
    await speak(say, false);
    saveMemory();
}

async function handleEpisodeHelp(model, event) {
    const facts = buildEpisodeHelpFacts(event);
    if (!facts) return;
    const state = await requestJson(`${bridgeUrl}/state`).catch(() => null);
    const horror = state?.syna?.horror?.stage && state.syna.horror.stage !== 'calm';
    const prompt = buildSystemPrompt(promptPack, 'episode_help_reply', { horror });
    const input = `EPISODE_HELP_FACTS=${JSON.stringify(facts)}\nHORROR_FACTS=${JSON.stringify(summarizeHorrorFacts(state))}`;
    let say = '';
    try {
        say = cleanSpeech(await model.sendRequest([...turns.slice(-4), { role: 'user', content: input }], prompt));
    } catch (error) {
        console.warn('[PureModCore] episode help speech failed:', error?.message || error);
    }
    audit('episode_help', { ...facts, spoken: say });
    if (!say) return;
    await sendCommand({ type: 'say', text: say });
    await speak(say, horror);
    turns.push({ role: 'assistant', content: `[赠礼] ${say}` });
    turns = turns.slice(-24);
    saveMemory();
}

function parseGiftRequest(text) {
    if (!/(\u7ed9\u6211|\u9001\u6211|\u62ff\u7ed9\u6211|give me)/i.test(text)) return null;
    const rules = [
        [/\u6728\u5934|\u539f\u6728|log/i, 'oak_log', 8], [/\u5706\u77f3|cobblestone/i, 'cobblestone', 8],
        [/\u706b\u628a|torch/i, 'torch', 8], [/\u9762\u5305|bread/i, 'bread', 2],
        [/\u725b\u6392|\u98df\u7269|food/i, 'cooked_beef', 3], [/\u94c1\u9550|iron pickaxe/i, 'iron_pickaxe', 1],
        [/\u94c1\u952d|\u5e16|iron ingot/i, 'iron_ingot', 4],
        [/\u9550|pickaxe/i, 'stone_pickaxe', 1], [/\u65a7|axe/i, 'stone_axe', 1], [/\u94f2|shovel/i, 'stone_shovel', 1],
    ];
    for (const [pattern, item, count] of rules) {
        if (pattern.test(text)) return { item, count, query: String(text).slice(0, 120) };
    }
    return { query: String(text).slice(0, 120) };
}

function isGiftFollowUp(text) {
    return /^(?:\u4f60)?(?:\u80fd|\u53ef\u4ee5)?(?:\u7ed9\u6211|\u9001\u6211)(?:\u4e00\u70b9|\u70b9|\u4e00\u4e9b)?(?:\u5417|\u561b|\u4e48)?[?\uff1f!\uff01\u3002]*$/i.test(String(text || '').trim())
        || /^give me some[?!.]*$/i.test(String(text || '').trim());
}

function parseLocateRequest(text) {
    if (!/(\u54ea\u91cc|\u9644\u8fd1|\u627e|where|nearby)/i.test(text)) return null;
    const rules = [
        [/\u94bb\u77f3|diamond/i, 'diamond_ore,deepslate_diamond_ore'],
        [/\u94c1\u77ff|iron/i, 'iron_ore,deepslate_iron_ore'],
        [/\u7164|coal/i, 'coal_ore,deepslate_coal_ore'],
        [/\u7ea2\u77f3|redstone/i, 'redstone_ore,deepslate_redstone_ore'],
    ];
    for (const [pattern, target] of rules) if (pattern.test(text)) return target;
    return null;
}

async function main() {
    const config = readModelConfig().active;
    const model = createModel({
        api: config.api,
        model: config.model,
        url: config.baseURL,
        params: config.params,
    });
    loadMemory();
    let startupFailures = 0;
    while (running) {
        try {
            const health = await requestJson(`${bridgeUrl}/health`, {}, 1500);
            if (!health?.ok) throw new Error('Syna Mod bridge is not ready.');
            if (!isSupportedBridgeHealth(health)) {
                throw new Error('Syna Mod protocol is outdated. Update the jar, then fully restart Minecraft.');
            }
            const initialEvents = await requestJson(`${bridgeUrl}/events?after=0`, {}, 1500);
            lastEventId = Number(initialEvents.latestId) || 0;
            bridgeOnline = true;
            console.log(`[PureModCore] connected to ${bridgeUrl}; Mineflayer is disabled.`);
            break;
        } catch (error) {
            if (/HTTP 404|protocol is outdated/i.test(String(error?.message || error))) {
                throw new Error('The running Syna Mod is outdated and does not provide /events. Update the jar, then fully restart Minecraft.');
            }
            bridgeOnline = false;
            startupFailures++;
            if (shouldStopAfterBridgeFailure(startupFailures)) {
                throw new Error(`Syna Mod bridge failed ${maxReconnectFailures} consecutive startup checks; stopping.`);
            }
            const now = Date.now();
            if (now - lastWaitingLogAt >= 30000) {
                console.warn('[PureModCore] waiting for a Minecraft world with Syna Mod...');
                lastWaitingLogAt = now;
            }
            await new Promise(resolve => setTimeout(resolve, 3000));
        }
    }

    while (running) {
        let delayMs = pollMs;
        try {
            const batch = await requestJson(`${bridgeUrl}/events?after=${lastEventId}`, {}, 1500);
            if (!bridgeOnline) console.log(`[PureModCore] reconnected to ${bridgeUrl}.`);
            bridgeOnline = true;
            pollFailureCount = 0;
            for (const event of batch.events || []) {
                if (event.type === 'player_chat') await handlePlayerChat(model, event);
                if (event.type === 'episode_help') await handleEpisodeHelp(model, event);
                lastEventId = Math.max(lastEventId, Number(event.id) || 0);
            }
            await handleProactiveState(model);
            await handleObservationState(model);
        } catch (error) {
            pollFailureCount++;
            if (bridgeOnline) {
                console.warn('[PureModCore] Syna Mod went offline; waiting for the world to return.');
            } else if (pollFailureCount % 12 === 0) {
                console.warn('[PureModCore] still waiting for Syna Mod...');
            }
            bridgeOnline = false;
            if (shouldStopAfterBridgeFailure(pollFailureCount)) {
                console.error(`[PureModCore] bridge failed ${maxReconnectFailures} consecutive reconnects; stopping.`);
                running = false;
                break;
            }
            delayMs = Math.min(10000, 500 * Math.pow(2, Math.min(5, pollFailureCount - 1)));
        }
        await new Promise(resolve => setTimeout(resolve, delayMs));
    }
}

process.on('SIGINT', () => { running = false; });
process.on('SIGTERM', () => { running = false; });

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
    main().catch(error => {
        console.error('[PureModCore] fatal:', error?.stack || error);
        process.exitCode = 1;
    });
}
