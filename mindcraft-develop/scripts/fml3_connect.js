/**
 * fml3_connect.js — 完整实现 FML3 握手协议连接 Forge 1.20.1
 * 
 * 基于诊断发现：
 * - Forge 1.20.1 FML3 的 S2CModList packet ID = 5 (不是 wiki 上说的 1)
 * - loginwrapper 格式: [string: channel] [varint: payload_length] [payload]
 * - 需要正确回复 C2SModListReply 才能通过握手
 * 
 * FML3 1.20.1 Packet IDs (from Forge source):
 *   S2C: ModList=1, ModData=2, Registry=3, ConfigData=4, ChannelList=5 ???
 *   实际观察: 第一个包 ID=5, 内容是 mod 列表
 *   
 * 实际上根据 Forge 源码 (LoginProtocolNegotiationHandler):
 *   S2CModList packet discriminator = 1 in the FML handshake protocol
 *   但是 loginwrapper 内部有额外的 length-prefix
 *   
 * 重新分析 hex: 0510096d696e6563726166...
 *   05 = ??? 
 *   10 = 16 (mod count)
 *   然后 mod strings...
 *   
 * 等等，让我重新看：也许 05 不是 packet ID，而是某种 discriminator byte
 * 在 Forge 的 FML3 中，loginwrapper payload 格式是:
 *   [varint: packet_index/discriminator] [actual packet data]
 * 
 * 不管 ID 是什么，关键是正确回复。让我直接解析 mod 列表并构造回复。
 */

import mc from 'minecraft-protocol';

const host = process.argv[2] || '127.0.0.1';
const port = parseInt(process.argv[3]) || 25565;

console.log('=== FML3 Full Handshake Implementation ===\n');

// === Varint helpers ===
function readVarInt(buffer, offset) {
    let value = 0;
    let length = 0;
    let currentByte;
    do {
        if (offset + length >= buffer.length) throw new Error('VarInt: buffer underflow');
        currentByte = buffer[offset + length];
        value |= (currentByte & 0x7F) << (length * 7);
        length++;
        if (length > 5) throw new Error('VarInt too big');
    } while (currentByte & 0x80);
    return { value, length };
}

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

function readString(buffer, offset) {
    const { value: strLen, length: varIntLen } = readVarInt(buffer, offset);
    if (offset + varIntLen + strLen > buffer.length) throw new Error('String: buffer underflow');
    const str = buffer.slice(offset + varIntLen, offset + varIntLen + strLen).toString('utf8');
    return { value: str, length: varIntLen + strLen };
}

function writeString(str) {
    const strBuf = Buffer.from(str, 'utf8');
    return Buffer.concat([writeVarInt(strBuf.length), strBuf]);
}

// === Parse loginwrapper ===
function parseLoginWrapper(data) {
    let offset = 0;
    const { value: channelName, length: channelLen } = readString(data, offset);
    offset += channelLen;
    const { value: payloadLen, length: plLen } = readVarInt(data, offset);
    offset += plLen;
    const payload = data.slice(offset, offset + payloadLen);
    return { channelName, payload, payloadLen };
}

// === Build loginwrapper response ===
function buildLoginWrapper(channelName, payload) {
    const channelBuf = writeString(channelName);
    const lenBuf = writeVarInt(payload.length);
    return Buffer.concat([channelBuf, lenBuf, payload]);
}

// === Parse S2CModList (packet ID 5 in our observation) ===
function parseModListPacket(payload) {
    let offset = 0;
    
    // First byte is packet discriminator
    const { value: packetId, length: pidLen } = readVarInt(payload, offset);
    offset += pidLen;
    
    console.log(`  [Parse] Packet discriminator: ${packetId}`);
    
    // Read mod count
    const { value: modCount, length: mcLen } = readVarInt(payload, offset);
    offset += mcLen;
    console.log(`  [Parse] Mod count: ${modCount}`);
    
    // Read mod entries - each has modId and modName (or just modId?)
    // From hex: 09 6d696e6563726166 74 = string "minecraft"
    // Then: 09 4d696e6563726166 74 = string "Minecraft" 
    // Then: 06 312e32302e31 = string "1.20.1"
    // So format might be: modId, displayName, version for each mod?
    // Or: modCount entries of (modId), then separate data
    
    // Let's try: each mod is just a string (modId)
    const mods = [];
    for (let i = 0; i < modCount; i++) {
        try {
            const { value: modId, length: modIdLen } = readString(payload, offset);
            offset += modIdLen;
            mods.push(modId);
        } catch(e) {
            console.log(`  [Parse] Error reading mod #${i}: ${e.message}`);
            break;
        }
    }
    console.log(`  [Parse] Mods parsed: ${mods.join(', ')}`);
    
    // After mods, try to read channels
    let channels = [];
    try {
        const { value: channelCount, length: ccLen } = readVarInt(payload, offset);
        offset += ccLen;
        console.log(`  [Parse] Channel count: ${channelCount}`);
        
        for (let i = 0; i < channelCount; i++) {
            const { value: resLoc, length: rlLen } = readString(payload, offset);
            offset += rlLen;
            const { value: version, length: vLen } = readString(payload, offset);
            offset += vLen;
            // client required flag
            const clientRequired = payload[offset];
            offset += 1;
            channels.push({ resLoc, version, clientRequired });
        }
        console.log(`  [Parse] Channels: ${channels.map(c => c.resLoc).join(', ')}`);
    } catch(e) {
        console.log(`  [Parse] Channel parse error: ${e.message}`);
    }
    
    // Registries
    let registries = [];
    try {
        if (offset < payload.length) {
            const { value: regCount, length: rcLen } = readVarInt(payload, offset);
            offset += rcLen;
            console.log(`  [Parse] Registry count: ${regCount}`);
            
            for (let i = 0; i < regCount; i++) {
                const { value: regName, length: rnLen } = readString(payload, offset);
                offset += rnLen;
                registries.push(regName);
            }
            console.log(`  [Parse] Registries: ${registries.join(', ')}`);
        }
    } catch(e) {
        console.log(`  [Parse] Registry parse error: ${e.message}`);
    }
    
    // Non-vanilla mods list (optional in some versions)
    let datapackRegistries = [];
    try {
        if (offset < payload.length) {
            const { value: dpCount, length: dpLen } = readVarInt(payload, offset);
            offset += dpLen;
            console.log(`  [Parse] Datapack registry count: ${dpCount}`);
            for (let i = 0; i < dpCount; i++) {
                const { value: dpName, length: dnLen } = readString(payload, offset);
                offset += dnLen;
                datapackRegistries.push(dpName);
            }
        }
    } catch(e) {
        // ignore
    }
    
    console.log(`  [Parse] Remaining bytes after parse: ${payload.length - offset}`);
    
    return { packetId, mods, channels, registries, datapackRegistries };
}

// === Build C2SModListReply ===
// In FML3 1.20.1, the reply packet discriminator should match what server expects
// Based on Forge source: C2SModListReply discriminator = 2
function buildModListReply(serverMods, serverChannels, serverRegistries) {
    // C2SModListReply format:
    // [varint: discriminator (2)]
    // [varint: mod_count] [for each: string modId, string modVersion]  
    // [varint: channel_count] [for each: string channelName, string version]
    // [varint: registry_count] [for each: string registryName]
    // [varint: datapack_count (0)]
    
    const parts = [];
    parts.push(writeVarInt(2)); // C2SModListReply discriminator
    
    // Mods - we claim to have the same mods (just modId, no version in some formats)
    // Actually for FML3 C2SModListReply, format is:
    // modCount, then for each mod: modId, markerURL, humanName, version
    // But simpler approach: just send modCount=0 (no client mods)
    // Or mirror the server's mods
    
    // Try sending 0 mods first (vanilla client pretending to be FML)
    parts.push(writeVarInt(0)); // mod count = 0
    parts.push(writeVarInt(0)); // channel count = 0  
    parts.push(writeVarInt(0)); // registry count = 0
    parts.push(writeVarInt(0)); // datapack registry count = 0 (if applicable)
    
    return Buffer.concat(parts);
}

// === Build C2SAcknowledge ===
function buildAcknowledge() {
    // Discriminator for acknowledge = 99 (0x63) in some versions, or 3
    // Let's try 99 first based on Forge source
    return Buffer.from([0x63]); // writeVarInt(99)
}

// === Main connection ===
const client = mc.createClient({
    host,
    port,
    username: 'SynaBot',
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
        if (data && data.serverHost) {
            data.serverHost = data.serverHost + '\0FML3\0';
            console.log(`[→] set_protocol with FML3 marker: ${data.serverHost}`);
        }
    }
    return origWrite(name, data);
};

let loginPluginCount = 0;
let handshakePhase = 'waiting'; // waiting -> modlist_received -> acknowledged

client.on('packet', (data, meta) => {
    if (meta.name === 'login_plugin_request') {
        loginPluginCount++;
        console.log(`\n[←] login_plugin_request #${loginPluginCount} (messageId=${data.messageId}, channel=${data.channel})`);
        
        if (data.channel === 'fml:loginwrapper' && data.data) {
            try {
                const { channelName, payload } = parseLoginWrapper(data.data);
                console.log(`  Inner channel: ${channelName}, payload size: ${payload.length}`);
                
                if (channelName === 'fml:handshake') {
                    const parsed = parseModListPacket(payload);
                    
                    if (handshakePhase === 'waiting' && parsed.packetId === 5) {
                        // This is the ModList - reply with ModListReply
                        handshakePhase = 'modlist_received';
                        const reply = buildModListReply(parsed.mods, parsed.channels, parsed.registries);
                        const wrapped = buildLoginWrapper('fml:handshake', reply);
                        console.log(`\n[→] C2SModListReply (${wrapped.length} bytes)`);
                        console.log(`  hex: ${wrapped.toString('hex')}`);
                        origWrite('login_plugin_response', {
                            messageId: data.messageId,
                            data: wrapped
                        });
                        return;
                    } else {
                        // Subsequent packets (registry data, config, etc.) - acknowledge
                        console.log(`  [Handling as subsequent packet, sending acknowledge]`);
                        const ack = buildAcknowledge();
                        const wrapped = buildLoginWrapper('fml:handshake', ack);
                        console.log(`[→] C2SAcknowledge (${wrapped.length} bytes)`);
                        origWrite('login_plugin_response', {
                            messageId: data.messageId,
                            data: wrapped
                        });
                        return;
                    }
                }
            } catch(e) {
                console.log(`  Parse error: ${e.message}`);
                console.log(`  Stack: ${e.stack}`);
            }
        }
        
        // Fallback for non-fml channels
        console.log(`[→] login_plugin_response (empty data fallback)`);
        origWrite('login_plugin_response', {
            messageId: data.messageId,
            data: Buffer.alloc(0)
        });
        
    } else if (meta.name === 'disconnect') {
        console.log(`\n[←] DISCONNECT: ${data.reason}`);
    } else if (meta.name === 'success') {
        console.log(`\n[←] LOGIN SUCCESS! uuid=${data.uuid} username=${data.username}`);
    } else if (meta.name === 'compress') {
        console.log(`[←] compress threshold=${data.threshold}`);
    } else if (meta.name === 'encryption_begin') {
        console.log(`[←] encryption_begin`);
    } else {
        console.log(`[←] ${meta.name}`);
    }
});

client.on('login', () => {
    console.log('\n✓✓✓ LOGIN SUCCESSFUL! Bot connected to Forge server! ✓✓✓');
    setTimeout(() => { client.end(); process.exit(0); }, 3000);
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
