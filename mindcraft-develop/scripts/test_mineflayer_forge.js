/**
 * test_mineflayer_forge.js — 测试 mineflayer + FML3 握手能否连接 Forge 1.20.1
 * 使用与 mcdata.js 相同的逻辑（通过 import initBot）
 */
import { initBot } from '../src/utils/mcdata.js';

console.log('=== Testing mineflayer Forge 1.20.1 connection ===\n');

const bot = initBot('SynaTest');

bot.once('login', () => {
    console.log('\n✓✓✓ LOGIN SUCCESS! Bot joined Forge 1.20.1 server ✓✓✓');
    console.log(`  Version: ${bot.version}`);
    console.log(`  Username: ${bot.username}`);
    setTimeout(() => {
        bot.quit();
        process.exit(0);
    }, 3000);
});

bot.on('error', (err) => {
    console.error(`[ERROR] ${err.message}`);
});

bot.on('kicked', (reason) => {
    console.log(`[KICKED] ${reason}`);
    process.exit(1);
});

bot.on('end', (reason) => {
    console.log(`[END] ${reason}`);
    setTimeout(() => process.exit(0), 500);
});

setTimeout(() => {
    console.log('[TIMEOUT] 30s elapsed, giving up');
    bot.quit();
    setTimeout(() => process.exit(1), 500);
}, 30000);
