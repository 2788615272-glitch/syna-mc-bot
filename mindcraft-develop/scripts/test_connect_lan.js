// 直连测试。两种模式：原版 / 抹掉 forgeData
// 用法：node scripts/test_connect_lan.js [mode]   mode = vanilla | strip-forge
import mc from 'minecraft-protocol';
import { createBot } from 'mineflayer';

const mode = process.argv[2] || 'strip-forge';
const host = '127.0.0.1';
const port = 25565;

console.log(`[test] mode=${mode} host=${host} port=${port}`);

if (mode === 'strip-forge') {
    // 拦截 ping，把 forgeData 删掉，让 mineflayer 以为是原版
    const origPing = mc.ping;
    mc.ping = function(opts, cb) {
        return origPing(opts, (err, res) => {
            if (res) {
                if (res.forgeData) { console.log('[test] strip forgeData'); delete res.forgeData; }
                if (res.modinfo)   { console.log('[test] strip modinfo');   delete res.modinfo; }
            }
            cb(err, res);
        });
    };
}

const bot = createBot({
    username: 'syna_test',
    host, port,
    auth: 'offline',
    version: '1.20.1',
    checkTimeoutInterval: 60000,
    hideErrors: false,
});

const start = Date.now();
const timer = setTimeout(() => {
    console.error(`[test] TIMEOUT 25s, no spawn. lastState=${bot._client?.state}`);
    process.exit(3);
}, 25000);

bot.on('login', () => console.log(`[test] login ok @${Date.now()-start}ms`));
bot.on('spawn', () => {
    clearTimeout(timer);
    console.log(`[test] SPAWN ok @${Date.now()-start}ms pos=`, bot.entity.position);
    setTimeout(() => { bot.quit('done'); process.exit(0); }, 2000);
});
bot.on('kicked', (r) => { clearTimeout(timer); console.error('[test] KICKED:', r); process.exit(4); });
bot.on('error',  (e) => { console.error('[test] ERROR:', e?.message || e); });
bot.on('end',    (r) => { console.log('[test] END:', r); });
