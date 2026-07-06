/**
 * Mod Blueprint Client
 *
 * Thin HTTP client for the SynaBridge mod's /blueprint REST API.
 * Lets the JS-side bot upload a snapshot (or an edit plan derived from one)
 * to the server-side BlueprintRegistry, then poll for the next block to
 * place and report progress.
 *
 * The mod is the source of truth for "what does the building look like":
 *   - It refuses to let the bot break blueprint blocks.
 *   - It hands out the next missing cell on demand (so the LLM never has
 *     to remember which blocks are done).
 *
 * Endpoints (see BridgeHttpServer.BlueprintHandler):
 *   POST   /blueprint/upload
 *   GET    /blueprint/list
 *   GET    /blueprint/{id}/status
 *   GET    /blueprint/{id}/next?fx=&fy=&fz=
 *   POST   /blueprint/{id}/mode
 *   DELETE /blueprint/{id}
 */

import { appendFileSync, mkdirSync } from 'fs';
import settings from '../settings.js';
import { bpLog } from './bp_log.js';

function normalizeBaseUrl(url) {
    return String(url || 'http://127.0.0.1:8765').replace(/\/+$/, '');
}

function logModClientError(event) {
    try {
        mkdirSync('./logs', { recursive: true });
        appendFileSync('./logs/mod_client_errors.jsonl', JSON.stringify({
            time: new Date().toISOString(),
            ...event,
        }) + '\n', 'utf8');
    } catch (err) {
        console.warn('[ModBlueprintClient] failed to write error log:', err?.message || err);
    }
}

export class ModBlueprintClient {
    constructor(options = {}) {
        // Defer reading settings until first request; settings.js is an
        // empty obj at import time and gets populated at runtime.
        this.options = options;
        this.lastError = null;
    }

    _resolveConfig() {
        const probeSettings = settings.syna_probe || {};
        const o = this.options || {};
        return {
            enabled: o.enabled ?? probeSettings.enabled ?? true,
            baseUrl: normalizeBaseUrl(o.base_url ?? probeSettings.base_url),
            timeoutMs: Math.max(3000, Number(o.timeout_ms ?? probeSettings.timeout_ms ?? 5000) || 5000),
        };
    }

    async _request(path, { method = 'GET', body = null } = {}) {
        const cfg = this._resolveConfig();
        if (!cfg.enabled) {
            this.lastError = 'mod_client_disabled';
            bpLog(`_request BLOCKED (disabled) path=${path}`);
            logModClientError({ kind: 'disabled', method, path, body, error: this.lastError });
            return null;
        }

        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), cfg.timeoutMs);
        const url = `${cfg.baseUrl}${path}`;
        const requestInfo = { method, path, url, body, timeoutMs: cfg.timeoutMs };
        bpLog(`>>> ${method} ${url}${body ? ' body=' + JSON.stringify(body).slice(0, 150) : ''}`);
        try {
            const init = {
                method,
                signal: controller.signal,
                headers: { 'Accept': 'application/json' },
            };
            if (body != null) {
                init.headers['Content-Type'] = 'application/json';
                init.body = typeof body === 'string' ? body : JSON.stringify(body);
            }
            const res = await fetch(url, init);
            const text = await res.text();
            let data = null;
            let parseError = null;
            try {
                data = text ? JSON.parse(text) : null;
            } catch (err) {
                parseError = err?.message || String(err);
                data = { ok: false, error: 'invalid_json', body: text };
            }
            bpLog(`<<< ${method} ${path} status=${res.status} data=${JSON.stringify(data).slice(0, 200)}`);

            if (parseError) {
                this.lastError = `invalid_json: ${parseError}`;
                logModClientError({
                    kind: 'invalid_json',
                    ...requestInfo,
                    status: res.status,
                    responseText: text?.slice(0, 4000),
                    error: parseError,
                });
                return data;
            }

            if (!res.ok || data?.ok === false) {
                const error = data?.error || text?.slice(0, 200) || `HTTP ${res.status}`;
                this.lastError = `HTTP ${res.status}: ${error}`;
                logModClientError({
                    kind: 'http_error',
                    ...requestInfo,
                    status: res.status,
                    response: data,
                    responseText: text?.slice(0, 4000),
                    error,
                });
                return data;
            }

            this.lastError = null;
            return data;
        } catch (err) {
            this.lastError = err?.message || String(err);
            bpLog(`!!! ${method} ${path} ERROR: ${this.lastError}`);
            logModClientError({
                kind: 'exception',
                ...requestInfo,
                error: this.lastError,
                stack: err?.stack,
            });
            return null;
        } finally {
            clearTimeout(timer);
        }
    }

    /**
     * Convert a snapshot from area_scanner.scanArea into a /blueprint/upload
     * payload. We expand layers + palette into an explicit cell list because
     * that's what the mod-side registry stores.
     *
     * If `editPlan` is provided (from buildEditPlan), we override snapshot
     * cells with the edit plan: only the placements survive, removals turn
     * into "air" cells, etc.; useful when the LLM is sculpting the building
     * before locking it in as a blueprint.
     */
    snapshotToUploadPayload(id, snapshot, { editPlan = null, mode = 'build', autoClear = false, includeAir = false } = {}) {
        if (!snapshot) throw new Error('snapshot required');
        const { origin, palette, layers } = snapshot;
        const blocks = [];

        if (editPlan && (editPlan.placements?.length || editPlan.removals?.length)) {
            // edit-plan path: world-coord placements only
            for (const p of editPlan.placements) {
                blocks.push({
                    dx: p.x - origin.x,
                    dy: p.y - origin.y,
                    dz: p.z - origin.z,
                    name: p.block,
                });
            }
            if (includeAir) {
                for (const r of editPlan.removals) {
                    blocks.push({
                        dx: r.x - origin.x,
                        dy: r.y - origin.y,
                        dz: r.z - origin.z,
                        name: 'air',
                    });
                }
            }
        } else {
            // raw snapshot path: every non-air cell becomes a blueprint cell
            for (const layer of layers) {
                const dy = layer.y;
                for (let dz = 0; dz < layer.grid.length; dz++) {
                    const row = layer.grid[dz];
                    for (let dx = 0; dx < row.length; dx++) {
                        const sym = row[dx];
                        const name = palette[sym] || 'air';
                        if (!includeAir && (name === 'air' || sym === '.')) continue;
                        blocks.push({ dx, dy, dz, name });
                    }
                }
            }
        }

        return {
            id,
            ox: origin.x,
            oy: origin.y,
            oz: origin.z,
            mode,
            auto_clear: autoClear,
            blocks,
        };
    }

    async uploadSnapshot(id, snapshot, opts = {}) {
        const payload = this.snapshotToUploadPayload(id, snapshot, opts);
        return await this._request('/blueprint/upload', { method: 'POST', body: payload });
    }

    async uploadRaw(payload) {
        const blockCount = payload?.blocks?.length || 0;
        // Guard: reject invalid origin coordinates before uploading.
        if (!Number.isFinite(payload?.ox) || !Number.isFinite(payload?.oy) || !Number.isFinite(payload?.oz)) {
            const msg = "uploadRaw: invalid origin! ox=" + payload?.ox + ", oy=" + payload?.oy + ", oz=" + payload?.oz + ". Refusing upload.";
            bpLog(msg);
            this.lastError = msg;
            return { ok: false, error: msg };
        }
        bpLog(`uploadRaw: id=${payload?.id}, origin=(${payload?.ox},${payload?.oy},${payload?.oz}), blocks=${blockCount}, mode=${payload?.mode}`);
        const result = await this._request('/blueprint/upload', { method: 'POST', body: payload });
        bpLog(`uploadRaw result: ${JSON.stringify(result)?.slice(0, 300)}`);
        if (!result) bpLog(`uploadRaw FAILED - lastError: ${this.lastError}`);
        return result;
    }

    async state() {
        return await this._request('/state');
    }

    async list() {
        return await this._request('/blueprint/list');
    }

    async status(id) {
        return await this._request(`/blueprint/${encodeURIComponent(id)}/status`);
    }

    async next(id, fromPos = null) {
        let qs = '';
        if (fromPos && Number.isFinite(fromPos.x) && Number.isFinite(fromPos.y) && Number.isFinite(fromPos.z)) {
            qs = `?fx=${Math.floor(fromPos.x)}&fy=${Math.floor(fromPos.y)}&fz=${Math.floor(fromPos.z)}`;
        }
        const result = await this._request(`/blueprint/${encodeURIComponent(id || 'any')}/next${qs}`);
        bpLog(`next(${id}): result=${JSON.stringify(result)?.slice(0, 200)}`);
        if (!result) bpLog(`next FAILED - lastError: ${this.lastError}`);
        return result;
    }

    async setMode(id, mode) {
        return await this._request(`/blueprint/${encodeURIComponent(id)}/mode`,
            { method: 'POST', body: { mode } });
    }

    /**
     * Skip (mark as placed) a specific cell so getNextMissing won't return it again.
     * Used when a block is in the blacklist or cannot be placed by the bot.
     */
    async skip(id, x, y, z, name) {
        return await this._request(`/blueprint/${encodeURIComponent(id)}/skip`, {
            method: 'POST',
            body: { x: Math.floor(x), y: Math.floor(y), z: Math.floor(z), name: name || 'air' }
        });
    }

    async remove(id) {
        return await this._request(`/blueprint/${encodeURIComponent(id)}`, { method: 'DELETE' });
    }
    async scanBlocks(name, { radius = 96, count = 16, fromPos = null } = {}) {
        const params = new URLSearchParams();
        params.set('name', name);
        params.set('radius', String(radius));
        params.set('count', String(count));
        if (fromPos && Number.isFinite(fromPos.x) && Number.isFinite(fromPos.y) && Number.isFinite(fromPos.z)) {
            params.set('x', String(Math.floor(fromPos.x)));
            params.set('y', String(Math.floor(fromPos.y)));
            params.set('z', String(Math.floor(fromPos.z)));
        }
        return await this._request(`/scan/blocks?${params.toString()}`);
    }

    async sendCommand(type, body = {}) {
        return await this._request('/command', {
            method: 'POST',
            body: { type, ...body }
        });
    }

    async horror(action = 'status', player = '', { ensureSpawnAt = null, anger = null, ownerName = '', reason = '', item = '', count = null, seconds = null } = {}) {
        const normalized = String(action || 'status').trim().toLowerCase();
        const needsBody = ['warn', 'countdown', 'hunt', 'chase', 'takeover', 'transform', 'possess', 'anger', 'set_anger', 'rage', 'challenge_block', 'challenge_kill'].includes(normalized);
        if (needsBody && ensureSpawnAt) {
            const state = await this.state();
            if (state && !state.syna) {
                await this.spawnSynaAt(ensureSpawnAt);
                for (let i = 0; i < 5; i++) {
                    await new Promise(resolve => setTimeout(resolve, 100));
                    const spawnedState = await this.state();
                    if (spawnedState?.syna) break;
                }
            }
        }
        const body = { text: action, player };
        if (item) body.item = item;
        if (ownerName) body.owner = ownerName;
        if (reason) body.reason = reason;
        if (Number.isFinite(Number(anger))) body.count = Math.max(0, Math.min(240, Math.floor(Number(anger))));
        else if (Number.isFinite(Number(count))) body.count = Math.max(1, Math.floor(Number(count)));
        if (Number.isFinite(Number(seconds))) body.seconds = Math.max(5, Math.min(600, Math.floor(Number(seconds))));
        return await this.sendCommand('horror', body);
    }

    async spawnSynaAt(pos) {
        return await this.sendCommand('spawn_syna_at', {
            x: pos?.x,
            y: pos?.y,
            z: pos?.z
        });
    }

    async scanEntities(name = '', { radius = 96, count = 16, fromPos = null } = {}) {
        const params = new URLSearchParams();
        if (name) params.set('name', name);
        params.set('radius', String(radius));
        params.set('count', String(count));
        if (fromPos && Number.isFinite(fromPos.x) && Number.isFinite(fromPos.y) && Number.isFinite(fromPos.z)) {
            params.set('x', String(Math.floor(fromPos.x)));
            params.set('y', String(Math.floor(fromPos.y)));
            params.set('z', String(Math.floor(fromPos.z)));
        }
        return await this._request(`/scan/entities?${params.toString()}`);
    }
}

let _shared = null;
export function getModBlueprintClient() {
    if (!_shared) _shared = new ModBlueprintClient();
    return _shared;
}
