/**
 * diagnose_fml3_handshake.js — 解析 FML3 login_plugin_request 的内容
 * 
 * 根据诊断结果，Forge 1.20.1 发送的 login_plugin_request 格式：
 * - channel: "fml:loginwrapper"
 * - data: [varint length] [inner channel name] [payload]
 * - inner channel: "fml:handshake"
 * - payload: FML3 handshake protocol data
 * 
 * FML3 Handshake Protocol (Forge 1.20.1):
 * Server -> Client:
 *   1. S2CModList: mod list + channels + registries
 *   2. S2CRegistry: registry data (may be multiple)
 * Client -> Server:
 *   1. C2SModListReply: acknowledge mod list
 *   2. C2SAcknowledge: acknowledge completion
 * 
 * 参考: https://wiki.vg/Minecraft_Forge_Handshake#FML3_(1.18+)
 */

import mc from 'minecraft-protocol';

const host = process.argv[2] || '127.0.0.1';
const port = parseInt(process.argv[3]) || 25565;

console.log('=== FML3 Handshake Deep Analysis ===\n');

// Varint reader helper
function readVarInt(buffer, offset) {
    let value = 0;
    let length = 0;
    let currentByte;
    do {
        currentByte = buffer[offset + length];
        value |= (currentByte & 0x7F) << (length * 7);
        length++;
        if (length > 5) throw new Error('VarInt too big');
    } while (currentByte & 0x80);
    return { value, length };
}

// Varint writer helper
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

// Read a string (varint length + utf8)
function readString(buffer, offset) {
    const { value: strLen, length: varIntLen } = readVarInt(buffer, offset);
    const str = buffer.slice(offset + varIntLen, offset + varIntLen + strLen).toString('utf8');
    return { value: str, length: varIntLen + strLen };
}

// Write a string (varint length + utf8)
function writeString(str) {
    const strBuf = Buffer.from(str, 'utf8');
    return Buffer.concat([writeVarInt(strBuf.length), strBuf]);
}

function parseLoginWrapper(data) {
    // loginwrapper format: [string: inner channel] [varint: payload_length] [bytes: payload]
    let offset = 0;
    const { value: channelName, length: channelLen } = readString(data, offset);
    offset += channelLen;
    // Next is a varint for the inner payload length
    const { value: payloadLen, length: plLen } = readVarInt(data, offset);
    offset += plLen;
    console.log(`  loginwrapper payload_length field: ${payloadLen}`);
    const payload = data.slice(offset);
    return { channelName, payload, payloadLen };
}

function parseS2CModList(payload) {
    // S2CModList format (FML3 for 1.20.1):
    // packetId (varint, should be 1 for ModList)
    // modCount (varint)
    // for each mod: modId (string)
    // channelCount (varint)  
    // for each channel: channelName (string), version (string)
    // registryCount (varint)
    // for each registry: registryName (string)
    
    let offset = 0;
    const { value: packetId, length: pidLen } = readVarInt(payload, offset);
    offset += pidLen;
    console.log(`  Packet ID: ${packetId}`);
    
    if (packetId === 1) {
        // ModList
        const { value: modCount, length: mcLen } = readVarInt(payload, offset);
        offset += mcLen;
        console.log(`  Mod count: ${modCount}`);
        
        const mods = [];
        for (let i = 0; i < modCount && offset < payload.length; i++) {
            try {
                const { value: modId, length: modIdLen } = readString(payload, offset);
                offset += modIdLen;
                mods.push(modId);
            } catch(e) { break; }
        }
        console.log(`  Mods: ${mods.join(', ')}`);
        
        // Channels
        if (offset < payload.length) {
            const { value: channelCount, length: ccLen } = readVarInt(payload, offset);
            offset += ccLen;
            console.log(`  Channel count: ${channelCount}`);
            
            const channels = [];
            for (let i = 0; i < channelCount && offset < payload.length; i++) {
                try {
                    const { value: chName, length: chNameLen } = readString(payload, offset);
                    offset += chNameLen;
                    const { value: chVersion, length: chVerLen } = readString(payload, offset);
                    offset += chVerLen;
                    channels.push(`${chName}@${chVersion}`);
                } catch(e) { break; }
            }
            console.log(`  Channels: ${channels.join(', ')}`);
        }
        
        // Registries
        if (offset < payload.length) {
            const { value: regCount, length: rcLen } = readVarInt(payload, offset);
            offset += rcLen;
            console.log(`  Registry count: ${regCount}`);
            
            const registries = [];
            for (let i = 0; i < regCount && offset < payload.length; i++) {
                try {
                    const { value: regName, length: regNameLen } = readString(payload, offset);
                    offset += regNameLen;
                    registries.push(regName);
                } catch(e) { break; }
            }
            console.log(`  Registries: ${registries.join(', ')}`);
        }
        
        return { packetId, mods, offset };
    }
    
    return { packetId, raw: payload.toString('hex').slice(0, 100) };
}

// Build C2SModListReply
function buildC2SModListReply() {
    // C2SModListReply format:
    // packetId = 2
    // modCount = 0 (we have no mods)
    // channelCount = 0
    // registryCount = 0 (or mirror server's registries)
    // hasOptionalData = false (varint 0)
    const parts = [
        writeVarInt(2),  // packet id = C2SModListReply
        writeVarInt(0),  // mod count = 0
        writeVarInt(0),  // channel count = 0
        writeVarInt(0),  // registry count = 0 (empty = accept all)
    ];
    return Buffer.concat(parts);
}

// Build C2SAcknowledge
function buildC2SAcknowledge() {
    // packetId = 99 (0x63) for Acknowledge in FML3
    // Actually in FML3 1.20.1 it's packet id 3
    return writeVarInt(3); // C2SAcknowledge
}

// Wrap payload in loginwrapper format
function wrapLoginPayload(channelName, payload) {
    return Buffer.concat([writeString(channelName), payload]);
}

const client = mc.createClient({
    host,
    port,
    username: 'DiagBot2',
    auth: 'offline',
    version: '1.20.1',
    hideErrors: false,
    checkTimeoutInterval: 20000,
});

// Inject FML3 marker
const origWrite = client.write.bind(client);
let injected = false;
client.write = function(name, data) {
    if (!injected && name === 'set_protocol') {
        injected = true;
        if (data && data.serverHost && !data.serverHost.includes('\0FML3\0')) {
            data.serverHost = data.serverHost + '\0FML3\0';
            console.log(`[SEND] set_protocol with FML3 marker`);
        }
    }
    return origWrite(name, data);
};

let loginPluginCount = 0;

client.on('packet', (data, meta) => {
    if (meta.name === 'login_plugin_request') {
        loginPluginCount++;
        console.log(`\n[RECV] login_plugin_request #${loginPluginCount}`);
        console.log(`  messageId: ${data.messageId}`);
        console.log(`  channel: ${data.channel}`);
        console.log(`  data length: ${data.data ? data.data.length : 0}`);
        
        if (data.channel === 'fml:loginwrapper' && data.data) {
            try {
                const { channelName, payload } = parseLoginWrapper(data.data);
                console.log(`  inner channel: ${channelName}`);
                console.log(`  payload length: ${payload.length}`);
                console.log(`  payload hex (first 64): ${payload.slice(0, 64).toString('hex')}`);
                
                if (channelName === 'fml:handshake') {
                    const parsed = parseS2CModList(payload);
                    console.log(`  parsed:`, JSON.stringify(parsed, null, 2).slice(0, 500));
                    
                    // Build appropriate reply
                    if (parsed.packetId === 1) {
                        // Reply with C2SModListReply
                        const reply = buildC2SModListReply();
                        const wrapped = wrapLoginPayload('fml:handshake', reply);
                        console.log(`\n[SEND] C2SModListReply (wrapped in loginwrapper)`);
                        console.log(`  reply hex: ${wrapped.toString('hex')}`);
                        origWrite('login_plugin_response', {
                            messageId: data.messageId,
                            data: wrapped
                        });
                        return;
                    } else if (parsed.packetId === 3 || parsed.packetId === 2) {
                        // Server sent registry data or something else, acknowledge
                        const ack = buildC2SAcknowledge();
                        const wrapped = wrapLoginPayload('fml:handshake', ack);
                        console.log(`\n[SEND] C2SAcknowledge`);
                        origWrite('login_plugin_response', {
                            messageId: data.messageId,
                            data: wrapped
                        });
                        return;
                    }
                }
            } catch(e) {
                console.log(`  parse error: ${e.message}`);
            }
        }
        
        // Fallback: reply with data (not undefined) to avoid "unexpected_query_response"
        console.log(`[SEND] login_plugin_response (empty buffer fallback)`);
        origWrite('login_plugin_response', {
            messageId: data.messageId,
            data: Buffer.alloc(0)
        });
        
    } else if (meta.name === 'disconnect') {
        console.log(`\n[RECV] DISCONNECT: ${data.reason}`);
    } else if (meta.name === 'success') {
        console.log(`\n[RECV] LOGIN SUCCESS! uuid=${data.uuid} username=${data.username}`);
    } else if (meta.name === 'compress') {
        console.log(`[RECV] compress threshold=${data.threshold}`);
    } else {
        console.log(`[RECV] ${meta.name}`);
    }
});

client.on('login', () => {
    console.log('\n*** LOGIN SUCCESSFUL! Bot is in the game! ***');
    setTimeout(() => { client.end(); process.exit(0); }, 2000);
});

client.on('error', (err) => {
    console.log(`[ERROR] ${err.message}`);
});

client.on('end', (reason) => {
    console.log(`[END] ${reason}`);
    setTimeout(() => process.exit(0), 500);
});

setTimeout(() => {
    console.log('\n[TIMEOUT] 20s elapsed');
    client.end();
    setTimeout(() => process.exit(1), 500);
}, 20000);
