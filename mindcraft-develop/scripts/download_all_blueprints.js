#!/usr/bin/env node
/**
 * download_all_blueprints.js
 * 
 * Pre-downloads all blueprints from online_index.json to local blueprints/ directory.
 * Uses proxy 127.0.0.1:10090 by default. Set HTTP_PROXY env to override.
 * Each file has a 4-minute timeout. Failed downloads are skipped.
 * 
 * Usage: node scripts/download_all_blueprints.js
 */

import { downloadAll } from '../src/agent/library/blueprint_downloader.js';

console.log('=== Blueprint Pre-Downloader ===');
console.log(`Proxy: ${process.env.HTTPS_PROXY || process.env.HTTP_PROXY || '127.0.0.1:10090 (default)'}`);
console.log(`Timeout: 4 minutes per file`);
console.log('');

const startTime = Date.now();

try {
    const stats = await downloadAll((name, status, idx, total) => {
        const icon = status === 'ok' ? '✓' : status === 'skipped' ? '⊘' : '✗';
        console.log(`  [${idx}/${total}] ${icon} ${name} — ${status}`);
    });

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    console.log('');
    console.log('=== Done ===');
    console.log(`  Downloaded: ${stats.success}`);
    console.log(`  Skipped (already local): ${stats.skipped}`);
    console.log(`  Failed: ${stats.failed}`);
    console.log(`  Time: ${elapsed}s`);

    if (stats.errors.length > 0) {
        console.log('');
        console.log('Failed downloads:');
        for (const err of stats.errors) {
            console.log(`  - ${err}`);
        }
    }
} catch (e) {
    console.error('Fatal error:', e.message);
    process.exit(1);
}
