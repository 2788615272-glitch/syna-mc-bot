// 探测 LAN 服务器并 dump 完整握手信息（含 forgeData / modinfo）
// 用法：node scripts/ping_forge_lan.js [host] [port]
import mc from 'minecraft-protocol';

const host = process.argv[2] || '127.0.0.1';
const port = parseInt(process.argv[3] || '25565', 10);

console.log(`[ping] ${host}:${port}`);
mc.ping({ host, port }, (err, res) => {
    if (err) {
        console.error('[ping] failed:', err && err.message ? err.message : err);
        process.exit(2);
    }
    console.log('[ping] version =', JSON.stringify(res.version));
    if (res.players) console.log('[ping] players =', JSON.stringify(res.players));
    if (res.forgeData) {
        console.log('[ping] forgeData.fmlNetworkVersion =', res.forgeData.fmlNetworkVersion);
        console.log('[ping] forgeData.channels(count) =', (res.forgeData.channels || []).length);
        console.log('[ping] forgeData.mods(count) =', (res.forgeData.mods || []).length);
        console.log('--- forgeData.mods ---');
        for (const m of (res.forgeData.mods || [])) {
            console.log('  -', m.modid || m.modId, '@', m.modmarker || m.version || '?');
        }
    } else if (res.modinfo) {
        console.log('[ping] (legacy FML2) modinfo.type =', res.modinfo.type);
        console.log('[ping] modinfo.modList(count) =', (res.modinfo.modList || []).length);
        for (const m of (res.modinfo.modList || [])) {
            console.log('  -', m.modid, '@', m.version);
        }
    } else {
        console.log('[ping] no forgeData / modinfo: 这看起来是原版服务器，无需 Forge 握手。');
    }
    // 同时把原始 JSON 打到 stderr 方便排查
    console.error('---RAW---');
    console.error(JSON.stringify(res, null, 2));
});
