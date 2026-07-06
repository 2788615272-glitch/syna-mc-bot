import { testMinecraftConnection } from '../src/mindcraft/control_config.js';

const host = process.argv[2] || '127.0.0.1';
const port = Number(process.argv[3]);
const minecraft_version = process.argv[4] || 'auto';

if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.log(JSON.stringify({ ok: false, error: 'Minecraft LAN port is required before testing.' }));
    process.exit(0);
}

try {
    const result = await testMinecraftConnection({ host, port, minecraft_version });
    console.log(JSON.stringify({ ok: true, result }));
} catch (error) {
    console.log(JSON.stringify({ ok: false, error: String(error?.message || error) }));
}
