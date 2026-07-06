import settings from './settings.js';

let lastErrorAt = 0;

function shouldLogError() {
    const now = Date.now();
    if (now - lastErrorAt > 10000) {
        lastErrorAt = now;
        return true;
    }
    return false;
}

/**
 * Interrupt any currently playing/queued TTS on the syna voice server.
 * Call this when the user starts speaking (VAD trigger) to cut off the bot's voice.
 */
export async function interruptSynaVoice() {
    const cfg = settings.syna_voice || {};
    if (!cfg.enabled) return false;

    const baseUrl = (cfg.base_url || 'http://127.0.0.1:8766').replace(/\/$/, '');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 500);

    try {
        const resp = await fetch(`${baseUrl}/interrupt`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json; charset=utf-8'},
            body: '{}',
            signal: controller.signal
        });
        return resp.ok;
    } catch {
        return false;
    } finally {
        clearTimeout(timer);
    }
}

export async function sendSynaVoice(text, meta={}) {
    const cfg = settings.syna_voice || {};
    if (!cfg.enabled) return false;

    const clean = String(text || '').trim();
    if (!clean) return false;

    const baseUrl = (cfg.base_url || 'http://127.0.0.1:8766').replace(/\/$/, '');
    const timeoutMs = cfg.timeout_ms ?? 800;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
        const resp = await fetch(`${baseUrl}/say`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json; charset=utf-8'},
            body: JSON.stringify({
                text: clean,
                source: meta.source || 'mindcraft',
                agent: meta.agent || '',
                interrupt: Boolean(meta.interrupt),
                burstId: meta.burstId == null ? '' : String(meta.burstId),
                sequence: Number.isFinite(meta.sequence) ? meta.sequence : 0,
                ...(Number.isFinite(meta.speed) ? { speed: meta.speed } : {})
            }),
            signal: controller.signal
        });
        if (!resp.ok) {
            throw new Error(`HTTP ${resp.status}: ${await resp.text().catch(() => '')}`);
        }
        return true;
    } catch (err) {
        if (cfg.log_errors && shouldLogError()) {
            const message = err?.name === 'AbortError'
                ? `connection timeout ${timeoutMs}ms`
                : (err?.message || String(err));
            console.warn(`[SynaVoice] local voice service ${baseUrl} is not accepting /say (${message}). Mindcraft will keep running; use the launcher voice test to diagnose TTS.`);
        }
        return false;
    } finally {
        clearTimeout(timer);
    }
}


