/**
 * Test script: connect to Forge 1.20.1 server and dump the login_plugin_request data
 * to understand what FML3 expects us to reply with.
 */
const mc = require('minecraft-protocol');

const host = '127.0.0.1';
const port = 62192; // Update to current LAN port

const client = mc.createClient({
    host,
    port,
    username: 'SynaProbe',
    version: '1.20.1',
    auth: 'offline',
    hideErrors: false
});

// Inject FML3 marker
const origWrite = client.write.bind(client);
let fmlDone = false;
client.write = function(name, data) {
    if (!fmlDone && name === 'set_protocol') {
        fmlDone = true;
        if (data && data.serverHost && !data.serverHost.includes('\0FML3\0')) {
            data.serverHost = data.serverHost + '\0FML3\0';
            console.log('[FML3] Injected marker into handshake');
        }
    }
    return origWrite(name, data);
};

client.on('login_plugin_request', (packet) => {
    console.log('\n=== LOGIN_PLUGIN_REQUEST ===');
    console.log('messageId:', packet.messageId);
    console.log('channel:', packet.channel);
    console.log('data type:', typeof packet.data);
    if (Buffer.isBuffer(packet.data)) {
        console.log('data length:', packet.data.length);
        console.log('data hex:', packet.data.toString('hex'));
        console.log('data utf8 (first 200):', packet.data.toString('utf8').substring(0, 200));
        
        // Try to parse as varint-prefixed
        let offset = 0;
        const buf = packet.data;
        function readVarInt() {
            let result = 0;
            let shift = 0;
            let b;
            do {
                if (offset >= buf.length) return null;
                b = buf[offset++];
                result |= (b & 0x7F) << shift;
                shift += 7;
            } while (b & 0x80);
            return result;
        }
        
        function readString() {
            const len = readVarInt();
            if (len === null || offset + len > buf.length) return null;
            const str = buf.toString('utf8', offset, offset + len);
            offset += len;
            return str;
        }
        
        console.log('\n--- Parsing as FML3 ---');
        const innerChannel = readString();
        console.log('Inner channel:', innerChannel);
        if (innerChannel) {
            const packetId = readVarInt();
            console.log('Packet ID:', packetId);
            console.log('Remaining bytes:', buf.length - offset);
            console.log('Remaining hex:', buf.toString('hex', offset));
        }
    }
    
    // Don't reply yet - just observe
    console.log('\n[Not replying - will timeout]');
});

client.on('disconnect', (packet) => {
    console.log('\nDisconnected:', JSON.stringify(packet));
});

client.on('error', (err) => {
    console.log('Error:', err.message);
});

client.on('end', (reason) => {
    console.log('Connection ended:', reason);
});

// Auto-close after 10 seconds
setTimeout(() => {
    console.log('\nTimeout - closing');
    client.end();
    process.exit(0);
}, 10000);
