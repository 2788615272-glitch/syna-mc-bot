/**
 * diagnose_spawn.js — 诊断 Forge 1.20.1 spawn 超时问题
 * 连接到服务器，记录 login 后收到的所有包名，看 position 包是否到达
 */
const mc = require('minecraft-protocol');

const host = '127.0.0.1';
const port = 25565;
const username = 'SpawnDiag';

console.log(`[diag] Connecting to ${host}:${port} as ${username}...`);

const client = mc.createClient({
    host,
    port,
    username,
    auth: 'offline',
    version: '1.20.1',
    checkTimeoutInterval: 60000,
});

// Inject FML3 marker
const origWrite = client.write.bind(client);
let fmlDone = false;
client.write = function(name, data) {
    if (!fmlDone && name === 'set_protocol') {
        fmlDone = true;
        if (data && data.serverHost) {
            data.serverHost += '\0FML3\0';
            console.log('[diag] Injected FML3 marker');
        }
    }
    return origWrite(name, data);
};

// Remove default login_plugin_request handler
client.removeAllListeners('login_plugin_request');

// FML3 login handler
function writeVarInt(value) {
    const bytes = [];
    do {
        let temp = value & 0x7F;
        value >>>= 7;
        if (value !== 0) temp |= 0x80;
        bytes.push(temp);
    } while (value !== 0);
    return Buffer.from(bytes);
}

let firstLoginPlugin = true;
client.on('login_plugin_request', (packet) => {
    const { messageId, channel, data } = packet;
    console.log(`[diag] login_plugin_request id=${messageId} channel=${channel}`);
    
    if (firstLoginPlugin && channel === 'fml:loginwrapper') {
        firstLoginPlugin = false;
        // Reply with C2SModListReply (discriminator=2, all counts=0)
        const channelName = 'fml:handshake';
        const channelBuf = Buffer.from(channelName, 'utf8');
        const channelLen = writeVarInt(channelBuf.length);
        // payload: discriminator=2, then 5x varint(0)
        const payload = Buffer.from([2, 0, 0, 0, 0, 0]);
        const payloadLen = writeVarInt(payload.length);
        const responseData = Buffer.concat([channelLen, channelBuf, payloadLen, payload]);
        client.write('login_plugin_response', { messageId, data: responseData });
        console.log(`[diag] → C2SModListReply`);
    } else {
        // Acknowledge
        const channelName = 'fml:handshake';
        const channelBuf = Buffer.from(channelName, 'utf8');
        const channelLen = writeVarInt(channelBuf.length);
        const payload = Buffer.from([0x63]);
        const payloadLen = writeVarInt(payload.length);
        const responseData = Buffer.concat([channelLen, channelBuf, payloadLen, payload]);
        client.write('login_plugin_response', { messageId, data: responseData });
        console.log(`[diag] → Acknowledge`);
    }
});

// Track all packets after login
let loginTime = null;
const packetCounts = {};
const importantPackets = ['position', 'map_chunk', 'login', 'respawn', 'abilities', 
    'update_health', 'spawn_position', 'game_state_change', 'custom_payload',
    'player_info', 'declare_recipes', 'tags', 'unlock_recipes'];

client.on('login', (data) => {
    loginTime = Date.now();
    console.log(`[diag] *** LOGIN received! entityId=${data.entityId} gameMode=${data.gameMode}`);
});

client.on('position', (data) => {
    console.log(`[diag] *** POSITION received! x=${data.x} y=${data.y} z=${data.z}`);
    console.log(`[diag] Time since login: ${Date.now() - loginTime}ms`);
    // Send teleport confirm
    if (data.teleportId !== undefined) {
        client.write('teleport_confirm', { teleportId: data.teleportId });
        console.log(`[diag] → teleport_confirm id=${data.teleportId}`);
    }
});

client.on('spawn_position', (data) => {
    console.log(`[diag] *** SPAWN_POSITION received! location=${JSON.stringify(data.location)}`);
});

client.on('map_chunk', (data) => {
    if (!packetCounts['map_chunk']) {
        console.log(`[diag] *** First MAP_CHUNK received! x=${data.x} z=${data.z}`);
        console.log(`[diag] Time since login: ${Date.now() - loginTime}ms`);
    }
    packetCounts['map_chunk'] = (packetCounts['map_chunk'] || 0) + 1;
});

// Listen to ALL packet events to see what's coming through
client.on('packet', (data, meta) => {
    if (!loginTime) return; // only track after login
    const name = meta.name;
    packetCounts[name] = (packetCounts[name] || 0) + 1;
    
    // Log first occurrence of important packets
    if (importantPackets.includes(name) && packetCounts[name] === 1) {
        console.log(`[diag] First '${name}' packet (${Date.now() - loginTime}ms after login)`);
    }
});

// Error handling - don't crash on parse errors
const origEmit = client.emit.bind(client);
client.emit = function(event, ...args) {
    if (event === 'error') {
        const err = args[0];
        const msg = err instanceof Error ? err.message : String(err);
        if (msg.includes('Parse error') || msg.includes('Read error') || msg.includes('PartialReadError')) {
            if (!packetCounts['_parse_errors']) {
                console.log(`[diag] First parse error: ${msg.substring(0, 150)}`);
            }
            packetCounts['_parse_errors'] = (packetCounts['_parse_errors'] || 0) + 1;
            return true;
        }
        console.log(`[diag] ERROR: ${msg}`);
    }
    return origEmit(event, ...args);
};

client.on('error', (err) => {
    // Already handled above
});

client.on('disconnect', (packet) => {
    console.log(`[diag] DISCONNECTED: ${JSON.stringify(packet.reason)}`);
});

client.on('end', (reason) => {
    console.log(`[diag] Connection ended: ${reason}`);
    console.log(`[diag] === Packet summary ===`);
    for (const [name, count] of Object.entries(packetCounts).sort((a,b) => b[1] - a[1])) {
        console.log(`  ${name}: ${count}`);
    }
    process.exit(0);
});

// After 35 seconds, print summary and exit
setTimeout(() => {
    console.log(`\n[diag] === 35s timeout - Packet summary ===`);
    console.log(`[diag] Login received: ${loginTime ? 'YES' : 'NO'}`);
    console.log(`[diag] Position received: ${packetCounts['position'] ? 'YES (' + packetCounts['position'] + ')' : 'NO'}`);
    console.log(`[diag] Chunks received: ${packetCounts['map_chunk'] || 0}`);
    console.log(`[diag] Parse errors: ${packetCounts['_parse_errors'] || 0}`);
    console.log(`[diag] All packets:`);
    for (const [name, count] of Object.entries(packetCounts).sort((a,b) => b[1] - a[1])) {
        console.log(`  ${name}: ${count}`);
    }
    client.end();
    process.exit(0);
}, 35000);
