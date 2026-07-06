/**
 * debug_journal.js
 * 
 * AI自用的报错日志系统。当Syna遇到报错/困惑时，
 * 她可以调用 !reportBug 把问题记录到 logs/ai_debug_journal.md，
 * 方便开发者查看AI遇到了什么问题。
 */

import fs from 'fs';
import path from 'path';

const LOG_DIR = path.resolve('./logs');
const JOURNAL_PATH = path.join(LOG_DIR, 'ai_debug_journal.md');

// 去重限流：同一错误摘要在 DEDUP_WINDOW_MS 内只记录一次
const DEDUP_WINDOW_MS = 5 * 60 * 1000; // 5分钟
const recentReports = new Map(); // key: errorSummary, value: timestamp

/**
 * Ensure the logs directory exists.
 */
function ensureLogDir() {
    if (!fs.existsSync(LOG_DIR)) {
        fs.mkdirSync(LOG_DIR, { recursive: true });
    }
}

/**
 * Check if this error was recently reported (dedup).
 * @param {string} errorKey
 * @returns {boolean} true if should be suppressed
 */
function isDuplicate(errorKey) {
    const now = Date.now();
    const lastTime = recentReports.get(errorKey);
    if (lastTime && (now - lastTime) < DEDUP_WINDOW_MS) {
        return true;
    }
    recentReports.set(errorKey, now);
    // Clean old entries
    for (const [key, ts] of recentReports) {
        if (now - ts > DEDUP_WINDOW_MS) {
            recentReports.delete(key);
        }
    }
    return false;
}

/**
 * Write a bug report entry to the debug journal.
 * 
 * @param {object} report
 * @param {string} report.error - The error message
 * @param {string} report.context - What the AI was doing when the error occurred
 * @param {string} report.analysis - AI's understanding/guess about the cause
 * @param {string} [report.suggestion] - AI's suggestion for fixing it
 * @param {object} [report.rawData] - Raw diagnostic data (mod response, status, etc.)
 * @param {boolean} [report.force] - Skip dedup check
 * @returns {string} Confirmation message or suppression notice
 */
export function writeBugReport(report) {
    const summary = summarizeError(report.error);
    
    // Dedup check
    if (!report.force && isDuplicate(summary)) {
        return `[去重] 同一错误 "${summary}" 5分钟内已记录，跳过重复写入。`;
    }

    ensureLogDir();

    const timestamp = new Date().toLocaleString('zh-CN', { 
        timeZone: 'Asia/Shanghai',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    });

    // Build the markdown entry
    let entry = `\n## ${timestamp} — ${summary}\n\n`;
    
    entry += `**错误信息：**\n\`\`\`\n${report.error}\n\`\`\`\n\n`;
    entry += `**我在做什么：** ${report.context}\n\n`;
    entry += `**我的理解：** ${report.analysis}\n\n`;
    
    if (report.suggestion) {
        entry += `**建议：** ${report.suggestion}\n\n`;
    }

    if (report.rawData) {
        entry += `**诊断数据：**\n\`\`\`json\n${JSON.stringify(report.rawData, null, 2)}\n\`\`\`\n\n`;
    }

    entry += `---\n`;

    // Initialize file with header if it doesn't exist
    if (!fs.existsSync(JOURNAL_PATH)) {
        const header = `# AI Debug Journal\n\n> Syna 自动记录的报错日志。每当遇到无法解决的问题时，她会把错误信息和自己的分析写在这里。\n\n---\n`;
        fs.writeFileSync(JOURNAL_PATH, header, 'utf-8');
    }

    // Append the entry
    fs.appendFileSync(JOURNAL_PATH, entry, 'utf-8');

    return `Bug report saved to logs/ai_debug_journal.md (${timestamp})`;
}

/**
 * Extract a short summary from the error message for the heading.
 */
function summarizeError(errorMsg) {
    if (!errorMsg) return '未知错误';
    
    // Try to extract the key part
    const firstLine = errorMsg.split('\n')[0].trim();
    
    // Common patterns
    if (firstLine.includes('ECONNREFUSED')) return 'Mod通信失败 (连接被拒绝)';
    if (firstLine.includes('ETIMEDOUT') || firstLine.includes('timed out')) return '连接超时';
    if (firstLine.includes('404')) return '资源未找到 (404)';
    if (firstLine.includes('ENOTFOUND')) return 'DNS解析失败';
    if (firstLine.includes('permission') || firstLine.includes('EACCES')) return '权限不足';
    if (firstLine.includes('not a function')) return '函数调用错误';
    if (firstLine.includes('undefined')) return '未定义变量/属性';
    if (firstLine.includes('Cannot read prop')) return '空引用错误';
    if (firstLine.includes('placed') && firstLine.includes('0')) return '建筑placed=0';
    
    // Truncate if too long
    if (firstLine.length > 50) return firstLine.substring(0, 47) + '...';
    return firstLine;
}

/**
 * Read the journal contents (for AI to review past issues).
 * @returns {string} The journal content or a message if empty
 */
export function readJournal() {
    if (!fs.existsSync(JOURNAL_PATH)) {
        return '还没有任何报错记录。';
    }
    const content = fs.readFileSync(JOURNAL_PATH, 'utf-8');
    // If too long, return only the last 3000 chars
    if (content.length > 3000) {
        return '...(earlier entries omitted)...\n' + content.slice(-3000);
    }
    return content;
}

/**
 * Get the count of bug reports in the journal.
 * @returns {number}
 */
export function getReportCount() {
    if (!fs.existsSync(JOURNAL_PATH)) return 0;
    const content = fs.readFileSync(JOURNAL_PATH, 'utf-8');
    return (content.match(/^## /gm) || []).length;
}
