/**
 * diagnose_forge_connect.js — 全量诊断 Forge 1.20.1 连接过程
 * 
 * 用法: node scripts/diagnose_forge_connect.js [host] [port]
 * 默认: 127.0.0.1:25565
 * 
 * 功能：
 * - 监听 minecraft-protocol 客户端的所有事件和包
 * - 逐包打印收发内容（含时间戳）
 * - 10秒超时自动退出并汇总
 * - 不猜测，只记录事实
 * 
 * ===== 经验教训（给后续 AI 看） =====
 * [FAILED-2026-05-20] FML3 注入方案失败记录：
 *   1. 在 set_protocol 中注入 \0FML3\0 → 通过了初始握手（不再 ECONNRESET）
 *   2. 监听 login_plugin_request 并回复 data:undefined → Forge 仍然踢人
 *   3. 服务端 FmlBypass mod (relaxChannelValidation + disableLoginPayloads) → 
 *      理论上应该阻止 Forge 发 login queries，但实际效果未确认
 *   4. 核心问题：Forge FML3 的握手不只是 login_plugin_request，
 *      还有 configuration phase（1.20.2+）或复杂的多轮 channel 协商。
 *      minecraft-protocol 对 Forge 的支持极其有限。
 *   
 * [可能的替代方案]
 *   A. 在 server.properties 或 Forge config 中关闭 mod 验证
 *   B. 用 ViaVersion 协议转换
 *   C. 用代理服务器（Velocity/BungeeCord）中转
 *   D. 更激进的服务端 hook：在 Netty pipeline 中直接跳过整个 FML handshake
 *   E. 使用支持 Forge 的 bot 库（如 pyCraft with forge support）
 * =====
 */

import mc from 'minecraft-protocol';

const host = process.argv[2] || '127.0.0.1';
const port = parseInt(process.argv[3]) || 25565;
const TIMEOUT = 15000; // 15s

console.log('='.repeat(70));
console.log(`[DIAG] Forge Connection Diagnostic`);
console.log(`[DIAG] Target: ${host}:${port}`);
console.log(`[DIAG] Timeout: ${TIMEOUT}ms`);
console.log(`[DIAG] Time: ${new Date().toISOString()}`);
console.log('='.repeat(70));

const events = [];
const t0 = Date.now();

function ts() { return `+${Date.now() - t0}ms`; }

function log(tag, msg, data) {
    const entry = { time: ts(), tag, msg, data };
    events.push(entry);
    if (data && typeof data === 'object') {
        const preview = JSON.stringify(data).slice(0, 200);
        console.log(`[${entry.time}] ${tag}: ${msg} ${preview}`);
    } else {
        console.log(`[${entry.time}] ${tag}: ${msg}`);
    }
}

// Step 1: Try ping first
console.log('\n--- Phase 1: Server Ping ---');
mc.ping({ host, port, version: '1.20.1' }, (err, result) => {
    if (err) {
        log('PING', 'FAILED', { error: err.message });
    } else {
        log('PING', 'OK', {
            version: result.version,
            players: result.players,
            description: typeof result.description === 'string' 
                ? result.description.slice(0, 100) 
                : JSON.stringify(result.description).slice(0, 100),
            modinfo: result.modinfo || result.forgeData || 'none'
        });
    }

    // Step 2: Attempt login
    console.log('\n--- Phase 2: Login Attempt (with FML3 marker) ---');
    
    const client = mc.createClient({
        host,
        port,
        username: 'DiagBot',
        auth: 'offline',
        version: '1.20.1',
        hideErrors: false,
        checkTimeoutInterval: TIMEOUT,
    });

    // Inject FML3 marker
    if (client.write) {
        const origWrite = client.write.bind(client);
        let injected = false;
        client.write = function(name, data) {
            if (!injected && name === 'set_protocol') {
                injected = true;
                if (data && data.serverHost && !data.serverHost.includes('\0FML3\0')) {
                    data.serverHost = data.serverHost + '\0FML3\0';
                    log('SEND', 'set_protocol (FML3 injected)', { serverHost: data.serverHost, protocolVersion: data.protocolVersion });
                }
            } else if (name === 'login_start') {
                log('SEND', 'login_start', data);
            } else if (name === 'login_plugin_response') {
                log('SEND', 'login_plugin_response', { messageId: data.messageId, hasData: data.data !== undefined && data.data !== null });
            } else {
                log('SEND', name, data);
            }
            return origWrite(name, data);
        };
    }

    // Listen to ALL packet events
    client.on('packet', (data, meta) => {
        if (meta.name === 'login_plugin_request') {
            log('RECV', `login_plugin_request`, {
                messageId: data.messageId,
                channel: data.channel,
                dataLength: data.data ? data.data.length : 0,
                dataHex: data.data ? data.data.slice(0, 32).toString('hex') : 'null'
            });
            // Respond with "don't understand"
            client.write('login_plugin_response', {
                messageId: data.messageId,
                data: undefined
            });
        } else if (meta.name === 'disconnect') {
            log('RECV', 'disconnect', { reason: data.reason });
        } else if (meta.name === 'compress') {
            log('RECV', 'compress', { threshold: data.threshold });
        } else if (meta.name === 'success') {
            log('RECV', 'login_success', { uuid: data.uuid, username: data.username });
        } else if (meta.name === 'encrypt_begin' || meta.name === 'encryption_begin') {
            log('RECV', 'encryption_request', { serverId: data.serverId });
        } else {
            const preview = JSON.stringify(data).slice(0, 150);
            log('RECV', meta.name, preview);
        }
    });

    // State changes
    client.on('state', (newState, oldState) => {
        log('STATE', `${oldState} -> ${newState}`);
    });

    // Errors
    client.on('error', (err) => {
        log('ERROR', err.message, { code: err.code, stack: err.stack?.split('\n')[1]?.trim() });
    });

    // End/close
    client.on('end', (reason) => {
        log('END', `Connection ended`, { reason });
        printSummary();
    });

    client.on('close', () => {
        log('CLOSE', 'Socket closed');
    });

    // Timeout
    setTimeout(() => {
        log('TIMEOUT', `No login success after ${TIMEOUT}ms — force closing`);
        client.end();
        setTimeout(() => printSummary(), 500);
    }, TIMEOUT);

    // Login success
    client.on('login', () => {
        log('SUCCESS', '*** LOGIN SUCCESSFUL ***');
        client.end();
        setTimeout(() => printSummary(), 500);
    });
});

function printSummary() {
    console.log('\n' + '='.repeat(70));
    console.log('[SUMMARY] Total events:', events.length);
    console.log('[SUMMARY] Duration:', ts());
    
    const recvEvents = events.filter(e => e.tag === 'RECV');
    const errors = events.filter(e => e.tag === 'ERROR');
    const loginPlugins = events.filter(e => e.tag === 'RECV' && e.msg.includes('login_plugin'));
    const disconnects = events.filter(e => e.tag === 'RECV' && e.msg.includes('disconnect'));
    const success = events.filter(e => e.tag === 'SUCCESS');
    
    console.log('[SUMMARY] Packets received:', recvEvents.length);
    console.log('[SUMMARY] login_plugin_requests:', loginPlugins.length);
    console.log('[SUMMARY] Disconnects:', disconnects.length);
    console.log('[SUMMARY] Errors:', errors.length);
    console.log('[SUMMARY] Login success:', success.length > 0 ? 'YES' : 'NO');
    
    if (disconnects.length > 0) {
        console.log('\n[DISCONNECT REASON]:');
        disconnects.forEach(d => console.log('  ', JSON.stringify(d.data)));
    }
    if (errors.length > 0) {
        console.log('\n[ERRORS]:');
        errors.forEach(e => console.log('  ', e.msg, JSON.stringify(e.data)));
    }
    if (loginPlugins.length > 0) {
        console.log('\n[LOGIN PLUGIN CHANNELS]:');
        loginPlugins.forEach(p => console.log('  ', JSON.stringify(p.data)));
    }
    
    console.log('\n[VERDICT]:');
    if (success.length > 0) {
        console.log('  ✓ Bot CAN connect to this Forge server!');
    } else if (disconnects.length > 0) {
        console.log('  ✗ Server explicitly disconnected the bot.');
        console.log('  → Check disconnect reason above.');
        console.log('  → If FML-related: the FmlBypass mod is not working or not loaded.');
    } else if (loginPlugins.length > 0) {
        console.log('  ✗ Server sent login_plugin_requests — FmlBypass did NOT suppress them.');
        console.log('  → The mod is either not loaded or disableLoginPayloads() failed.');
        console.log('  → Check Minecraft server logs for [SynaFmlBypass] messages.');
    } else if (errors.length > 0) {
        console.log('  ✗ Connection error (likely ECONNRESET or ECONNREFUSED).');
        console.log('  → Server may not be running, or port is wrong.');
    } else {
        console.log('  ? Timeout with no clear error — connection hung.');
        console.log('  → Possibly stuck in FML negotiation phase.');
    }
    
    console.log('='.repeat(70));
    process.exit(0);
}
