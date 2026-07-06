/**
 * Blueprint diagnostic logger — writes [BP-DIAG] messages to both
 * console AND a dedicated log file for easy inspection.
 * 
 * Usage:
 *   import { bpLog, bpLogReset } from './bp_log.js';
 *   bpLog('upload result:', JSON.stringify(res));
 */

import fs from 'fs';
import path from 'path';

const LOG_DIR = path.resolve(import.meta.dirname || path.dirname(new URL(import.meta.url).pathname), '../../../logs');
const LOG_FILE = path.join(LOG_DIR, 'blueprint_build.log');
const BP_LOG_TO_CONSOLE = process.env.SYNA_BP_LOG_CONSOLE === '1';

// Ensure log directory exists
try { fs.mkdirSync(LOG_DIR, { recursive: true }); } catch (_) { /* ignore */ }

/**
 * Log a blueprint diagnostic message to console + file.
 * Automatically prefixes with timestamp and [BP-DIAG].
 */
export function bpLog(...args) {
    const ts = new Date().toISOString();
    const line = `[${ts}] [BP-DIAG] ${args.join(' ')}`;
    if (BP_LOG_TO_CONSOLE) console.log(line);
    try {
        fs.appendFileSync(LOG_FILE, line + '\n');
    } catch (_) { /* ignore write errors */ }
}

/**
 * Reset (truncate) the blueprint log file. Call at the start of a new build session.
 */
export function bpLogReset() {
    try {
        fs.writeFileSync(LOG_FILE, `=== Blueprint Build Log — reset at ${new Date().toISOString()} ===\n`);
    } catch (_) { /* ignore */ }
}

export default bpLog;
