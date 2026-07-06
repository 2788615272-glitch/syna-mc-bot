/**
 * test_mod_registry.js
 *
 * 不启动 AI / LLM，仅用 mineflayer 连服一次，验证：
 *   1) syna 能否进入这个（可能装了 mod 的）世界
 *   2) 进入后能识别多少 mod 命名空间 / 物品 / 方块 / 实体
 *   3) buildPromptSnippet() 会塞给 LLM 看的内容长啥样
 *
 * 用法（示例）：
 *   node scripts/test_mod_registry.js --host 127.0.0.1 --port 25565 --user SynaProbe
 *   node scripts/test_mod_registry.js --host 127.0.0.1 --port 25565 --version 1.20.1 --user SynaProbe
 *
 * 默认从 settings.js 读 host/port/version；命令行参数会覆盖。
 * 跑完会：
 *   - 打印 mod 清单
 *   - 在 logs/mod_registry_<host>.json 落盘完整注册表
 *   - 5 秒后自动退出
 */

import path from 'path';
import { fileURLToPath } from 'url';
import { createBot } from 'mineflayer';
import * as modRegistry from '../src/agent/mod_registry.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function getArg(name, fallback = null) {
    const eq = process.argv.find(a => a.startsWith(`--${name}=`));
    if (eq) return eq.split('=').slice(1).join('=');
    const i = process.argv.indexOf(`--${name}`);
    if (i !== -1 && i + 1 < process.argv.length) return process.argv[i + 1];
    return fallback;
}

async function loadSettings() {
    try {
        const mod = await import('../settings.js');
        return mod.default || mod;
    } catch (e) {
        console.warn('[test] 读 settings.js 失败，全部用命令行参数:', e.message);
        return {};
    }
}

(async () => {
    const settings = await loadSettings();

    const host = getArg('host', settings.host || '127.0.0.1');
    const port = parseInt(getArg('port', settings.port || 25565), 10);
    const username = getArg('user', settings.username || 'SynaProbe');
    const auth = getArg('auth', settings.auth || 'offline');
    let version = getArg('version', settings.minecraft_version || 'auto');
    if (version === 'auto') version = false; // mineflayer 自动协商

    console.log('═════════════════════════════════════════════════');
    console.log('  Syna 模组识别探针');
    console.log('═════════════════════════════════════════════════');
    console.log(`  目标:    ${host}:${port}`);
    console.log(`  用户名:  ${username}`);
    console.log(`  版本:    ${version || 'auto'}`);
    console.log(`  Auth:    ${auth}`);
    console.log('─────────────────────────────────────────────────');

    const bot = createBot({
        host, port, username, auth,
        version: version || undefined,
        checkTimeoutInterval: 60000,
    });

    // 关键：跟正式启动一样把 mod_registry 挂上
    modRegistry.attachToBot(bot, { host: `${host}:${port}` });

    const t0 = Date.now();
    let resolved = false;

    function finish(reason) {
        if (resolved) return;
        resolved = true;
        const ms = Date.now() - t0;
        console.log('\n─────────────────────────────────────────────────');
        console.log(`  完成原因: ${reason}  (${ms}ms)`);
        console.log('─────────────────────────────────────────────────');

        const captured = modRegistry.isCaptured && modRegistry.isCaptured();
        if (!captured) {
            console.log('  ⚠️  没抓到注册表（可能 spawn 之前就断了）。');
            console.log('     如果服务端是 Forge 强握手且要求 mod 列表完全一致，');
            console.log('     mineflayer 4.33 默认做不到，需要装 minecraft-protocol-forge。');
        } else {
            const ns = modRegistry.getModNamespaces();
            console.log(`  ✅ 抓到注册表`);
            console.log(`     mod 命名空间数: ${ns.length}`);
            console.log(`     mods: ${ns.slice(0, 30).join(', ')}${ns.length > 30 ? ' …' : ''}`);
            console.log(`     dump 文件: ${modRegistry.getDumpPath()}`);
            console.log('\n──── prompt snippet（注入给 LLM 的部分） ────');
            console.log(modRegistry.buildPromptSnippet({ maxItemsPerMod: 6, maxMods: 20 }));
        }

        try { bot.end(); } catch (_) {}
        setTimeout(() => process.exit(captured ? 0 : 2), 200);
    }

    bot.once('spawn', () => {
        // spawn 回调里 mod_registry 内部会自动 harvest，再等一帧确保 dump 写入
        setTimeout(() => finish('spawn 完成'), 1500);
    });

    bot.on('kicked', (reason) => {
        console.log('  ❌ 被踢:', reason);
        finish('kicked');
    });

    bot.on('error', (err) => {
        console.log('  ❌ 错误:', err && err.message);
    });

    bot.on('end', (reason) => {
        finish('socket end: ' + (reason || ''));
    });

    // 兜底超时
    setTimeout(() => finish('超时(20s)'), 20000);
})();
