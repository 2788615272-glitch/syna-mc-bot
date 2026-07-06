import settings from '../settings.js';
import taskBoard from '../task_board.js';
import { getModBlueprintClient } from './mod_blueprint_client.js';

let _timer = null;

function now() {
    return Date.now();
}

function nearbyPlayers(agent, radius) {
    const bot = agent?.bot;
    const self = bot?.entity;
    if (!bot || !self?.position) return [];
    const names = [];
    for (const [name, player] of Object.entries(bot.players || {})) {
        if (!name || name === agent.name) continue;
        const entity = player?.entity;
        if (!entity?.position) continue;
        const distance = self.position.distanceTo(entity.position);
        if (Number.isFinite(distance) && distance <= radius) {
            names.push({ name, distance });
        }
    }
    return names.sort((a, b) => a.distance - b.distance);
}

function timeLabel(bot) {
    const t = bot?.time?.timeOfDay;
    if (!Number.isFinite(t)) return 'unknown';
    if (t < 1000) return 'sunrise';
    if (t < 6000) return 'morning';
    if (t < 12000) return 'afternoon';
    if (t < 13000) return 'sunset';
    return 'night';
}

function shouldSkip(agent, cfg, state) {
    const current = now();
    if (!agent?.bot?.entity) return 'no_bot';
    if (!agent.isIdle?.()) return 'busy';
    if (agent.actions?.currentActionLabel) return 'action_running';
    if (agent.self_prompter?.isActive?.()) return 'self_prompting';
    try {
        if (taskBoard.list().some(t => t?.kind === 'mc_run' && !t?.done)) return 'mc_run_pending';
    } catch (_) {}
    if (agent._streamingTTS) return 'speaking';
    if (current < (agent._voiceHoldUntil || 0)) return 'voice_hold';
    if (current - (state.lastUserAt || 0) < cfg.quiet_after_user_ms) return 'recent_user';
    if (current - (state.lastProactiveAt || 0) < cfg.min_gap_ms) return 'cooldown';
    return '';
}

async function getHorrorSummary() {
    try {
        const state = await getModBlueprintClient().state();
        const horror = state?.syna?.horror;
        if (!horror) return '';
        const stage = String(horror.stage || 'calm');
        const anger = Number(horror.anger || 0);
        const challengeKind = String(horror.challengeKind || 'none');
        const parts = [`horrorStage=${stage}`, `anger=${anger}`];
        if (horror.target) parts.push(`target=${horror.target}`);
        if (challengeKind !== 'none') {
            parts.push(`challenge=${challengeKind}:${horror.challengeTarget || 'unset'}:${horror.challengeProgress || 0}/${horror.challengeRequired || 0}:${horror.challengeSeconds || 0}s`);
        }
        return parts.join(', ');
    } catch (_) {
        return '';
    }
}

function buildPrompt(agent, cfg, state, horrorSummary) {
    const bot = agent.bot;
    const pos = bot.entity?.position;
    const players = nearbyPlayers(agent, cfg.player_radius).slice(0, 4);
    const playerText = players.length
        ? players.map(p => `${p.name}(${p.distance.toFixed(1)}m)`).join(', ')
        : 'none nearby';
    const task = agent.actions?.currentActionLabel || 'idle';
    const time = timeLabel(bot);
    const health = Number.isFinite(bot.health) ? Math.round(bot.health) : '?';
    const food = Number.isFinite(bot.food) ? Math.round(bot.food) : '?';
    const position = pos ? `${pos.x.toFixed(1)},${pos.y.toFixed(1)},${pos.z.toFixed(1)}` : 'unknown';
    const moodSeed = state.tickCount % 5;
    const angle = [
        'notice one small environmental detail and decide whether it matters',
        'quietly maintain your sense of self and story continuity',
        'consider a tiny useful action, not a big project',
        'if Horror Syna is relevant, seed a clue or restraint rather than instantly escalating',
        'if nothing matters, deliberately stay quiet'
    ][moodSeed];

    return [
        '[Syna主动性触发]',
        `状态：task=${task}, pos=${position}, time=${time}, hp=${health}/20, food=${food}/20, players=${playerText}${horrorSummary ? ', ' + horrorSummary : ''}.`,
        `这次主动性角度：${angle}.`,
        '你可以选择：',
        '1. 完全不打扰玩家：只调用 !synaWait("reason")。',
        '2. 心里想一下：用 [THINK] 开头，必须很短。',
        '3. 真的要让附近玩家听见：用 [SAY] 开头，最多一句短话。',
        '4. 有明确小动作才用命令；不要为了主动而开大任务。',
        '不要重复寒暄，不要连续解释设定，不要每次都说话。Respond with at most one short sentence or one command.'
    ].join('\n');
}

export function attachSynaProactivity(agent) {
    const cfg = {
        enabled: true,
        interval_ms: 15000,
        min_gap_ms: 90000,
        quiet_after_user_ms: 45000,
        player_radius: 32,
        chance: 0.55,
        ...(settings.syna_proactivity || {})
    };
    if (!cfg.enabled) return;
    if (_timer) clearInterval(_timer);

    const state = {
        lastUserAt: now(),
        lastProactiveAt: 0,
        tickCount: 0
    };

    const noteUser = (source) => {
        if (source && source !== 'system' && source !== agent.name) {
            state.lastUserAt = now();
        }
    };
    agent._noteSynaProactivityInput = noteUser;

    _timer = setInterval(async () => {
        try {
            state.tickCount++;
            const skip = shouldSkip(agent, cfg, state);
            if (skip) return;
            if (Math.random() > cfg.chance) {
                state.lastProactiveAt = now() - Math.floor(cfg.min_gap_ms * 0.55);
                return;
            }
            const horrorSummary = await getHorrorSummary();
            const prompt = buildPrompt(agent, cfg, state, horrorSummary);
            state.lastProactiveAt = now();
            agent._lastProactivePromptAt = state.lastProactiveAt;
            await agent.handleMessage('system', prompt, 1);
        } catch (error) {
            if (settings.syna_proactivity?.log) {
                console.warn('[SynaProactivity] tick failed:', error?.message || error);
            }
        }
    }, Math.max(5000, Number(cfg.interval_ms) || 15000));

    if (settings.syna_proactivity?.log) {
        console.log(`[SynaProactivity] attached (interval=${cfg.interval_ms}ms, min_gap=${cfg.min_gap_ms}ms)`);
    }
}

export function detachSynaProactivity() {
    if (_timer) {
        clearInterval(_timer);
        _timer = null;
    }
}
