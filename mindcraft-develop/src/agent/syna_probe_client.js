import settings from './settings.js';

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function normalizeBaseUrl(url) {
    return String(url || 'http://127.0.0.1:8765').replace(/\/+$/, '');
}

export class SynaProbeClient {
    constructor(options = {}) {
        const probeSettings = settings.syna_probe || {};
        this.enabled = options.enabled ?? probeSettings.enabled ?? false;
        this.baseUrl = normalizeBaseUrl(options.base_url ?? probeSettings.base_url);
        this.timeoutMs = options.timeout_ms ?? probeSettings.timeout_ms ?? 500;
        this.lastError = null;
        this.lastHealth = null;
        this.lastState = null;
    }

    async requestJson(path) {
        if (!this.enabled) {
            return null;
        }

        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), this.timeoutMs);
        try {
            const res = await fetch(`${this.baseUrl}${path}`, {
                method: 'GET',
                signal: controller.signal,
                headers: { 'Accept': 'application/json' }
            });
            if (!res.ok) {
                throw new Error(`HTTP ${res.status}`);
            }
            const data = await res.json();
            this.lastError = null;
            return data;
        } catch (err) {
            this.lastError = err?.message || String(err);
            return null;
        } finally {
            clearTimeout(timer);
        }
    }

    async health() {
        this.lastHealth = await this.requestJson('/health');
        return this.lastHealth;
    }

    async getState() {
        this.lastState = await this.requestJson('/state');
        return this.lastState;
    }

    isAvailable() {
        return this.enabled && !this.lastError;
    }

    getBoundPlayer(state = this.lastState) {
        return state?.boundPlayer || null;
    }

    getSynaEntity(state = this.lastState) {
        return state?.syna || null;
    }

    async waitUntilState(predicate, timeoutMs = 1200, intervalMs = 100) {
        const start = Date.now();
        while (Date.now() - start <= timeoutMs) {
            const state = await this.getState();
            if (state && predicate(state)) {
                return state;
            }
            await sleep(intervalMs);
        }
        return null;
    }

    async waitUntilStableOnGround(timeoutMs = 1200) {
        // Current syna_mod /state does not expose onGround yet. This method is a safe placeholder:
        // it succeeds when the probe is reachable, and can become stricter once the mod adds onGround/velocity.
        return await this.waitUntilState(state => Boolean(state?.ok), timeoutMs);
    }
}

export function createSynaProbeClient(options = {}) {
    return new SynaProbeClient(options);
}