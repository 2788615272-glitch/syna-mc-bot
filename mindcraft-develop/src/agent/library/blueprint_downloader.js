/**
 * blueprint_downloader.js
 * 
 * Provides online blueprint search, download, and random selection.
 * Uses a pre-built index (online_index.json) to find schematics by keyword,
 * then downloads them to the local blueprints directory.
 * 
 * Features:
 * - Local-first: checks if file already exists before downloading
 * - 4-minute timeout per download
 * - HTTP proxy support (default 127.0.0.1:10090, or env HTTP_PROXY/HTTPS_PROXY)
 * - Graceful fallback on failure
 */

import fs from 'fs';
import path from 'path';
import https from 'https';
import http from 'http';
import { getLibraryDir } from './schematic_importer.js';

const INDEX_PATH = path.resolve('./src/agent/library/blueprints/online_index.json');

// Download timeout: 4 minutes
const DOWNLOAD_TIMEOUT_MS = 4 * 60 * 1000;

// Default proxy (your local proxy)
const DEFAULT_PROXY = '127.0.0.1:10090';

let _indexCache = null;

/**
 * Get proxy configuration. Priority:
 * 1. Environment variable HTTPS_PROXY or HTTP_PROXY
 * 2. Default proxy (127.0.0.1:10090)
 * Returns {host, port} or null if proxy is explicitly disabled (set env to "none")
 */
function getProxyConfig() {
    const envProxy = process.env.HTTPS_PROXY || process.env.HTTP_PROXY || 
                     process.env.https_proxy || process.env.http_proxy;
    
    if (envProxy === 'none' || envProxy === 'off' || envProxy === 'false') {
        return null; // Explicitly disabled
    }
    
    if (envProxy) {
        try {
            // Parse "http://host:port" or "host:port"
            const cleaned = envProxy.replace(/^https?:\/\//, '');
            const [host, portStr] = cleaned.split(':');
            return { host: host || '127.0.0.1', port: parseInt(portStr) || 10090 };
        } catch (e) {
            // Fall through to default
        }
    }
    
    // Default proxy
    const [host, portStr] = DEFAULT_PROXY.split(':');
    return { host, port: parseInt(portStr) };
}

/**
 * Load the online index (cached after first load).
 */
function loadIndex() {
    if (_indexCache) return _indexCache;
    if (!fs.existsSync(INDEX_PATH)) {
        throw new Error('Online index not found at: ' + INDEX_PATH);
    }
    const raw = fs.readFileSync(INDEX_PATH, 'utf-8');
    _indexCache = JSON.parse(raw);
    return _indexCache;
}

/**
 * Reload index from disk (useful after manual edits).
 */
export function reloadIndex() {
    _indexCache = null;
    return loadIndex();
}

/**
 * Search the online index by keyword. Matches against name, tags, and description.
 * @param {string} keyword - Search term
 * @returns {Array} Matching entries sorted by relevance
 */
export function searchOnlineIndex(keyword) {
    const index = loadIndex();
    const kw = keyword.toLowerCase().trim();
    const words = kw.split(/\s+/);

    const scored = index.sources.map(entry => {
        let score = 0;
        const nameL = entry.name.toLowerCase();
        const descL = entry.description.toLowerCase();
        const tagsL = entry.tags.map(t => t.toLowerCase());

        for (const w of words) {
            if (nameL === w) score += 10;
            else if (nameL.includes(w)) score += 5;
            if (tagsL.includes(w)) score += 4;
            else if (tagsL.some(t => t.includes(w))) score += 2;
            if (descL.includes(w)) score += 1;
        }
        return { ...entry, score };
    });

    return scored.filter(e => e.score > 0).sort((a, b) => b.score - a.score);
}

/**
 * Get a random entry from the online index.
 * @param {string} [sizeFilter] - Optional: 'tiny', 'small', 'medium', 'large'
 * @returns {object} A random index entry
 */
export function getRandomEntry(sizeFilter) {
    const index = loadIndex();
    let pool = index.sources;
    if (sizeFilter) {
        pool = pool.filter(e => e.size_estimate === sizeFilter);
    }
    if (pool.length === 0) {
        pool = index.sources;
    }
    return pool[Math.floor(Math.random() * pool.length)];
}

/**
 * Download a file from URL to the blueprints directory.
 * - Checks local first (skips if exists)
 * - 4-minute timeout
 * - Uses HTTP proxy if available
 * 
 * @param {string} url - The download URL
 * @param {string} filename - Target filename (e.g. "medieval_house.schem")
 * @returns {Promise<string>} The local file path
 */
export function downloadFile(url, filename) {
    return new Promise((resolve, reject) => {
        const dir = getLibraryDir();
        const filePath = path.join(dir, filename);

        // Local-first: if already downloaded, skip
        if (fs.existsSync(filePath)) {
            const stat = fs.statSync(filePath);
            if (stat.size > 0) {
                resolve(filePath);
                return;
            }
            // File exists but is empty (failed download), delete and retry
            fs.unlinkSync(filePath);
        }

        const proxy = getProxyConfig();
        const isHttps = url.startsWith('https');
        
        // Timeout timer
        let timeoutId = null;
        let finished = false;

        const finish = (err, result) => {
            if (finished) return;
            finished = true;
            if (timeoutId) clearTimeout(timeoutId);
            if (err) reject(err);
            else resolve(result);
        };

        timeoutId = setTimeout(() => {
            finish(new Error(`Download timed out after 4 minutes: ${filename}`));
        }, DOWNLOAD_TIMEOUT_MS);

        const doRequest = (reqUrl, redirectCount = 0) => {
            if (redirectCount > 5) {
                finish(new Error('Too many redirects'));
                return;
            }

            let requestOptions;
            let transport;

            if (proxy) {
                // Use CONNECT tunnel through proxy for HTTPS, or direct proxy for HTTP
                if (isHttps) {
                    // For HTTPS through HTTP proxy, we use http.request with CONNECT method
                    // Simpler approach: use http proxy with full URL
                    const parsed = new URL(reqUrl);
                    requestOptions = {
                        host: proxy.host,
                        port: proxy.port,
                        path: reqUrl,
                        method: 'GET',
                        headers: {
                            'Host': parsed.host,
                            'User-Agent': 'Mindcraft-Blueprint-Downloader/1.0'
                        }
                    };
                    transport = http;
                } else {
                    const parsed = new URL(reqUrl);
                    requestOptions = {
                        host: proxy.host,
                        port: proxy.port,
                        path: reqUrl,
                        method: 'GET',
                        headers: {
                            'Host': parsed.host,
                            'User-Agent': 'Mindcraft-Blueprint-Downloader/1.0'
                        }
                    };
                    transport = http;
                }
            } else {
                // Direct connection (no proxy)
                const parsed = new URL(reqUrl);
                requestOptions = {
                    hostname: parsed.hostname,
                    port: parsed.port || (isHttps ? 443 : 80),
                    path: parsed.pathname + parsed.search,
                    method: 'GET',
                    headers: {
                        'User-Agent': 'Mindcraft-Blueprint-Downloader/1.0'
                    }
                };
                transport = isHttps ? https : http;
            }

            const req = transport.request(requestOptions, (res) => {
                // Handle redirects
                if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                    doRequest(res.headers.location, redirectCount + 1);
                    return;
                }
                if (res.statusCode !== 200) {
                    finish(new Error(`Download failed: HTTP ${res.statusCode} for ${reqUrl}`));
                    return;
                }
                const fileStream = fs.createWriteStream(filePath);
                res.pipe(fileStream);
                fileStream.on('finish', () => {
                    fileStream.close();
                    finish(null, filePath);
                });
                fileStream.on('error', (err) => {
                    fs.unlink(filePath, () => {});
                    finish(err);
                });
            });

            req.on('error', (err) => {
                finish(new Error(`Network error (proxy=${proxy ? proxy.host + ':' + proxy.port : 'none'}): ${err.message}`));
            });

            req.end();
        };

        doRequest(url);
    });
}

/**
 * Search online index and download the best match.
 * @param {string} keyword - What to search for
 * @returns {Promise<{entry: object, localPath: string}>}
 */
export async function searchAndDownload(keyword) {
    const results = searchOnlineIndex(keyword);
    if (results.length === 0) {
        throw new Error(`No online blueprints found matching "${keyword}". Available tags: medieval, modern, fantasy, farm, castle, ship, tower, etc.`);
    }
    const entry = results[0];
    const filename = `${entry.name}.${entry.format}`;
    const localPath = await downloadFile(entry.url, filename);
    return { entry, localPath };
}

/**
 * Download a random blueprint.
 * @param {string} [sizeFilter] - Optional size filter
 * @returns {Promise<{entry: object, localPath: string}>}
 */
export async function downloadRandom(sizeFilter) {
    const entry = getRandomEntry(sizeFilter);
    const filename = `${entry.name}.${entry.format}`;
    const localPath = await downloadFile(entry.url, filename);
    return { entry, localPath };
}

/**
 * List all online blueprints available for download (formatted for AI).
 */
export function listOnlineBlueprints() {
    const index = loadIndex();
    const lines = index.sources.map(e => 
        `  - ${e.name} [${e.size_estimate}] (${e.tags.slice(0, 3).join(', ')}): ${e.description}`
    );
    return `Online blueprints available for download (${index.sources.length}):\n${lines.join('\n')}`;
}

/**
 * Save a scanned area as a blueprint to the local library.
 * @param {string} name - Blueprint name
 * @param {object} scanData - The scan result from area_scanner
 * @returns {string} The saved file path
 */
export function saveScannedBlueprint(name, scanData) {
    const dir = getLibraryDir();
    const safeName = name.replace(/[^a-zA-Z0-9_-]/g, '_').toLowerCase();
    const filePath = path.join(dir, `${safeName}.json`);

    const blueprint = {
        id: safeName,
        name: name,
        format: 'json',
        created: new Date().toISOString(),
        source: 'world_scan',
        origin: scanData.origin || { x: 0, y: 0, z: 0 },
        size: scanData.size || { x: 0, y: 0, z: 0 },
        blocks: scanData.blocks || []
    };

    fs.writeFileSync(filePath, JSON.stringify(blueprint, null, 2));
    return filePath;
}

/**
 * Download ALL blueprints from the online index to local.
 * Used by the predownload script. Returns stats.
 * @param {function} [onProgress] - Callback(name, status, index, total)
 * @returns {Promise<{success: number, failed: number, skipped: number, errors: string[]}>}
 */
export async function downloadAll(onProgress) {
    const index = loadIndex();
    const stats = { success: 0, failed: 0, skipped: 0, errors: [] };
    const total = index.sources.length;

    for (let i = 0; i < total; i++) {
        const entry = index.sources[i];
        const filename = `${entry.name}.${entry.format}`;
        const dir = getLibraryDir();
        const filePath = path.join(dir, filename);

        // Skip if already exists
        if (fs.existsSync(filePath) && fs.statSync(filePath).size > 0) {
            stats.skipped++;
            if (onProgress) onProgress(entry.name, 'skipped', i + 1, total);
            continue;
        }

        try {
            await downloadFile(entry.url, filename);
            stats.success++;
            if (onProgress) onProgress(entry.name, 'ok', i + 1, total);
        } catch (e) {
            stats.failed++;
            stats.errors.push(`${entry.name}: ${e.message}`);
            if (onProgress) onProgress(entry.name, 'FAILED', i + 1, total);
        }
    }

    return stats;
}
