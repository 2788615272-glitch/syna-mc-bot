// scripts/find_mc_lan_port.js
// 自动扫描本机 LAN 端口找到正在运行的 Minecraft 单人 LAN 服务，
// 并把 settings.js 里的 port / minecraft_version 同步成真实值。
//
// 用法（在 d:\mindcraft\mindcraft-develop 目录下）：
//   node scripts/find_mc_lan_port.js              # 只探测、打印结果，不改文件
//   node scripts/find_mc_lan_port.js --apply      # 探测到后写回 settings.js
//
// 依赖 minecraft-protocol（项目已装）。
//
// 思路：
//   1. 用 PowerShell 列出所有处于 LISTEN 状态的 TCP 端口（限 IPv4 / IPv6 loopback）
//   2. 排除已知非 MC 端口（mindserver 8081、syna 探针 8765 / 8766、常见 HTTP 等）
//   3. 对每个候选端口做 minecraft-protocol.ping，超时 1.5s
//   4. ping 成功的就是 MC 服务，打印协议号 + 版本字符串
//   5. --apply 时回写 settings.js 的 port 字段（version 仅在不一致时建议、不强改）

import { execSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import mc from 'minecraft-protocol';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SETTINGS_PATH = resolve(__dirname, '..', 'settings.js');

const KNOWN_NON_MC = new Set([
    80, 443, 445, 135, 139, 53, 5040, 5037, 7680, 49664, 49665, 49666, 49667, 49668, 49669, 49670,
    8081, // mindserver
    8765, // syna_probe
    8766, // syna_voice
    8000, 3000, 3001, 5173, // 常见前端
    27017, 6379, 5432, 3306, // 常见 DB
]);

function listListeningPorts() {
    // 用 PowerShell 拿 TCP listen 列表，过滤 loopback + IPv4 any
    const ps = `Get-NetTCPConnection -State Listen | ` +
        `Where-Object { $_.LocalAddress -in @('127.0.0.1','0.0.0.0','::','::1') } | ` +
        `Select-Object LocalPort,LocalAddress,OwningProcess | ` +
        `Sort-Object LocalPort -Unique | ConvertTo-Json -Compress`;
    const out = execSync(`powershell -NoProfile -Command "${ps}"`, {
        encoding: 'utf8',
        windowsHide: true,
    }).trim();
    if (!out) return [];
    const data = JSON.parse(out);
    const arr = Array.isArray(data) ? data : [data];
    return arr.map(r => ({
        port: r.LocalPort,
        addr: r.LocalAddress,
        pid: r.OwningProcess,
    }));
}

function processIsJava(pid) {
    try {
        const ps = `Get-CimInstance Win32_Process -Filter "ProcessId=${pid}" | ` +
            `Select-Object -ExpandProperty Name`;
        const name = execSync(`powershell -NoProfile -Command "${ps}"`, {
            encoding: 'utf8',
            windowsHide: true,
        }).trim().toLowerCase();
        return name === 'java.exe' || name === 'javaw.exe';
    } catch {
        return false;
    }
}

function pingPort(host, port, timeoutMs = 1500) {
    return new Promise(resolveP => {
        let done = false;
        const timer = setTimeout(() => {
            if (done) return;
            done = true;
            resolveP(null);
        }, timeoutMs);
        try {
            mc.ping({ host, port, closeTimeout: timeoutMs }, (err, result) => {
                if (done) return;
                done = true;
                clearTimeout(timer);
                if (err) resolveP(null);
                else resolveP(result);
            });
        } catch {
            if (!done) { done = true; clearTimeout(timer); resolveP(null); }
        }
    });
}

async function main() {
    const apply = process.argv.includes('--apply');
    console.log('扫描本机 LISTEN 端口...');
    const ports = listListeningPorts();
    console.log(`共 ${ports.length} 个监听端口`);

    // 优先级：javaw/java 进程占用的端口排前面，再按端口号升序
    const candidates = ports
        .filter(p => !KNOWN_NON_MC.has(p.port))
        .filter(p => p.port >= 1024) // 跳过特权端口
        .map(p => ({ ...p, isJava: processIsJava(p.pid) }))
        .sort((a, b) => (b.isJava - a.isJava) || (a.port - b.port));

    if (!candidates.length) {
        console.error('没有候选端口。请先启动 Minecraft 并点 Open to LAN。');
        process.exit(2);
    }

    console.log('候选端口（java 进程优先）：');
    for (const c of candidates) {
        console.log(`  pid=${c.pid} java=${c.isJava} addr=${c.addr} port=${c.port}`);
    }

    console.log('\n开始 minecraft ping 探测...');
    const hits = [];
    for (const c of candidates) {
        process.stdout.write(`  ping 127.0.0.1:${c.port} ... `);
        const result = await pingPort('127.0.0.1', c.port, 1500);
        if (result) {
            const ver = result.version?.name || '?';
            const proto = result.version?.protocol ?? '?';
            const players = `${result.players?.online ?? '?'}/${result.players?.max ?? '?'}`;
            console.log(`HIT  version=${ver} protocol=${proto} players=${players}`);
            hits.push({ port: c.port, version: ver, protocol: proto });
        } else {
            console.log('no');
        }
    }

    if (!hits.length) {
        console.error('\n所有候选端口都不是 MC 服务。请确认：');
        console.error('  1. MC 已经进入世界（不是主菜单）');
        console.error('  2. 已点 Esc → Open to LAN，并看到 "Local game hosted on port XXXXX"');
        console.error('  3. 防火墙没拦 javaw.exe');
        process.exit(3);
    }

    if (hits.length > 1) {
        console.warn(`\n警告：发现 ${hits.length} 个 MC 端口，使用第一个：${hits[0].port}`);
    }
    const target = hits[0];
    console.log(`\n=> 真实 MC 端口：${target.port}`);
    console.log(`=> 服务端版本：${target.version}（协议号 ${target.protocol}）`);

    if (!apply) {
        console.log('\n（dry-run）加 --apply 把上面的端口写回 settings.js');
        return;
    }

    let src = readFileSync(SETTINGS_PATH, 'utf8');
    const portRe = /("port"\s*:\s*)(-?\d+)/;
    if (!portRe.test(src)) {
        console.error('settings.js 里找不到 "port": <数字>，无法回写。');
        process.exit(4);
    }
    const before = src.match(portRe)[2];
    src = src.replace(portRe, `$1${target.port}`);

    // 顺手核对 minecraft_version
    const verRe = /("minecraft_version"\s*:\s*")([^"]+)"/;
    const m = src.match(verRe);
    if (m && m[2] !== target.version) {
        console.warn(`\n注意：settings.js 写着 minecraft_version="${m[2]}"，但服务端报告是 "${target.version}"。`);
        console.warn('  我没自动改这一行 —— mineflayer 对版本比较敏感，建议你确认后手动改。');
    }

    writeFileSync(SETTINGS_PATH, src, 'utf8');
    console.log(`\n已把 settings.js 的 port 从 ${before} 改成 ${target.port}`);
    console.log('现在可以启动 syna 了：node main.js');
}

main().catch(e => { console.error(e); process.exit(1); });
