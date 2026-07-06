// Test FML3 handshake + login_plugin_request handling for Forge 1.20.1
import mc from 'minecraft-protocol';

const client = mc.createClient({
    host: '127.0.0.1',
    port: 25565,
    username: 'SynaTest',
    version: '1.20.1',
    auth: 'offline',
    fakeHost: '127.0.0.1\0FML3\0'
});

client.on('connect', () => console.log('[OK] TCP connected'));

// Handle Forge login_plugin_request packets
// Respond with undefined data = "I don't understand this channel" per MC protocol
client.on('login_plugin_request', (packet) => {
    console.log(`[FML3] login_plugin_request messageId=${packet.messageId} channel=${packet.channel}`);
    client.write('login_plugin_response', {
        messageId: packet.messageId,
        data: undefined
    });
});

client.on('login', (p) => {
    console.log('[OK] LOGIN SUCCESS! entityId=' + p.entityId);
    setTimeout(() => { client.end(); process.exit(0); }, 2000);
});

client.on('error', (e) => {
    console.error('[ERR]', e.message);
    process.exit(1);
});

client.on('kick_disconnect', (p) => {
    console.log('[KICK]', JSON.stringify(p));
    client.end();
    process.exit(1);
});

client.on('disconnect', (p) => {
    console.log('[DISCONNECT]', JSON.stringify(p));
    process.exit(1);
});

setTimeout(() => {
    console.log('[TIMEOUT] No response after 15s');
    process.exit(1);
}, 15000);
