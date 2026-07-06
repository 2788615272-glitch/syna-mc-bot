import { Server } from 'socket.io';
import express from 'express';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import * as mindcraft from './mindcraft.js';
import { readFileSync } from 'fs';
import appSettings from '../../settings.js';
import { readModelConfig, writeModelConfig } from './model_config.js';
import { applyControlConfigToSettings, readControlConfig, testMinecraftConnection, validateMinecraftLaunchConfig, writeMinecraftConfig } from './control_config.js';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Mindserver is:
// - central hub for communication between all agent processes
// - api to control from other languages and remote users 
// - host for webapp

let io;
let server;
const agent_connections = {};
const agent_listeners = [];

function readJsonFile(filePath) {
    return JSON.parse(readFileSync(filePath, 'utf8').replace(/^\uFEFF/, ''));
}

const settings_spec = readJsonFile(path.join(__dirname, 'public/settings_spec.json'));

class AgentConnection {
    constructor(settings, viewer_port) {
        this.socket = null;
        this.settings = settings;
        this.in_game = false;
        this.full_state = null;
        this.viewer_port = viewer_port;
    }
    setSettings(settings) {
        this.settings = settings;
    }
}

export function registerAgent(settings, viewer_port) {
    let agentConnection = new AgentConnection(settings, viewer_port);
    agent_connections[settings.profile.name] = agentConnection;
}

export function logoutAgent(agentName) {
    if (agent_connections[agentName]) {
        agent_connections[agentName].in_game = false;
        agentsStatusUpdate();
    }
}

function stopAllAgents() {
    console.log('Killing all agents');
    const stopped = [];
    for (let agentName in agent_connections) {
        stopped.push(agentName);
        mindcraft.stopAgent(agentName);
    }
    return stopped;
}

// Initialize the server
export function createMindServer(host_public = false, port = 8080) {
    const app = express();
    server = http.createServer(app);
    io = new Server(server);

    // Serve static files
    const __dirname = path.dirname(fileURLToPath(import.meta.url));
    app.use(express.static(path.join(__dirname, 'public')));
    app.use(express.json({ limit: '1mb' }));

    app.get('/api/health', (req, res) => {
        res.json({
            ok: true,
            service: 'mindserver',
            agents: Object.entries(agent_connections).map(([name, conn]) => ({
                name,
                in_game: conn.in_game,
                socket_connected: !!conn.socket,
            })),
        });
    });

    app.post('/api/agents/:agentName/message', (req, res) => {
        const agentName = req.params.agentName;
        const body = req.body || {};
        const message = String(body.message || '').trim();
        const from = String(body.from || 'Syna');

        if (!message) {
            return res.status(400).json({ ok: false, error: 'message is required' });
        }

        const result = sendMessageToAgent(agentName, { from, message });
        if (!result.ok) {
            return res.status(404).json(result);
        }

        return res.json({ ok: true, agentName, from, message });
    });

    app.post('/api/agents/:agentName/voice-input', (req, res) => {
        const agentName = req.params.agentName;
        const body = req.body || {};
        const text = String(body.text || body.message || '').trim();
        const from = String(body.from || 'SynaMic');

        if (!text) {
            return res.status(400).json({ ok: false, error: 'text is required' });
        }

        io.emit('voice-log', {
            agentName,
            type: 'voice-input',
            text,
            source: 'api',
            from,
        });

        const result = sendMessageToAgent(agentName, { from, message: text, channel: 'voice' });
        if (!result.ok) {
            return res.status(404).json(result);
        }

        return res.json({ ok: true, agentName, from, text });
    });

    app.post('/api/agents/:agentName/voice-enabled', (req, res) => {
        const agentName = req.params.agentName;
        const conn = agent_connections[agentName];
        if (!conn) {
            return res.status(404).json({ ok: false, error: `Agent '${agentName}' not found.` });
        }
        const enabled = Boolean(req.body?.enabled);
        conn.settings = {
            ...conn.settings,
            syna_voice: {
                ...(conn.settings?.syna_voice || {}),
                enabled,
            },
        };
        if (conn.socket) conn.socket.emit('apply-settings', conn.settings);
        agentsStatusUpdate();
        return res.json({ ok: true, agentName, enabled });
    });

    app.get('/api/model-config', (req, res) => {
        try {
            res.json({ ok: true, ...readModelConfig() });
        } catch (error) {
            res.status(500).json({ ok: false, error: String(error.message || error) });
        }
    });

    app.post('/api/model-config', (req, res) => {
        try {
            const config = writeModelConfig(req.body || {});
            res.json({ ok: true, ...config });
        } catch (error) {
            res.status(400).json({ ok: false, error: String(error.message || error) });
        }
    });

    app.get('/api/control-config', (req, res) => {
        try {
            res.json({ ok: true, ...readControlConfig() });
        } catch (error) {
            res.status(500).json({ ok: false, error: String(error.message || error) });
        }
    });

    app.post('/api/minecraft-config', (req, res) => {
        try {
            const config = writeMinecraftConfig({ ...(req.body || {}), last_test: null });
            res.json({ ok: true, ...config });
        } catch (error) {
            res.status(400).json({ ok: false, error: String(error.message || error) });
        }
    });

    app.post('/api/minecraft-test', async (req, res) => {
        try {
            const result = await testMinecraftConnection(req.body || {});
            res.json({ ok: true, result, ...readControlConfig() });
        } catch (error) {
            res.status(400).json({ ok: false, error: String(error.message || error), ...readControlConfig() });
        }
    });

    app.post('/api/agents/syna/launch', async (req, res) => {
        try {
            const profilePath = req.body?.profilePath || './profiles/syna.json';
            const profile = readJsonFile(profilePath);
            const settings = applyControlConfigToSettings(JSON.parse(JSON.stringify(appSettings)));
            settings.profile = profile;
            validateMinecraftLaunchConfig(settings);

            if (agent_connections[profile.name]) {
                return res.status(409).json({ ok: false, error: `Agent '${profile.name}' already exists. Use Connect or Restart.` });
            }

            const returned = await mindcraft.createAgent(settings);
            if (!returned.success && agent_connections[profile.name]) {
                mindcraft.destroyAgent(profile.name);
                delete agent_connections[profile.name];
            }
            agentsStatusUpdate();
            return res.status(returned.success ? 200 : 400).json({ ok: returned.success, ...returned });
        } catch (error) {
            return res.status(400).json({ ok: false, success: false, error: String(error.message || error) });
        }
    });

    app.post('/api/agents/stop-all', (req, res) => {
        const stopped = stopAllAgents();
        res.json({ ok: true, stopped });
    });

    app.post('/api/shutdown', (req, res) => {
        console.log(`Shutting down via HTTP from ${req.ip || req.socket?.remoteAddress || 'unknown'} ua=${req.get('user-agent') || ''}`);
        const stopped = stopAllAgents();
        res.json({ ok: true, stopped });
        setTimeout(() => {
            console.log('Exiting MindServer');
            globalThis.process.exit(0);
        }, 800);
    });

    // Texture proxy: resolve item/block textures using minecraft-assets with version fallback
    app.get('/assets/item/:agent/:name.png', async (req, res) => {
        try {
            const agentName = req.params.agent;
            const rawName = req.params.name;
            const itemName = String(rawName).toLowerCase();
            const conn = agent_connections[agentName];
            const preferred = conn?.settings?.minecraft_version;
            const candidates = [];
            if (preferred && preferred !== 'auto') candidates.push(preferred);
            candidates.push('1.21.8');

            // Lazy import to avoid ESM/CJS conflicts
            const mod = await import('minecraft-assets');
            const mcAssetsFactory = mod.default || mod;

            for (const ver of candidates) {
                try {
                    const assets = mcAssetsFactory(ver);
                    // Prefer items path first, then blocks
                    const item = assets.items[itemName];
                    const block = assets.blocks[itemName];
                    const tex = assets.textureContent?.[itemName]?.texture
                        || (item ? assets.textureContent?.[itemName]?.texture : null)
                        || (block ? assets.textureContent?.[itemName]?.texture : null);
                    if (tex) {
                        // textureContent already provides a data URL in many versions
                        if (tex.startsWith('data:image')) {
                            const base64 = tex.split(',')[1];
                            const img = globalThis.Buffer.from(base64, 'base64');
                            res.setHeader('Content-Type', 'image/png');
                            return res.end(img);
                        }
                    }
                    // If textureContent missing, try static path resolution inside package
                    // Helps with some strange blocks like Leaf Litter
                    const guessPaths = [];
                    const base = assets.directory;
                    guessPaths.push(path.join(base, 'items', `${itemName}.png`));
                    guessPaths.push(path.join(base, 'blocks', `${itemName}.png`));
                    for (const p of guessPaths) {
                        try {
                            const fsMod = await import('fs');
                            const buf = fsMod.readFileSync(p);
                            res.setHeader('Content-Type', 'image/png');
                            return res.end(buf);
                        } catch { /* ignore */ }
                    }
                } catch { /* ignore */ }
            }
            // Not found, fallback svg
            res.setHeader('Content-Type', 'image/svg+xml');
            res.status(404).send('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="100%" height="100%" fill="#444"/><text x="50%" y="55%" font-size="12" fill="#bbb" text-anchor="middle">?</text></svg>');
        } catch (e) {
            res.setHeader('Content-Type', 'image/svg+xml');
            res.status(500).send('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="100%" height="100%" fill="#444"/><text x="50%" y="55%" font-size="12" fill="#bbb" text-anchor="middle">!</text></svg>');
        }
    });

    // Socket.io connection handling
    io.on('connection', (socket) => {
        let curAgentName = null;
        console.log('Client connected');

        agentsStatusUpdate(socket);

        socket.on('create-agent', async (settings, callback) => {
            console.log('API create agent...');
            for (let key in settings_spec) {
                if (!(key in settings)) {
                    if (settings_spec[key].required) {
                        callback({ success: false, error: `Setting ${key} is required` });
                        return;
                    }
                    else {
                        settings[key] = settings_spec[key].default;
                    }
                }
            }
            for (let key in settings) {
                if (!(key in settings_spec)) {
                    delete settings[key];
                }
            }
            if (settings.profile?.name) {
                settings = applyControlConfigToSettings(settings);
                if (settings.profile.name in agent_connections) {
                    callback({ success: false, error: 'Agent already exists' });
                    return;
                }
                try {
                    validateMinecraftLaunchConfig(settings);
                } catch (error) {
                    callback({ success: false, error: error.message });
                    return;
                }
                let returned = await mindcraft.createAgent(settings);
                callback({ success: returned.success, error: returned.error });
                let name = settings.profile.name;
                if (!returned.success && agent_connections[name]) {
                    mindcraft.destroyAgent(name);
                    delete agent_connections[name];
                }
                agentsStatusUpdate();
            }
            else {
                console.error('Agent name is required in profile');
                callback({ success: false, error: 'Agent name is required in profile' });
            }
        });

        socket.on('get-settings', (agentName, callback) => {
            if (agent_connections[agentName]) {
                callback({ settings: agent_connections[agentName].settings });
            } else {
                callback({ error: `Agent '${agentName}' not found.` });
            }
        });

        socket.on('connect-agent-process', (agentName) => {
            if (agent_connections[agentName]) {
                agent_connections[agentName].socket = socket;
                agentsStatusUpdate();
            }
        });

        socket.on('login-agent', (agentName) => {
            if (agent_connections[agentName]) {
                agent_connections[agentName].socket = socket;
                agent_connections[agentName].in_game = true;
                curAgentName = agentName;
                agentsStatusUpdate();
            }
            else {
                console.warn(`Unregistered agent ${agentName} tried to login`);
            }
        });

        socket.on('disconnect', () => {
            if (agent_connections[curAgentName]) {
                console.log(`Agent ${curAgentName} disconnected`);
                agent_connections[curAgentName].in_game = false;
                agent_connections[curAgentName].socket = null;
                agentsStatusUpdate();
            }
            if (agent_listeners.includes(socket)) {
                removeListener(socket);
            }
        });

        socket.on('chat-message', (agentName, json) => {
            if (!agent_connections[agentName]) {
                console.warn(`Agent ${agentName} tried to send a message but is not logged in`);
                return;
            }
            console.log(`${curAgentName} sending message to ${agentName}: ${json.message}`);
            agent_connections[agentName].socket.emit('chat-message', curAgentName, json);
        });

        socket.on('set-agent-settings', (agentName, settings) => {
            const agent = agent_connections[agentName];
            if (agent) {
                agent.setSettings(settings);
                agent.socket.emit('restart-agent');
            }
        });

        socket.on('restart-agent', (agentName) => {
            console.log(`Restarting agent: ${agentName}`);
            agent_connections[agentName].socket.emit('restart-agent');
        });

        socket.on('stop-agent', (agentName) => {
            mindcraft.stopAgent(agentName);
        });

        socket.on('start-agent', (agentName, callback) => {
            const conn = agent_connections[agentName];
            try {
                if (!conn) throw new Error(`Agent '${agentName}' not found.`);
                conn.settings = applyControlConfigToSettings(conn.settings);
                validateMinecraftLaunchConfig(conn.settings);
                const result = mindcraft.startAgent(agentName);
                if (callback) callback(result || { success: true });
            } catch (error) {
                const payload = { success: false, error: error.message };
                if (callback) callback(payload);
                else console.warn(payload.error);
            }
        });

        socket.on('destroy-agent', (agentName) => {
            if (agent_connections[agentName]) {
                mindcraft.destroyAgent(agentName);
                delete agent_connections[agentName];
            }
            agentsStatusUpdate();
        });

        socket.on('stop-all-agents', () => {
            stopAllAgents();
        });

        socket.on('shutdown', () => {
            console.log(`Shutting down via socket id=${socket.id} agent=${curAgentName || 'ui'} addr=${socket.handshake?.address || 'unknown'}`);
            stopAllAgents();
            setTimeout(() => {
                console.log('Exiting MindServer');
                globalThis.process.exit(0);
            }, 2000);
        });

		socket.on('send-message', (agentName, data) => {
			const result = sendMessageToAgent(agentName, data);
			if (!result.ok) {
				console.warn(result.error);
			}
		});

        socket.on('bot-output', (agentName, message) => {
            io.emit('bot-output', agentName, message);
        });

		socket.on('voice-log', (payload) => {
			io.emit('voice-log', payload);
		});

        socket.on('listen-to-agents', () => {
            addListener(socket);
        });
    });

    if (host_public) {
        console.log('Public hosting not supported yet. Using localhost.');
    }
    const host = 'localhost';
    server.listen(port, host, () => {
        console.log(`MindServer running on port ${port} on host ${host}`);
    });

    return server;
}

function agentsStatusUpdate(socket) {
    if (!socket) {
        socket = io;
    }
    let agents = [];
    for (let agentName in agent_connections) {
        const conn = agent_connections[agentName];
        agents.push({
            name: agentName, 
            in_game: conn.in_game,
            viewerPort: conn.viewer_port,
            socket_connected: !!conn.socket
        });
    };
    socket.emit('agents-status', agents);
}

function sendMessageToAgent(agentName, data) {
    if (!agent_connections[agentName]) {
        return { ok: false, error: `Agent ${agentName} not in game, cannot send message via MindServer.` };
    }
    try {
        agent_connections[agentName].socket.emit('send-message', data);
        return { ok: true };
    } catch (error) {
        console.error('Error: ', error);
        return { ok: false, error: String(error) };
    }
}


let listenerInterval = null;
function addListener(listener_socket) {
    agent_listeners.push(listener_socket);
    if (agent_listeners.length === 1) {
        listenerInterval = setInterval(async () => {
            const states = {};
            for (let agentName in agent_connections) {
                let agent = agent_connections[agentName];
                if (agent.in_game) {
                    try {
                        const state = await new Promise((resolve) => {
                            agent.socket.emit('get-full-state', (s) => resolve(s));
                        });
                        states[agentName] = state;
                    } catch (e) {
                        states[agentName] = { error: String(e) };
                    }
                }
            }
            for (let listener of agent_listeners) {
                listener.emit('state-update', states);
            }
        }, 1000);
    }
}

function removeListener(listener_socket) {
    agent_listeners.splice(agent_listeners.indexOf(listener_socket), 1);
    if (agent_listeners.length === 0) {
        clearInterval(listenerInterval);
        listenerInterval = null;
    }
}

// Optional: export these if you need access to them from other files
export const getIO = () => io;
export const getServer = () => server;
export const numStateListeners = () => agent_listeners.length;