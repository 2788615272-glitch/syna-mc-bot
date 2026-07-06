import settings from '../settings.js';
import { getModBlueprintClient } from './mod_blueprint_client.js';

let _pollInterval = null;

function parseAttackEvent(lastEvent, fallbackTarget = '') {
    if (!String(lastEvent || '').startsWith('syna_attacked:')) return null;
    const pieces = String(lastEvent).split(':');
    return {
        attacker: pieces[1] || fallbackTarget || 'unknown',
        amount: pieces[2] || '?',
        bodyKind: pieces[3] || 'mod_entity',
        bodyName: pieces[4] || 'Syna',
        cause: pieces[5] || 'unknown',
        sourceKind: pieces[6] || (pieces[1] && pieces[1] !== 'unknown' ? 'entity' : 'unknown'),
    };
}

function describeHorror(state) {
    const lastEvent = String(state?.lastEvent || '');
    const syna = state?.syna;
    const horror = syna?.horror || {};
    const attack = parseAttackEvent(lastEvent, String(horror.target || ''));

    if (!syna && !attack && !lastEvent.startsWith('horror_') && !lastEvent.startsWith('voice_focus:')) return null;

    const form = String(horror.form || (horror.stage === 'calm' ? 'normal' : 'horror'));
    const stage = String(horror.stage || 'calm');
    const anger = Number(horror.anger || 0);
    const target = String(horror.target || '');
    const targetKind = String(horror.targetKind || 'none');
    const seconds = Number(horror.countdownSeconds || 0);
    const awaitingConfession = Boolean(horror.awaitingConfession);
    const angerKey = String(horror.angerKey || "");
    const challengeKind = String(horror.challengeKind || 'none');
    const challengeTarget = String(horror.challengeTarget || '');
    const challengeClue = String(horror.challengeClue || '');
    const challengeProgress = Number(horror.challengeProgress || 0);
    const challengeRequired = Number(horror.challengeRequired || 0);
    const challengeSeconds = Number(horror.challengeSeconds || 0);
    const active = stage !== 'calm'
        || anger > 0
        || Boolean(attack)
        || lastEvent.startsWith('horror_')
        || lastEvent.startsWith('voice_focus:');
    if (!active) return null;

    const parts = [
        `[Syna second form state] form=${form}`,
        `stage=${stage}`,
        `anger=${anger}`,
        `target=${target || 'none'}`,
        `targetKind=${targetKind}`,
        `lastEvent=${lastEvent}`,
    ];
    if (seconds > 0) parts.push(`countdownSeconds=${seconds}`);
    if (awaitingConfession) parts.push(`You are waiting for the player to answer: "What did you do wrong?" AngerKey=${angerKey || "unset"}. If they guess wrong, call !synaHorror("guess", "", 0, "their answer") so the form keeps hunting; if they guess right, the mod will calm down.`);
    if (challengeKind !== 'none') {
        parts.push(`Active mercy challenge: kind=${challengeKind}, target=${challengeTarget || 'unset'}, clue=${challengeClue || 'unset'}, progress=${challengeProgress}/${challengeRequired}, secondsLeft=${challengeSeconds}. Do not forgive just because the player asks; wait for mod progress/completion or choose to stop with !synaHorror("forgive").`);
    } else if (stage === 'hunting' || awaitingConfession) {
        parts.push('You may offer a playable mercy challenge with !synaHorrorChallenge("block", "player", "minecraft:target_item", "short riddle", seconds, count) or !synaHorrorChallenge("kill", "player", "minecraft:zombie", "short task", seconds, count).');
    }
    if (attack) {
        if (attack.sourceKind === 'player') {
            parts.push(String(attack.attacker) + ' is attacking you right now. Damage=' + attack.amount + '. Cause=' + attack.cause + '. Body=' + attack.bodyKind + '/' + attack.bodyName + '. Treat this as direct evidence of who hit your body, but do not instantly summon Horror Syna from one or two weak hits. Build anger first with !synaHorror("key", "' + String(attack.attacker).replace(/"/g, '') + '", anger, "reason") or warn them; only use !synaHorrorTakeover after repeated deliberate attacks, heavy damage, or a clear roleplay escalation.');
        } else if (attack.sourceKind === 'mob') {
            parts.push('You were damaged by a non-player entity, probably ' + String(attack.attacker || attack.cause) + '. Damage=' + attack.amount + '. Cause=' + attack.cause + '. Do not blame or target the player from this event.');
        } else {
            parts.push('You were damaged by the environment. Damage=' + attack.amount + '. Cause=' + attack.cause + '. Body=' + attack.bodyKind + '/' + attack.bodyName + '. Do not blame or target the player from this event, even if a player is nearby.');
        }
    }
    if (lastEvent.startsWith('voice_focus:')) {
        parts.push('The player pressed the Syna voice-focus key. Treat their next voice or chat message as intentionally addressed to you.');
    }
    parts.push('Horror Syna is your second form, not a separate character. If you have set an anger key, do not forgive until the player names the real reason with !synaHorror("guess", "", 0, "answer"). You can also design one concrete block/item or kill-count mercy challenge and let the mod enforce it. If no key/challenge is active and the player begs, apologizes, dies, or you decide to stop, use !synaHorror("forgive"). You may build anger with !synaHorror("key", "player", anger, "reason") before transforming. If attacked, remember the target is whoever actually attacked your body, not automatically the main player.');
    return parts.join(' | ');
}

export async function attachHorrorAwareness(agent) {
    if (_pollInterval) clearInterval(_pollInterval);
    let lastSignature = '';
    let lastInjectedAt = 0;
    let lastAttackHandled = '';

    async function poll() {
        try {
            const state = await getModBlueprintClient().state();
            const msg = describeHorror(state);
            if (!msg) return;
            const horror = state?.syna?.horror || {};
            const lastEvent = String(state?.lastEvent || '');
            const signature = `${lastEvent}|${horror.form || ''}|${horror.stage || ''}|${horror.anger || 0}|${horror.target || ''}|${horror.countdownTicks || 0}|${horror.challengeKind || ''}|${horror.challengeProgress || 0}|${horror.challengeTicks || 0}`;
            const now = Date.now();
            if (signature !== lastSignature || now - lastInjectedAt >= 30000) {
                lastSignature = signature;
                lastInjectedAt = now;
                await agent.history.add('system', msg);
                agent._lastHorrorSummary = msg;
                if (settings.horror_awareness?.log) console.log('[HorrorAwareness]', msg);
            }

            if (lastEvent.startsWith('syna_attacked:') && lastEvent !== lastAttackHandled) {
                lastAttackHandled = lastEvent;
                if (settings.horror_awareness?.log) console.log('[HorrorAwareness] handling attack:', lastEvent);
                setTimeout(() => {
                    agent.handleMessage('system', `[ATTACK EVENT] ${msg}`).catch(() => {});
                }, 0);
            }
        } catch (_) {
            // Mod bridge is optional; stay silent when offline.
        }
    }

    setTimeout(poll, 1000);
    _pollInterval = setInterval(poll, 1000);
}

export function detachHorrorAwareness() {
    if (_pollInterval) {
        clearInterval(_pollInterval);
        _pollInterval = null;
    }
}