import { History } from './history.js';
import { Coder } from './coder.js';
import { VisionInterpreter } from './vision/vision_interpreter.js';
import { Prompter } from '../models/prompter.js';
import { initModes } from './modes.js';
import { initBot } from '../utils/mcdata.js';
import { containsCommand, commandExists, executeCommand, truncCommandMessage, isAction, blacklistCommands } from './commands/index.js';
import { ActionManager } from './action_manager.js';
import { NPCContoller } from './npc/controller.js';
import { MemoryBank } from './memory_bank.js';
import { SelfPrompter } from './self_prompter.js';
import convoManager from './conversation.js';
import { handleTranslation, handleEnglishTranslation } from '../utils/translator.js';
import { addBrowserViewer } from './vision/browser_viewer.js';
import { serverProxy, sendOutputToServer, sendVoiceLogToServer } from './mindserver_proxy.js';
import settings from './settings.js';
import { Task } from './tasks/tasks.js';
import { speak } from './speak.js';
import { log, validateNameFormat, handleDisconnection, shouldRestartAfterDisconnect } from './connection_handler.js';
import { createSynaProbeClient } from './syna_probe_client.js';
import { extractVoiceText } from './voice_text.js';
import { DisconnectProbe } from './debug/disconnect_probe.js';
import { attachKnockbackSync } from './debug/knockback_sync.js';
import { attachIdleHeartbeat } from './debug/idle_heartbeat.js';
import { sendSynaVoice, interruptSynaVoice } from './syna_voice_client.js';
import { attachBlueprintAwareness } from './library/blueprint_awareness.js';
import { attachHorrorAwareness } from './library/horror_awareness.js';
import { attachSynaProactivity } from './library/syna_proactivity.js';
import { getModBlueprintClient } from './library/mod_blueprint_client.js';
import taskBoard from './task_board.js';
import { buildMcRunGoalPrompt, suggestMcRunAction } from './library/mc_run_planner.js';
import { routeIncomingMessage } from './message_router.js';
import * as world from './library/world.js';

// Voice focus heuristic: detect user tasks and store them in task_board.focus.
const FOCUS_VERBS = ['\u5efa', '\u9020', '\u76d6', '\u642d', '\u6316', '\u627e', '\u53bb', '\u7ed9', '\u505a', '\u62ff', '\u780d', '\u6536\u96c6', '\u5408\u6210', '\u5236\u4f5c', '\u524d\u5f80', '\u5bfb\u627e', '\u91c7', '\u9493', '\u79cd', '\u6740', '\u6253', '\u6361'];
const FOCUS_NEGATIVE = ['\u505c', '\u522b', '\u4e0d\u8981', '\u53d6\u6d88', '\u7b97\u4e86', '\u64a4', '\u4e0d\u7528', '\u4e0d\u505a', '\u522b\u505a', '\u5148\u4e0d\u505a', '\u5148\u522b\u505a', '\u4e0d\u7528\u505a', '\u522b\u7ba1', '\u522b\u5f04', '\u6682\u505c', '\u505c\u4e0b'];
const FOCUS_ASR_MISTAKE = ['\u542c\u9519', '\u8bc6\u522b\u9519', '\u8bc6\u522b\u9519\u4e86', '\u8bf4\u9519\u4e86', '\u4e0d\u662f\u8fd9\u4e2a', '\u4e0d\u662f\u8ba9\u4f60', '\u521a\u624d\u4e0d\u662f'];
const MC_RUN_QUERY_ONLY_COMMANDS = new Set(['!mcRunStatus', '!inventory', '!nearbyBlocks', '!entities', '!modFindBlock', '!modFindEntity', '!taskList', '!stats']);
const PICKUP_IMPORTANT_ITEMS = new Set(['bread', 'beef', 'cooked_beef', 'porkchop', 'cooked_porkchop', 'chicken', 'cooked_chicken', 'mutton', 'cooked_mutton', 'rabbit', 'cooked_rabbit', 'cod', 'cooked_cod', 'salmon', 'cooked_salmon', 'rotten_flesh', 'apple', 'golden_apple', 'potato', 'baked_potato', 'carrot', 'wheat', 'iron_ingot', 'raw_iron', 'diamond', 'obsidian', 'bucket', 'water_bucket', 'shield', 'wool', 'white_wool', 'black_wool', 'gray_wool', 'brown_wool', 'bed', 'white_bed', 'oak_log', 'birch_log', 'spruce_log', 'acacia_log', 'jungle_log', 'dark_oak_log', 'mangrove_log', 'cherry_log']);
function formatMessageForSyna(source, message, channel = 'chat', addressed = false) {
    const cleanSource = String(source || '').trim() || 'player';
    const cleanMessage = String(message || '').trim();
    if (!cleanMessage) return cleanMessage;
    if (source === 'system' || cleanSource === 'system') return cleanMessage;
    if (channel === 'voice' || cleanSource === 'SynaMic') {
        return `[Voice from ${cleanSource} to you] ${cleanMessage}`;
    }
    if (addressed) {
        return `[Player ${cleanSource} is talking to you] ${cleanMessage}`;
    }
    return `[Player ${cleanSource} said nearby] ${cleanMessage}`;
}
function wantsMcRunCommand(message) {
    const text = String(message || '').replace(/\s+/g, '').toLowerCase();
    if (!text) return false;
    if (/别通关|不要通关|停止通关|取消通关|先别通关|stopdragon|stoprun/.test(text)) return false;
    return /通关|末影龙|末地|下界|烈焰棒|末影之眼|dragon|enderdragon|completeminecraft|speedrun/.test(text);
}

function shouldStreamVoiceSentence(sentence, isSelfPromptActive = false) {
    const text = String(sentence || '').trim();
    if (!text) return false;
    if (/^\[THINK\]/i.test(text)) return false;
    if (/^\[SAY\]/i.test(text)) return true;
    // While an autonomous goal is active, only explicit [SAY] should be spoken.
    return !isSelfPromptActive;
}

function formatVoiceInputForChat(message) {
    let text = String(message || '').replace(/\s+/g, ' ').trim();
    if (!text) return '';
    if (text.length > 160) text = text.substring(0, 157) + '...';
    return '\u3010\u9EA6\u514B\u98CE\u3011' + text;
}
function estimateVoiceHoldMs(text, meta = {}) {
    const clean = String(text || '').replace(/\s+/g, ' ').trim();
    if (!clean) return 0;
    const cfg = settings.syna_voice || {};
    const perCharMs = Number(cfg.hold_ms_per_char ?? 500);
    const minMs = Number(cfg.hold_min_ms ?? 900);
    const maxMs = Number(cfg.hold_max_ms ?? 14000);
    const punctuationMs = (clean.match(/[。！？!?，,、；;]/g) || []).length * Number(cfg.hold_punctuation_ms ?? 180);
    const speed = Number.isFinite(meta.speed) && meta.speed > 0 ? meta.speed : 1;
    const raw = (clean.length * perCharMs + punctuationMs) / speed;
    return Math.max(minMs, Math.min(maxMs, raw));
}

function formatEntityForAwareness(entity) {
    const name = entity?.name || entity?.username || entity?.displayName || entity?.type || 'unknown entity';
    return String(name).replaceAll('_', ' ');
}

function systemStatusVoiceText(kind, detail = '') {
    const text = String(detail || '').trim();
    if (kind === 'spawned') return '\u0053\u0079\u006e\u0061 \u5df2\u8fdb\u5165 Minecraft \u4e16\u754c\u3002';
    if (kind === 'spawn_timeout') return '\u0053\u0079\u006e\u0061 \u8fdb\u5165\u4e16\u754c\u8d85\u65f6\uff0c\u6b63\u5728\u9000\u51fa\u3002';
    if (kind === 'death') return '\u7cdf\u7cd5\uff0cSyna \u6b7b\u4ea1\u4e86\u3002\u6211\u5df2\u7ecf\u8bb0\u5f55\u6b7b\u4ea1\u4f4d\u7f6e\uff0c\u51c6\u5907\u56de\u53bb\u6361\u6389\u843d\u7269\u3002';
    if (kind === 'respawn') return '\u0053\u0079\u006e\u0061 \u5df2\u7ecf\u91cd\u751f\u3002';
    if (kind === 'damage') return '\u0053\u0079\u006e\u0061 \u53d7\u4f24\u4e86\uff0c\u6b63\u5728\u5224\u65ad\u653b\u51fb\u6765\u6e90\u3002';
    if (kind === 'task_done') return text ? `\u4efb\u52a1\u7ed3\u675f\uff1a${text}` : '\u4efb\u52a1\u7ed3\u675f\u3002';
    if (kind === 'disconnect') return text ? `\u8fde\u63a5\u4e2d\u65ad\uff1a${text}` : '\u8fde\u63a5\u4e2d\u65ad\uff0cSyna \u6b63\u5728\u9000\u51fa\u3002';
    if (kind === 'missing_players') return text ? `\u7f3a\u5c11\u73a9\u5bb6\u6216\u673a\u5668\u4eba\uff1a${text}\u3002Syna \u5c06\u9000\u51fa\u3002` : '\u7f3a\u5c11\u5fc5\u8981\u73a9\u5bb6\u6216\u673a\u5668\u4eba\uff0cSyna \u5c06\u9000\u51fa\u3002';
    return text;
}
function findLikelyDamageAttacker(bot) {
    const selfName = String(bot?.username || '').toLowerCase();
    const selfPos = bot?.entity?.position;
    if (!selfPos) return null;

    let best = null;
    for (const entity of Object.values(bot.entities || {})) {
        const username = entity?.username || entity?.name;
        if (!username || String(username).toLowerCase() === selfName) continue;
        if (entity?.type !== 'player') continue;
        const pos = entity.position;
        if (!pos) continue;
        const distance = selfPos.distanceTo(pos);
        if (!Number.isFinite(distance)) continue;
        if (!best || distance < best.distance) {
            best = {
                name: username,
                distance,
                confidence: distance <= 4.25 ? 'high' : distance <= 8 ? 'medium' : 'low',
            };
        }
    }

    if (best && best.distance <= 10) return best;
    return null;
}
function blockAtOffset(bot, base, dx, dy, dz) {
    try {
        return bot.blockAt(base.offset(dx, dy, dz));
    } catch (_) {
        return null;
    }
}

function damageBlockName(block) {
    return String(block?.name || '').toLowerCase();
}

function isFallingOrSuffocatingBlockName(name) {
    return name === 'sand'
        || name === 'red_sand'
        || name === 'gravel'
        || name === 'powder_snow'
        || name.endsWith('_concrete_powder')
        || name.includes('quicksand');
}

function isSolidSuffocationBlock(block) {
    const name = damageBlockName(block);
    if (!name || name === 'air' || name === 'cave_air' || name === 'void_air') return false;
    if (name === 'water' || name === 'lava' || name.includes('fire')) return false;
    if (name.includes('grass') || name.includes('flower') || name.includes('torch')) return false;
    return block?.boundingBox === 'block' || isFallingOrSuffocatingBlockName(name);
}

function hostileEntityName(entity) {
    const name = String(entity?.name || entity?.username || entity?.displayName || '').toLowerCase();
    if (!name) return '';
    const hostiles = ['zombie', 'skeleton', 'creeper', 'spider', 'enderman', 'witch', 'slime', 'drowned', 'husk', 'stray', 'pillager', 'vindicator', 'evoker', 'ravager', 'phantom', 'blaze', 'ghast', 'magma_cube', 'guardian', 'warden', 'silverfish', 'endermite', 'shulker', 'piglin', 'hoglin', 'zoglin', 'wither'];
    return hostiles.find(h => name.includes(h)) || "";
}

function findNearbyHostileDamageSource(bot, radius = 5.5) {
    const selfPos = bot?.entity?.position;
    if (!selfPos) return null;
    let best = null;
    for (const entity of Object.values(bot.entities || {})) {
        if (!entity || entity === bot.entity || entity.type === 'player') continue;
        const hostile = hostileEntityName(entity);
        if (!hostile || !entity.position) continue;
        const distance = selfPos.distanceTo(entity.position);
        if (!Number.isFinite(distance) || distance > radius) continue;
        if (!best || distance < best.distance) {
            best = { name: entity.name || hostile, kind: "mob", cause: hostile, distance, confidence: distance <= 3.5 ? "high" : "medium" };
        }
    }
    return best;
}

function classifyLocalDamageEvidence(bot, disconnectProbe = null) {
    const pos = bot?.entity?.position;
    const evidence = { kind: 'unknown', cause: 'unknown', confidence: 'none', sourceName: 'unknown', details: [], nearestPlayer: null };
    if (!pos) return evidence;
    const recentPacket = disconnectProbe?._lastDamageCause && Date.now() - (disconnectProbe._lastDamageCause.t || 0) <= 1800 ? disconnectProbe._lastDamageCause : null;
    if (recentPacket?.cause) evidence.details.push('packet=' + recentPacket.cause);
    const samples = [['feet',0,0,0], ['head',0,1,0], ['above_head',0,2,0], ['below',0,-1,0], ['north',0,1,-1], ['south',0,1,1], ['east',1,1,0], ['west',-1,1,0]].map(([label, dx, dy, dz]) => ({ label, block: blockAtOffset(bot, pos, dx, dy, dz) }));
    const names = samples.map(s => [s.label, damageBlockName(s.block)]).filter(([, name]) => name);
    const matchName = (predicate) => names.find(([, name]) => predicate(name));
    const lava = matchName(name => name === 'lava');
    if (lava) return { ...evidence, kind: 'environment', cause: 'lava', confidence: 'high', details: [lava[0] + '=' + lava[1]] };
    const fire = matchName(name => name.includes('fire') || name === 'magma_block' || name === 'campfire' || name === 'soul_campfire');
    if (fire) return { ...evidence, kind: 'environment', cause: 'fire', confidence: 'high', details: [fire[0] + '=' + fire[1]] };
    const cactus = matchName(name => name === 'cactus' || name === 'sweet_berry_bush' || name === 'wither_rose');
    if (cactus) return { ...evidence, kind: 'environment', cause: cactus[1], confidence: 'high', details: [cactus[0] + '=' + cactus[1]] };
    const falling = matchName(name => isFallingOrSuffocatingBlockName(name));
    if (falling) return { ...evidence, kind: 'environment', cause: 'suffocation_' + falling[1], confidence: 'high', details: [falling[0] + '=' + falling[1]] };
    const head = samples.find(s => s.label === 'head')?.block;
    const feet = samples.find(s => s.label === 'feet')?.block;
    if (isSolidSuffocationBlock(head) || isSolidSuffocationBlock(feet)) {
        const block = isSolidSuffocationBlock(head) ? head : feet;
        return { ...evidence, kind: 'environment', cause: 'suffocation_' + damageBlockName(block), confidence: 'medium', details: ['inside=' + damageBlockName(block)] };
    }
    const water = matchName(name => name === 'water');
    if (water && (bot.oxygenLevel === 0 || bot.oxygenLevel < 6)) return { ...evidence, kind: 'environment', cause: 'drowning', confidence: 'high', details: [water[0] + '=' + water[1], 'oxygen=' + bot.oxygenLevel] };
    const mob = findNearbyHostileDamageSource(bot);
    if (mob && (recentPacket?.cause === 'entity' || mob.distance <= 4.2)) return { ...evidence, kind: 'mob', cause: mob.cause, confidence: mob.confidence, sourceName: mob.name, details: ['mob=' + mob.name + '@' + mob.distance.toFixed(1)] };
    const nearestPlayer = findLikelyDamageAttacker(bot);
    evidence.nearestPlayer = nearestPlayer;
    if (nearestPlayer && recentPacket?.cause === 'entity' && nearestPlayer.distance <= 3.4) return { ...evidence, kind: 'player', cause: 'melee', confidence: 'high', sourceName: nearestPlayer.name, nearestPlayer, details: ['player=' + nearestPlayer.name + '@' + nearestPlayer.distance.toFixed(1), 'packet=entity'] };
    if (nearestPlayer && !recentPacket && nearestPlayer.distance <= 2.6) return { ...evidence, kind: 'player', cause: 'possible_melee', confidence: 'medium', sourceName: nearestPlayer.name, nearestPlayer, details: ['player=' + nearestPlayer.name + '@' + nearestPlayer.distance.toFixed(1)] };
    if (recentPacket?.cause === 'env') return { ...evidence, kind: 'environment', cause: 'environmental_damage', confidence: 'medium', nearestPlayer, details: evidence.details };
    return evidence;
}
export function detectFocus(message) {
    if (!message || typeof message !== 'string') return null;
    const trimmed = message.trim();
    if (trimmed.length < 2 || trimmed.length > 200) return null;
    const normalized = trimmed.replace(/[\u3010\u3011\[\]\uFF08\uFF09()\uFF1A:\uFF0C\u3002\uFF01\uFF1F!?\u3001,.]/g, ' ');

    if (FOCUS_ASR_MISTAKE.some(flag => normalized.includes(flag))) return '__CLEAR__';
    if (/(\u5148)?(\u4e0d|\u522b|\u4e0d\u8981|\u4e0d\u7528)\s*(\u505a|\u5f04|\u641e|\u7ba1|\u5efa|\u9020|\u6316|\u627e|\u53bb|\u62ff|\u6536\u96c6|\u5408\u6210|\u5236\u4f5c|\u6253|\u6740)/.test(normalized)) return '__CLEAR__';
    for (const neg of FOCUS_NEGATIVE) {
        if (normalized.includes(neg)) return '__CLEAR__';
    }

    if (trimmed.includes('?') || trimmed.includes('\uFF1F')) return null;
    for (const v of FOCUS_VERBS) {
        if (trimmed.includes(v)) return trimmed;
    }
    return null;
}


export class Agent {
    async start(load_mem=false, init_message=null, count_id=0) {
        this.last_sender = null;
        this.count_id = count_id;
        this._disconnectHandled = false;

        // Initialize components
        this.actions = new ActionManager(this);
        this.prompter = new Prompter(this, settings.profile);
        this.name = (this.prompter.getName() || '').trim();
        console.log(`Initializing agent ${this.name}...`);
        
        // Validate Name Format
        // connection_handler now ensures the message has [LoginGuard] prefix
        const nameCheck = validateNameFormat(this.name);
        if (!nameCheck.success) {
            log(this.name, nameCheck.msg);
            process.exit(1);
            return;
        }
        
        this.history = new History(this);
        this.coder = new Coder(this);
        this.npc = new NPCContoller(this);
        this.memory_bank = new MemoryBank();
        this.self_prompter = new SelfPrompter(this);
        this.pending_resume_goal = null;
        this._voiceHoldUntil = 0;
        this._voiceBurstId = 0;
        this._recentCombatTargets = new Map();
        this._recentMcRunFailures = new Map();
        this._lastEntityDeathNoticeAt = 0;
        this._lastPickupNoticeAt = 0;
        this._lastPickupPromptAt = 0;
        this._lastInventoryFullNoticeAt = 0;
        this._recentItemSpawns = new Map();
        this._recentBrokenBlocks = [];
        this._lastInventoryCounts = null;
        this._playerAttackMemory = new Map();
        this._lastVoiceFeedbackAt = 0;
        this._lastSilentVoiceFeedbackAt = 0;
        this.syna_probe = createSynaProbeClient();
        this.disconnect_probe = new DisconnectProbe(this);
        convoManager.initAgent(this);
        await this.prompter.initExamples();

        // load mem first before doing task
        let save_data = null;
        if (load_mem) {
            save_data = this.history.load();
        }
        let taskStart = null;
        if (save_data) {
            taskStart = save_data.taskStart;
        } else {
            taskStart = Date.now();
        }
        this.task = new Task(this, settings.task, taskStart);
        this.blocked_actions = settings.blocked_actions.concat(this.task.blocked_actions || []);
        blacklistCommands(this.blocked_actions);

        console.log(this.name, 'logging into minecraft...');
        this.bot = initBot(this.name);
        
        // Connection Handler
        const onDisconnect = (event, reason) => {
            if (this._disconnectHandled) return;
            this._disconnectHandled = true;

            // 兜底 flush DisconnectProbe，保证 dump 一定落盘 (process.exit 是同步的)
            try { this.disconnect_probe?.flushOnExit?.(`${event}: ${reason}`); } catch (_) {}

            // Log and Analyze
            // handleDisconnection handles logging to console and server
            const { type } = handleDisconnection(this.name, reason);

            process.exit(shouldRestartAfterDisconnect(type) ? 1 : 10);
        };

        
        // Bind events
        this.bot.once('kicked', (reason) => onDisconnect('Kicked', reason));
        this.bot.once('end', (reason) => onDisconnect('Disconnected', reason));
        this.bot.on('error', (err) => {
            const errStr = String(err);
            if (errStr.includes('Duplicate') || errStr.includes('ECONNREFUSED')) {
                 onDisconnect('Error', err);
            } else if (errStr.includes('Read error') || errStr.includes('PartialReadError') || errStr.includes('Parse error for play.toClient') || errStr.includes('declare_commands')) {
                 // Forge mod packet parse errors (incl. declare_commands in modded servers) — non-fatal, suppress silently
                 console.warn(`[LoginGuard] Suppressed Forge parse error: ${errStr.substring(0, 120)}`);
            } else {
                 log(this.name, `[LoginGuard] Connection Error: ${errStr}`);
            }
        });

        this.bot.on('synaCombatTarget', (entity, reason) => {
            this._trackCombatTarget(entity, reason);
        });

        initModes(this);

        this.bot.on('login', () => {
            console.log(this.name, 'logged in!');
            serverProxy.login();
            
            // Set skin for profile, requires Fabric Tailor. (https://modrinth.com/mod/fabrictailor)
            if (this.prompter.profile.skin)
                this.bot.chat(`/skin set URL ${this.prompter.profile.skin.model} ${this.prompter.profile.skin.path}`);
            else
                this.bot.chat(`/skin clear`);
        });
		const spawnTimeoutDuration = settings.spawn_timeout;
        const spawnTimeout = setTimeout(() => {
            const msg = `Bot has not spawned after ${spawnTimeoutDuration} seconds. Exiting.`;
            this._speakSystemStatus('spawn_timeout', '', {interrupt: true}).catch(() => {});
            log(this.name, msg);
            process.exit(1);
        }, spawnTimeoutDuration * 1000);
        this.bot.once('spawn', async () => {
            try {
                try { this.disconnect_probe?.attach?.(); } catch (e) { console.warn('[DisconnectProbe] attach failed:', e?.message || e); }
                try { attachKnockbackSync(this); } catch (e) { console.warn('[knockbackSync] attach failed:', e?.message || e); }
                try { attachIdleHeartbeat(this); } catch (e) { console.warn('[idleHeartbeat] attach failed:', e?.message || e); }
                try { attachBlueprintAwareness(this); } catch (e) { console.warn('[BlueprintAwareness] attach failed:', e?.message || e); }
                try { attachHorrorAwareness(this); } catch (e) { console.warn('[HorrorAwareness] attach failed:', e?.message || e); }
                try { attachSynaProactivity(this); } catch (e) { console.warn('[SynaProactivity] attach failed:', e?.message || e); }
                clearTimeout(spawnTimeout);
                addBrowserViewer(this.bot, count_id);
                console.log('Initializing vision intepreter...');
                this.vision_interpreter = new VisionInterpreter(this, settings.allow_vision);

                // wait for a bit so stats are not undefined
                await new Promise((resolve) => setTimeout(resolve, 1000));

                // [FIX-P0] Wait for chunks to load before proceeding.
                // On Forge servers, forced spawn may fire before any chunk data arrives.
                // Without chunks, bot.findBlocks() returns nothing (nearbyBlocks = "none").
                // We poll bot.world.getColumns() or fallback to blockAt(bot.entity.position)
                // with a 15s timeout so we don't hang forever.
                const chunkWaitStart = Date.now();
                const CHUNK_WAIT_TIMEOUT = 15000;
                let chunksReady = false;
                while (Date.now() - chunkWaitStart < CHUNK_WAIT_TIMEOUT) {
                    // Check if we have any loaded chunk columns
                    const hasChunks = (() => {
                        try {
                            // mineflayer exposes bot.world; check if blockAt returns non-null for bot pos
                            if (this.bot.entity && this.bot.entity.position) {
                                const below = this.bot.blockAt(this.bot.entity.position.offset(0, -1, 0));
                                if (below && below.name && below.name !== 'air') return true;
                            }
                            // Also check if world has any column data at all
                            if (this.bot.world && typeof this.bot.world.getColumns === 'function') {
                                const cols = this.bot.world.getColumns();
                                if (cols && (Array.isArray(cols) ? cols.length > 0 : Object.keys(cols).length > 0)) return true;
                            }
                            // Fallback: try findBlocks for any non-air block nearby
                            const found = this.bot.findBlocks({ matching: (block) => block.type !== 0, maxDistance: 4, count: 1 });
                            if (found && found.length > 0) return true;
                        } catch (e) { /* ignore */ }
                        return false;
                    })();
                    if (hasChunks) { chunksReady = true; break; }
                    await new Promise(r => setTimeout(r, 500));
                }
                if (chunksReady) {
                    console.log(`[ChunkWait] Chunks loaded in ${Date.now() - chunkWaitStart}ms`);
                } else {
                    console.warn(`[ChunkWait] Timed out after ${CHUNK_WAIT_TIMEOUT}ms — chunks may not be loaded. Requesting chunk reload from synabridge...`);
                    // Ask the Forge mod to resend chunks for this bot
                    try {
                        const res = await fetch('http://127.0.0.1:8765/chunk_reload', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ player: this.name })
                        });
                        const data = await res.json();
                        if (data?.ok) {
                            console.log(`[ChunkWait] chunk_reload triggered, waiting 3s for chunks...`);
                            // Give server time to resend chunks
                            await new Promise(r => setTimeout(r, 3000));
                        } else {
                            console.warn(`[ChunkWait] chunk_reload response:`, data);
                        }
                    } catch (e) {
                        console.warn(`[ChunkWait] chunk_reload request failed (synabridge may not be running):`, e.message);
                    }
                }

                console.log(`${this.name} spawned.`);
                this.clearBotLogs();
                if (settings.syna_probe?.enabled && settings.syna_probe?.log_on_spawn) {
                    const health = await this.syna_probe.health();
                    if (health?.ok) {
                        console.log(`[SynaProbe] connected to ${this.syna_probe.baseUrl}:`, health);
                    } else {
                        console.warn(`[SynaProbe] not available at ${this.syna_probe.baseUrl}: ${this.syna_probe.lastError || 'unknown error'}. Mindflayer will continue normally.`);
                    }
                }
              
                this._setupEventHandlers(save_data, init_message);
                this.startEvents();
              
                if (!load_mem) {
                    if (settings.task) {
                        this.task.initBotTask();
                        this.task.setAgentGoal();
                    }
                } else {
                    // set the goal without initializing the rest of the task
                    if (settings.task) {
                        this.task.setAgentGoal();
                    }
                }

                await new Promise((resolve) => setTimeout(resolve, 10000));
                this.checkAllPlayersPresent();

            } catch (error) {
                console.error('Error in spawn event:', error);
                process.exit(0);
            }
        });
    }

    async _setupEventHandlers(save_data, init_message) {
        const ignore_messages = [
            "Set own game mode to",
            "Set the time to",
            "Set the difficulty to",
            "Teleported ",
            "Set the weather to",
            "Gamerule "
        ];
        
        const respondFunc = async (username, message, channel = 'chat') => {
            if (message === "") return;
            const route = routeIncomingMessage({
                source: username,
                message,
                botName: this.name,
                aliases: this.prompter?.profile?.chat_aliases || ['syna', 'Syna'],
                channel,
                onlyChatWith: settings.only_chat_with || [],
                allowPublicWithoutMention: Boolean(settings.chat_routing?.allow_public_without_mention),
            });
            if (!route.respond) {
                if (settings.chat_routing?.log_ignored) {
                    console.log(`[MessageRouter] ignored ${channel} from ${username}: ${route.reason} :: ${message}`);
                }
                return;
            }
            message = route.message || message;

            // Dedup: same (username, message) within 1s is a duplicate (whisper + chat double-fire)
            const dedupeKey = `${username}::${message}`;
            const now = Date.now();
            if (this._lastRespondMsg === dedupeKey && now - this._lastRespondTime < 1000) {
                return; // skip duplicate
            }
            this._lastRespondMsg = dedupeKey;
            this._lastRespondTime = now;

            try {
                if (ignore_messages.some((m) => message.startsWith(m))) return;

                // CROSSHAIR is sensor telemetry from SynaBridge mod — store silently, never trigger LLM or TTS interrupt
                const crosshairEarly = message.match(/\[CROSSHAIR\]\s+(\S+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)/);
                if (crosshairEarly) {
                    const [, blockName, x, y, z] = crosshairEarly;
                    this._playerCrosshair = { block: blockName, x: parseInt(x), y: parseInt(y), z: parseInt(z), time: Date.now() };
                    return; // Do NOT enter handleMessage — no interrupt, no LLM call
                }

                this.shut_up = false;

                console.log(this.name, 'received message from', username, ':', message);

                if (convoManager.isOtherAgent(username)) {
                    console.warn('received whisper from other bot??')
                }
                else {
                    let translation = await handleEnglishTranslation(message);
                    const routedMessage = formatMessageForSyna(username, translation, channel, route.addressed);
                    this.handleMessage(username, routedMessage);
                }
            } catch (error) {
                console.error('Error handling message:', error);
            }
        }

		this.respondFunc = respondFunc;

        this.bot.on('whisper', (username, message) => respondFunc(username, message, 'whisper'));
        
        this.bot.on('chat', (username, message) => {
            if (serverProxy.getNumOtherAgents() > 0) return;
            // only respond to open chat messages when there are no other agents
            respondFunc(username, message, 'chat');
        });

        // Set up auto-eat
        this.bot.autoEat.options = {
            priority: 'foodPoints',
            startAt: 14,
            bannedFood: ["rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken"]
        };

        // Anti-kick: pause pathfinder briefly when bot takes damage (knockback causes invalid movement packets)
        this.bot.on('entityHurt', (entity) => {
            if (entity === this.bot.entity) {
                try {
                    this.bot.pathfinder.stop();
                } catch (e) { /* ignore */ }
                // Resume pathfinder after knockback settles
                setTimeout(() => {
                    // pathfinder will be restarted by whatever action is running
                }, 600);
            }
        });

        const savedFocus = taskBoard.getFocus && taskBoard.getFocus();
        if (save_data?.self_prompt || savedFocus) {
            this.pending_resume_goal = {
                prompt: save_data?.self_prompt || '',
                state: save_data?.self_prompting_state,
                focus: savedFocus || null
            };
            if (savedFocus) {
                taskBoard.clearFocus();
            }
            if (init_message) {
                this.history.add('system', init_message);
            }
            this._offerPendingResumeGoal();
        }
        if (save_data?.last_sender) {
            this.last_sender = save_data.last_sender;
            if (convoManager.otherAgentInGame(this.last_sender)) {
                const msg_package = {
                    message: `You have restarted and this message is auto-generated. Continue the conversation with me.`,
                    start: true
                };
                convoManager.receiveFromBot(this.last_sender, msg_package);
            }
        }
        else if (init_message) {
            await this.handleMessage('system', init_message, 2);
        }
        else {
            this.openChat("Hello world! I am "+this.name);
        }

        // Auto-start self_prompter from profile config if not already active
        if (!this.pending_resume_goal && !this.self_prompter.isActive() && this.prompter?.profile?.self_prompting?.enabled && this.prompter.profile.self_prompting.auto_start_on_spawn === true) {
            const selfPromptText = this.prompter.profile.self_prompting.prompt;
            if (selfPromptText) {
                console.log('Auto-starting self-prompter from profile config.');
                setTimeout(() => {
                    if (!this.self_prompter.isActive()) {
                        this.self_prompter.start(selfPromptText);
                    }
                }, 5000); // delay 5s to let bot settle after spawn
            }
        }
    }

    checkAllPlayersPresent() {
        if (!this.task || !this.task.agent_names) {
          return;
        }

        const missingPlayers = this.task.agent_names.filter(name => !this.bot.players[name]);
        if (missingPlayers.length > 0) {
            console.log(`Missing players/bots: ${missingPlayers.join(', ')}`);
            this._speakSystemStatus('missing_players', missingPlayers.join('、'), {interrupt: true}).catch(() => {});
            this.cleanKill('Not all required players/bots are present in the world. Exiting.', 4);
        }
    }

    requestInterrupt() {
        this.bot.interrupt_code = true;
        this.bot.stopDigging();
        this.bot.collectBlock.cancelTask();
        this.bot.pathfinder.stop();
        this.bot.pvp.stop();
    }

    clearBotLogs() {
        this.bot.output = '';
        this.bot.interrupt_code = false;
    }

    shutUp() {
        this.shut_up = true;
        if (this.self_prompter.isActive()) {
            this.self_prompter.stop(false);
        }
        convoManager.endAllConversations();
    }


    _canSendSynaVoice(meta = {}) {
        if (meta.interrupt || meta.allowOverlap || meta.urgent || meta.burstId) return true;
        return Date.now() >= (this._voiceHoldUntil || 0);
    }

    _noteSynaVoiceSent(text, meta = {}) {
        if (!text) return;
        const holdMs = estimateVoiceHoldMs(text, meta);
        this._voiceHoldUntil = Math.max(this._voiceHoldUntil || 0, Date.now() + holdMs);
    }

    _voiceFeedbackText(result) {
        if (!result || !result.text) return '';
        const chars = result.text.length;
        if (result.spoken) {
            return `[发声状态] 上一条回复已发出声音，别人能听见；长度约 ${chars} 字。`;
        }
        if (result.silent) {
            return `[发声状态] 上一条回复没有发出声音，只能当作 Syna 的心里话；原因：${result.reason || '语音被节流或关闭'}。如果这句话只是看空气/自言自语，可以下次少说或不说。`;
        }
        return '';
    }

    _recordVoiceFeedback(result, {force = false} = {}) {
        const text = this._voiceFeedbackText(result);
        if (!text) return;
        const now = Date.now();
        const minGap = result?.silent ? 1200 : 4000;
        const lastAt = result?.silent ? this._lastSilentVoiceFeedbackAt : this._lastVoiceFeedbackAt;
        if (!force && now - (lastAt || 0) < minGap) return;
        if (result?.silent) this._lastSilentVoiceFeedbackAt = now;
        else this._lastVoiceFeedbackAt = now;
        this.history.add('system', text).catch(() => {});
    }

    async _sendSynaVoiceGuarded(text, meta = {}) {
        const clean = String(text || '').trim();
        if (!clean) return {spoken: false, silent: false, reason: 'empty', text: clean};
        if (!settings.syna_voice?.enabled) {
            return {spoken: false, silent: true, reason: '语音功能未开启', text: clean};
        }
        if (!this._canSendSynaVoice(meta)) {
            const left = Math.ceil(((this._voiceHoldUntil || 0) - Date.now()) / 1000);
            const reason = `上一句话估计还没说完，剩余约 ${Math.max(0, left)} 秒`;
            console.log(`[SynaVoiceGate] skipped TTS during hold window (${Math.max(0, left)}s left): ${clean.substring(0, 80)}`);
            return {spoken: false, silent: true, reason, text: clean};
        }
        const ok = await sendSynaVoice(clean, meta).catch(() => false);
        if (ok) {
            this._noteSynaVoiceSent(clean, meta);
            return {spoken: true, silent: false, reason: '', text: clean};
        }
        return {spoken: false, silent: true, reason: 'TTS 服务没有接收这句话', text: clean};
    }

    _trackCombatTarget(entity, reason = 'combat') {
        if (!entity || entity.id == null) return;
        this._recentCombatTargets.set(entity.id, {
            name: entity.name || entity.username || entity.displayName || entity.type || 'unknown entity',
            type: entity.type || '',
            reason,
            time: Date.now()
        });
        if (this._recentCombatTargets.size > 80) {
            const cutoff = Date.now() - 30000;
            for (const [id, item] of this._recentCombatTargets.entries()) {
                if ((item?.time || 0) < cutoff) this._recentCombatTargets.delete(id);
            }
        }
    }

    _noteInventoryFull(reason = 'inventory') {
        let emptySlots = 0;
        try { emptySlots = this.bot?.inventory?.emptySlotCount?.() ?? 0; }
        catch (_) { return; }
        if (emptySlots > 0) return;
        const now = Date.now();
        if (now - this._lastInventoryFullNoticeAt < 15000) return;
        this._lastInventoryFullNoticeAt = now;
        const counts = this._inventoryCountsSnapshot();
        const compact = Object.entries(counts)
            .sort((a, b) => String(a[0]).localeCompare(String(b[0])))
            .slice(0, 28)
            .map(([name, count]) => name + '=' + count)
            .join(', ');
        const msg = '[INVENTORY FULL] Your inventory has no empty slots (' + reason + '). Stop collecting/mining for a moment and clean inventory once. Use !discard(item_name, num) only for low-value junk such as dirt, cobblestone, gravel, sand, netherrack, rotten_flesh, seeds, flowers, excess saplings, or other clearly unnecessary clutter. Do NOT discard tools, weapons, armor, food, ores/ingots, diamonds, buckets, water_bucket, lava_bucket, obsidian, blaze_rods, ender_pearls, ender_eyes, beds, shield, crafting_table, furnace, or quest materials. Current inventory snapshot: ' + compact + '.';
        console.warn('[InventoryAwareness] full; reason=' + reason + '; ' + compact);
        this.history.add('system', msg).catch(() => {});
        this.handleMessage('system', msg, 1).catch(() => {});
    }
    _inventoryCountsSnapshot() {
        try { return world.getInventoryCounts(this.bot); }
        catch (_) { return {}; }
    }

    _diffInventoryCounts(before, after) {
        const gained = [];
        const keys = new Set([...Object.keys(before || {}), ...Object.keys(after || {})]);
        for (const key of keys) {
            const delta = Number(after?.[key] || 0) - Number(before?.[key] || 0);
            if (delta > 0) gained.push({ name: key, count: delta });
        }
        return gained.sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
    }

    _isImportantPickup(items) {
        return (items || []).some(item => PICKUP_IMPORTANT_ITEMS.has(String(item.name || '').replace(/^minecraft:/, '')));
    }

    _formatPickupItems(items) {
        return (items || []).map(item => String(item.count) + ' ' + String(item.name)).join(', ');
    }

    _noteInventorySnapshotSoon(delayMs = 600) {
        setTimeout(() => {
            this._lastInventoryCounts = this._inventoryCountsSnapshot();
        }, delayMs);
    }

    _nearestVisiblePlayerToPosition(position, maxDistance = 4) {
        if (!position) return null;
        let best = null;
        for (const entity of Object.values(this.bot?.entities || {})) {
            const username = entity?.username;
            if (!username || username === this.name || !entity.position) continue;
            const distance = position.distanceTo(entity.position);
            if (!Number.isFinite(distance) || distance > maxDistance) continue;
            if (!best || distance < best.distance) best = { name: username, distance };
        }
        return best;
    }

    _trackItemSpawn(entity) {
        if (!entity || entity.id == null || entity.name !== 'item' || !entity.position) return;
        const now = Date.now();
        const nearestPlayer = this._nearestVisiblePlayerToPosition(entity.position, 3.2);
        this._recentItemSpawns.set(entity.id, {
            time: now,
            position: entity.position.clone?.() || entity.position,
            nearestPlayer
        });
        const cutoff = now - 30000;
        for (const [id, item] of this._recentItemSpawns.entries()) {
            if ((item?.time || 0) < cutoff) this._recentItemSpawns.delete(id);
        }
    }

    _noteBlockBrokenForPickup(info) {
        const pos = info?.position;
        if (!pos) return;
        const now = Date.now();
        this._recentBrokenBlocks.push({
            name: String(info.name || 'block'),
            position: pos.clone?.() || pos,
            time: now
        });
        this._recentBrokenBlocks = this._recentBrokenBlocks.filter(item => now - item.time <= 12000).slice(-24);
    }

    _classifyPickupSource(collected) {
        const now = Date.now();
        const itemTrack = collected?.id != null ? this._recentItemSpawns.get(collected.id) : null;
        const pos = collected?.position || itemTrack?.position;
        if (pos) {
            let broken = null;
            for (const item of this._recentBrokenBlocks || []) {
                if (!item.position || now - item.time > 12000) continue;
                const distance = item.position.distanceTo(pos);
                if (Number.isFinite(distance) && distance <= 3.5 && (!broken || distance < broken.distance)) {
                    broken = { ...item, distance };
                }
            }
            if (broken) {
                return {
                    kind: 'mined_drop',
                    text: 'Likely from the ' + broken.name + ' block you just mined nearby.',
                    shouldPrompt: false
                };
            }
        }
        const nearbyPlayer = itemTrack?.nearestPlayer || this._nearestVisiblePlayerToPosition(pos, 3.2);
        if (nearbyPlayer && (!itemTrack || now - itemTrack.time <= 12000)) {
            return {
                kind: 'player_drop',
                text: 'Likely dropped or handed to you by player ' + nearbyPlayer.name + ' nearby (distance about ' + nearbyPlayer.distance.toFixed(1) + ' blocks; inferred from proximity, not exact server ownership).',
                shouldPrompt: true
            };
        }
        return { kind: 'ground_drop', text: 'Source looks like a ground/natural drop or unknown item entity.', shouldPrompt: false };
    }
    _droppedItemFromCollected(collected) {
        try {
            const item = typeof collected?.getDroppedItem === 'function' ? collected.getDroppedItem() : null;
            const name = item?.name || item?.displayName || '';
            const count = Number(item?.count || 0);
            if (name && count > 0) {
                return { name: String(name).replace(/^minecraft:/, '').replaceAll(' ', '_').toLowerCase(), count };
            }
        } catch (_) {}
        const fallbackName = collected?.name || collected?.displayName || '';
        if (fallbackName && !['item', 'object'].includes(String(fallbackName).toLowerCase())) {
            return { name: String(fallbackName).replace(/^minecraft:/, '').replaceAll(' ', '_').toLowerCase(), count: 1 };
        }
        return null;
    }

    _noteInventoryPickup(collector, collected, beforeCounts) {
        if (!collector || collector.username !== this.name) return;
        const eventBeforeCounts = beforeCounts || this._lastInventoryCounts || this._inventoryCountsSnapshot();
        const droppedItem = this._droppedItemFromCollected(collected);
        setTimeout(async () => {
            const afterCounts = this._inventoryCountsSnapshot();
            let gained = this._diffInventoryCounts(eventBeforeCounts || {}, afterCounts);
            if (!gained.length) {
                if (droppedItem) gained = [droppedItem];
            } else if (droppedItem && gained.every(item => item.name !== droppedItem.name)) {
                gained.unshift(droppedItem);
            }
            this._lastInventoryCounts = afterCounts;
            if (!gained.length) return;
            const now = Date.now();
            if (now - this._lastPickupNoticeAt < 250) return;
            this._lastPickupNoticeAt = now;
            const summary = this._formatPickupItems(gained);
            const important = this._isImportantPickup(gained);
            const source = this._classifyPickupSource(collected);
            const msg = '[PICKUP EVENT] You just picked up: ' + summary + '. Source: ' + source.text + ' Current food bar=' + Math.round(this.bot.food ?? 0) + '/20. Inventory changed; update your plan immediately. If this satisfies the food/material need, stop searching for that resource and move to the next MC_RUN requirement.';
            console.log('[PickupAwareness] ' + summary + '; source=' + source.kind + '; important=' + important);
            await this.history.add('system', msg).catch(() => {});
            if ((important || source.shouldPrompt) && now - this._lastPickupPromptAt >= 5000) {
                this._lastPickupPromptAt = now;
                this.handleMessage('system', msg, 1).catch(() => {});
            }
        }, 250);
    }

    _noteEntityDeath(entity) {
        if (!entity || entity === this.bot?.entity) return;
        const now = Date.now();
        const tracked = entity.id != null ? this._recentCombatTargets.get(entity.id) : null;
        const distance = this.bot?.entity?.position && entity.position ? this.bot.entity.position.distanceTo(entity.position) : Infinity;
        const recentTracked = tracked && now - tracked.time < 20000;
        const nearby = Number.isFinite(distance) && distance <= 8;
        if (!recentTracked && !nearby) return;
        if (now - this._lastEntityDeathNoticeAt < 1200) return;
        this._lastEntityDeathNoticeAt = now;
        if (entity.id != null) this._recentCombatTargets.delete(entity.id);
        const name = formatEntityForAwareness(entity);
        const distanceText = Number.isFinite(distance) ? `, 距离约 ${distance.toFixed(1)} 格` : '';
        const reasonText = recentTracked ? `，这是 Syna 刚才正在处理的目标` : '';
        this.history.add('system', `[实体死亡事件] 附近的 ${name} 刚刚死亡${distanceText}${reasonText}。如果玩家问它去哪了，不要说不知道；可以推断它已经被击杀或消失。`).catch(() => {});
    }

    async _getSynaVoiceMeta(base = {}) {
        const meta = { ...base };
        if (Number.isFinite(meta.speed)) {
            return meta;
        }
        try {
            const now = Date.now();
            if (!this._horrorVoiceCache || now - this._horrorVoiceCache.time > 1200) {
                const state = await getModBlueprintClient().state();
                this._horrorVoiceCache = { time: now, horror: state?.syna?.horror || null };
            }
            const horror = this._horrorVoiceCache?.horror;
            const stage = String(horror?.stage || 'calm');
            if (stage === 'warning') meta.speed = 0.88;
            else if (stage === 'storm') meta.speed = 0.78;
            else if (stage === 'countdown') meta.speed = 0.68;
            else if (stage === 'hunting') meta.speed = 1.32;
        } catch (_) {
            // Voice still works if the mod bridge is offline.
        }
        return meta;
    }
    async _speakSystemStatus(kind, detail = '', options = {}) {
        const cfg = settings.syna_voice || {};
        if (!cfg.enabled || cfg.system_status_tts === false) return;
        const text = systemStatusVoiceText(kind, detail);
        if (!text) return;
        try {
            const meta = await this._getSynaVoiceMeta({
                agent: this.name,
                source: options.source || 'system-status',
                interrupt: Boolean(options.interrupt),
                urgent: Boolean(options.urgent),
                allowOverlap: Boolean(options.allowOverlap),
                ...(Number.isFinite(options.speed) ? { speed: options.speed } : {})
            });
            this._sendSynaVoiceGuarded(text, meta).then(result => this._recordVoiceFeedback(result)).catch(() => {});
        } catch (_) {
            this._sendSynaVoiceGuarded(text, {
                agent: this.name,
                source: options.source || 'system-status',
                interrupt: Boolean(options.interrupt),
                urgent: Boolean(options.urgent),
                allowOverlap: Boolean(options.allowOverlap),
                ...(Number.isFinite(options.speed) ? { speed: options.speed } : {})
            }).then(result => this._recordVoiceFeedback(result)).catch(() => {});
        }
    }
    _describePendingResumeGoal() {
        const pending = this.pending_resume_goal;
        if (!pending) return '';
        const focusText = pending.focus?.text ? String(pending.focus.text).trim() : '';
        const promptText = pending.prompt ? String(pending.prompt).trim() : '';
        return focusText || promptText || '';
    }

    _offerPendingResumeGoal() {
        const text = this._describePendingResumeGoal();
        if (!text) return;
        const shortText = text.length > 120 ? text.substring(0, 117) + '...' : text;
        this.openChat(`[Syna] 上次还有一个任务：${shortText}。要继续吗？说“继续/接着”恢复，说“取消/不用/算了”丢弃。`);
    }

    async _handlePendingResumeReply(source, message) {
        if (!this.pending_resume_goal || source === 'system' || source === this.name || convoManager.isOtherAgent(source)) {
            return false;
        }
        const text = String(message || '').replace(/\s+/g, '').toLowerCase();
        if (!text) return false;
        const accepts = ['继续', '接着', '恢复', '继续吧', '继续做', '接着做', 'yes', 'y', 'ok'];
        const rejects = ['取消', '不用', '算了', '不要', '别继续', '不继续', '清掉', '忘掉', 'no', 'n'];
        if (rejects.some(word => text.includes(word))) {
            const old = this._describePendingResumeGoal();
            this.pending_resume_goal = null;
            taskBoard.clearFocus();
            this.routeResponse(source, `[Syna] 好，我不会继续上次的任务了。${old ? '已丢弃：' + old : ''}`);
            this.history.save();
            return true;
        }
        if (accepts.some(word => text.includes(word))) {
            const pending = this.pending_resume_goal;
            this.pending_resume_goal = null;
            const focusText = pending.focus?.text || pending.prompt || '';
            if (focusText) taskBoard.setFocus(focusText, pending.focus?.source || source);
            const prompt = pending.prompt || focusText;
            if (prompt) this.self_prompter.start(prompt);
            this.routeResponse(source, `[Syna] 好，我继续上次的任务：${focusText || prompt}`);
            this.history.save();
            return true;
        }
        return false;
    }
    _extractPlayerNameFromSource(source) {
        const raw = String(source || '').trim();
        if (!raw || raw === 'system' || raw === this.name || raw === 'SynaMic') {
            return this.last_sender || Object.keys(this.bot?.players || {}).find(name => name !== this.name) || '';
        }
        return raw;
    }

    _wantsComeHere(message) {
        const text = String(message || '').replace(/\s+/g, '').toLowerCase();
        return /过来|来我这|来我身边|到我这|跟我|跟着我|followme|comehere|cometome/.test(text);
    }

    _fallbackCommandForUserRequest(source, message, response) {
        if (containsCommand(response || '')) return null;
        if (!this._wantsComeHere(message)) return null;
        const playerName = this._extractPlayerNameFromSource(source);
        if (!playerName) return null;
        return `!goToPlayer("${playerName}", 3)`;
    }
    _formatCommandFeedback(commandName, executeRes) {
        const text = String(executeRes || '').trim();
        if (!text) return '';
        const lower = text.toLowerCase();
        const failed = /failed|error|invalid|does not exist|not found|cannot|can't|no response|timed out|aborted|失败|错误|找不到|不能|无法/.test(lower);
        if (commandName === '!setMode') {
            const match = text.match(/Mode\s+([^\s]+)\s+is\s+(?:now\s+)?(on|off|already\s+on|already\s+off)/i);
            if (match) {
                const mode = match[1];
                const state = match[2].includes('off') ? '关了' : '开了';
                return `确认，${mode} 模式${state}。`;
            }
        }
        if (commandName === '!synaHorror' || commandName === '!synaHorrorTakeover') return '恐怖控制已发送，我会按真实状态继续。';
        if (failed) return `执行没成功：${text.slice(0, 120)}`;
        if (/^MOD_(BLOCK|ENTITY)_SCAN/i.test(text)) return '我扫到了结果，正在按坐标判断下一步。';
        if (commandName === '!modFindBlock' || commandName === '!modFindEntity') return '我用 mod 扫描完了，结果已经进上下文。';
        if (commandName === '!stop') return '停下来了。';
        return '';
    }

    async handleMessage(source, message, max_responses=null, options = {}) {
        await this.checkTaskDone();
        if (!source || !message) {
            console.warn('Received empty message from', source);
            return false;
        }
        if (await this._handlePendingResumeReply(source, message)) return true;


        // Only interrupt TTS when the user is actually speaking via microphone (SynaMic source).
        // Other chat sources (in-game text, system, other bots) should NOT interrupt voice playback.
        // Headphone mode: always allow interrupt (no echo concern), even during streaming.
        if (source === 'SynaMic') {
            // Debounce: only interrupt if last interrupt was >600ms ago
            const now = Date.now();
            if (!this._lastInterruptAt || now - this._lastInterruptAt > 600) {
                this._lastInterruptAt = now;
                interruptSynaVoice().catch(() => {});
                // Soft interrupt: mark that we were interrupted so LLM gets context continuity
                if (this._streamingTTS) {
                    this._wasInterrupted = true;
                }
            }
        }

        if (source === 'SynaMic' && settings.syna_voice?.show_user_voice_in_chat !== false) {
            const voiceChatMessage = formatVoiceInputForChat(message);
            if (voiceChatMessage) {
                try {
                    this.bot.chat(voiceChatMessage);
                } catch (error) {
                    console.warn('[VoiceInputChat] failed:', error?.message || error);
                }
            }
        }
        let used_command = false;
        if (max_responses === null) {
            max_responses = settings.max_commands === -1 ? Infinity : settings.max_commands;
        }
        if (max_responses === -1) {
            max_responses = Infinity;
        }

        const self_prompt = source === 'system' || source === this.name;
        const from_other_bot = convoManager.isOtherAgent(source);
        try { this._noteSynaProactivityInput?.(source); } catch (_) {}

        // 用户口头任务自动入 task_board.focus（祈使句启发式）
        if (!self_prompt && !from_other_bot) {
            try {
                const f = detectFocus(message);
                if (f === '__CLEAR__') {
                    const cleared = taskBoard.clearFocus();
                    if (cleared) console.log(`[TaskBoard] focus cleared by user negation: "${cleared.text}"`);
                } else if (f) {
                    const newFocus = taskBoard.setFocus(f, source);
                    console.log(`[TaskBoard] focus set: "${newFocus.text}" (from ${source})`);
                }
            } catch (e) {
                console.warn('[TaskBoard] focus detect failed:', e?.message || e);
            }
        }

        if (!self_prompt && !from_other_bot && wantsMcRunCommand(message)) {
            const prompt = buildMcRunGoalPrompt(this);
            this.self_prompter.start(prompt);
            const suggested = suggestMcRunAction(this);
            await this.history.add('system', '[MC_RUN_USER_TRIGGER] 玩家要求通关我的世界。MC_RUN 已启动/唤醒。下一轮必须直接执行动作命令，不要只查询状态。建议动作：' + (suggested?.command || 'none') + '，原因：' + (suggested?.reason || 'none'));
            this.routeResponse(source, '[Syna] 通关循环已启动，我会直接推进下一步。');
            return true;
        }

        if (!self_prompt && !from_other_bot) { // from user, check for forced commands
            const user_command_name = containsCommand(message);

            if (user_command_name) {
                if (!commandExists(user_command_name)) {
                    this.routeResponse(source, `Command '${user_command_name}' does not exist.`);
                    return false;
                }
                this.routeResponse(source, `*${source} used ${user_command_name.substring(1)}*`);
                if (user_command_name === '!newAction') {
                    // all user-initiated commands are ignored by the bot except for this one
                    // add the preceding message to the history to give context for newAction
                    this.history.add(source, message);
                }
                let execute_res = await executeCommand(this, message);
                if (execute_res) 
                    this.routeResponse(source, execute_res);
                return true;
            }
        }

        if (from_other_bot)
            this.last_sender = source;

        // Now translate the message
        message = await handleEnglishTranslation(message);
        console.log('received message from', source, ':', message);

        const checkInterrupt = () => this.self_prompter.shouldInterrupt(self_prompt) || this.shut_up || convoManager.responseScheduledFor(source);
        
        let behavior_log = this.bot.modes.flushBehaviorLog().trim();
        if (behavior_log.length > 0) {
            const MAX_LOG = 500;
            if (behavior_log.length > MAX_LOG) {
                behavior_log = '...' + behavior_log.substring(behavior_log.length - MAX_LOG);
            }
            behavior_log = '最近的自动行为记录：\n' + behavior_log;
            await this.history.add('system', behavior_log);
        }

        // Handle other user messages
        await this.history.add(source, message);

        // When player speaks via mic, inject crosshair context so LLM knows what they're looking at
        if (source === 'SynaMic' && this._playerCrosshair) {
            const ch = this._playerCrosshair;
            const age = Date.now() - ch.time;
            if (age < 5000) { // only if data is fresh (within 5 seconds)
                await this.history.add('system', `[环境感知] 玩家当前准星指向的方块: ${ch.block}，坐标(${ch.x}, ${ch.y}, ${ch.z})。注意：这是玩家看着的位置，不是玩家自身的坐标。`);
            }
        }

        this.history.save();

        if (!self_prompt && this.self_prompter.isActive()) // message is from user during self-prompting
            max_responses = 1; // force only respond to this message, then let self-prompting take over

        // Soft interrupt context: if bot was interrupted mid-speech, tell LLM for continuity
        if (this._wasInterrupted) {
            this._wasInterrupted = false;
            await this.history.add('system', '[语音被打断] 你刚才说话时被用户打断了。请注意上下文连续性，不要重复已说过的内容，直接回应用户新的话。');
        }

        for (let i=0; i<max_responses; i++) {
            if (checkInterrupt()) break;
            await this.history.prepareForPrompt();
            let history = this.history.getHistory();
            const voiceMode = source === 'SynaMic';

            let res;
            if (voiceMode) {
                // Streaming path: send each sentence to TTS as it arrives
                // Set flag to suppress interrupt during streaming (prevents echo loop)
                this._streamingTTS = true;
                const voiceBurstId = ++this._voiceBurstId;
                let voiceSequence = 0;
                let voiceSendQueue = Promise.resolve();
                let streamedVoiceSent = false;
                try {
                    res = await this.prompter.promptConvoStreaming(history, async (sentence) => {
                        // Filter out command fragments before sending to TTS
                        const voiceChunk = shouldStreamVoiceSentence(sentence, this.self_prompter.isActive()) ? extractVoiceText(sentence) : '';
                        if (voiceChunk) {
                            // Chain immediately so async metadata lookup cannot reorder streamed sentences.
                            const sequence = ++voiceSequence;
                            streamedVoiceSent = true;
                            voiceSendQueue = voiceSendQueue
                                .then(async () => {
                                    const meta = await this._getSynaVoiceMeta({
                                        source: 'mindcraft',
                                        agent: this.name,
                                        burstId: voiceBurstId,
                                        sequence,
                                        interrupt: sequence === 1
                                    });
                                    return this._sendSynaVoiceGuarded(voiceChunk, meta);
                                })
                                .then(result => this._recordVoiceFeedback(result))
                                .catch(() => {});
                        }
                    });
                } finally {
                    await voiceSendQueue.catch(() => {});
                    this._streamingTTS = false;
                    this._streamedVoiceAlready = streamedVoiceSent;
                }
            } else {
                res = await this.prompter.promptConvo(history, {voiceMode: false});
            }

            console.log(`${this.name} full response to ${source}: ""${res}""`);

            if (res.trim().length === 0) {
                console.warn('no response')
                break; // empty response ends loop
            }

            const fallbackCommand = this._fallbackCommandForUserRequest(source, message, res);
            if (fallbackCommand) {
                console.warn(`[SynaFallback] model promised movement without command; injecting ${fallbackCommand}`);
                res = `${res.trim()}
${fallbackCommand}`;
            }

            let command_name = containsCommand(res);

            if (self_prompt && this.self_prompter?._isMcRunActive?.() && MC_RUN_QUERY_ONLY_COMMANDS.has(command_name)) {
                const suggested = suggestMcRunAction(this);
                if (suggested?.command) {
                    console.warn(`[MC_RUN] replacing query-only command ${command_name} with action ${suggested.command} (${suggested.reason})`);
                    res = suggested.command;
                    command_name = containsCommand(res);
                }
            }

            if (command_name) { // contains query or command
                res = truncCommandMessage(res); // everything after the command is ignored
                this.history.add(this.name, res);
                
                if (!commandExists(command_name)) {
                    this.history.add('system', `Command ${command_name} does not exist.`);
                    console.warn('Agent hallucinated command:', command_name)
                    continue;
                }

                if (checkInterrupt() || options.cancelToken?.cancelled) break;
                this.self_prompter.handleUserPromptedCmd(self_prompt, isAction(command_name));

                    if (settings.show_command_syntax === "full") {
                        this.routeResponse(source, res);
                    }
                    else if (settings.show_command_syntax === "shortened") {
                        // show only "used !commandname"
                        let pre_message = res.substring(0, res.indexOf(command_name)).trim();
                        let chat_message = `*used ${command_name.substring(1)}*`;
                        if (pre_message.length > 0)
                            chat_message = `${pre_message}  ${chat_message}`;
                        this.routeResponse(source, chat_message);
                    }
                    else {
                        // no command at all
                        let pre_message = res.substring(0, res.indexOf(command_name)).trim();
                        if (pre_message.trim().length > 0)
                            this.routeResponse(source, pre_message);
                    }
                

                if (options.cancelToken?.cancelled) {
                    console.warn(`[SynaStaleResponse] cancelled before executing ${command_name}`);
                    return false;
                }

                let execute_res = await executeCommand(this, res);

                console.log('Agent executed:', command_name, 'and got:', execute_res);
                const executeText = String(execute_res || '').toLowerCase();
                const commandFailed = /failed|error|invalid|does not exist|not found|could not find|cannot|can't|no response|timed out|aborted/.test(executeText);
                if (self_prompt && this.self_prompter?._isMcRunActive?.() && commandFailed) {
                    this._recentMcRunFailures?.set(command_name + ':' + res, { time: Date.now(), text: String(execute_res || '').slice(0, 240) });
                }
                used_command = true;

                if (execute_res) {
                    this.history.add('system', execute_res);
                    const feedback = this._formatCommandFeedback(command_name, execute_res);
                    if (feedback && !self_prompt) {
                        this.routeResponse(source, feedback);
                    }
                }
                else
                    break;

                await this.history.save?.();

                if (voiceMode && this.self_prompter.isActive()) {
                    break;
                }
            }
            else { // conversation response
                this.history.add(this.name, res);
                this.routeResponse(source, res);
                break;
            }
            
            this.history.save();
        }

        return used_command;
    }

    async routeResponse(to_player, message) {
        if (this.shut_up) return;
        let self_prompt = to_player === 'system' || to_player === this.name;
        if (self_prompt && this.last_sender) {
            // this is for when the agent is prompted by system while still in conversation
            // so it can respond to events like death but be routed back to the last sender
            to_player = this.last_sender;
        }

        if (convoManager.isOtherAgent(to_player) && convoManager.inConversation(to_player)) {
            // if we're in an ongoing conversation with the other bot, send the response to it
            convoManager.sendToBot(to_player, message);
        }
        else {
            // otherwise, use open chat
            this.openChat(message);
            // note that to_player could be another bot, but if we get here the conversation has ended
        }
    }

    async openChat(message) {
        // [SAY]/[THINK] tag control:
        // - [SAY] allows voice and strips the tag for display.
        // - [THINK] or self-prompt output without [SAY] stays silent but can still appear in MC chat/UI.
        let shouldVoice = true;
        const trimmedMessage = String(message || '').trimStart();
        if (trimmedMessage.startsWith('[SAY]')) {
            message = trimmedMessage.substring(5).trim();
            shouldVoice = true;
        } else if (trimmedMessage.startsWith('[THINK]')) {
            message = trimmedMessage.substring(7).trim();
            shouldVoice = false;
        } else if (this.self_prompter.isActive()) {
            // Self-prompt output without [SAY] = silent, no voice
            shouldVoice = false;
        }

        let to_translate = message;
        let remaining = '';
        let command_name = containsCommand(message);
        let translate_up_to = command_name ? message.indexOf(command_name) : -1;
        if (translate_up_to != -1) { // don't translate the command
            to_translate = to_translate.substring(0, translate_up_to);
            remaining = message.substring(translate_up_to);
        }
        message = (await handleTranslation(to_translate)).trim() + " " + remaining;
        // newlines are interpreted as separate chats, which triggers spam filters. replace them with spaces
        message = message.replaceAll('\n', ' ');

        if (settings.only_chat_with.length > 0) {
            for (let username of settings.only_chat_with) {
                this.bot.whisper(username, message);
            }
        }
        else {
            const voice_text = extractVoiceText(to_translate);
            if (settings.syna_voice?.log_voice_text && voice_text) {
                console.log(`[SynaVoiceText] ${voice_text}`);
            }
            if (shouldVoice && voice_text) {
                sendVoiceLogToServer(this.name, {
                    type: 'voice-text',
                    text: voice_text,
                    source: 'openChat'
                });
            }
            if (!shouldVoice && voice_text) {
                this._recordVoiceFeedback({
                    spoken: false,
                    silent: true,
                    reason: this.self_prompter.isActive() ? '当前是自我提示或思考输出' : '回复被标记为 THINK/静音',
                    text: voice_text
                });
            }
            if (shouldVoice && settings.syna_voice?.enabled && voice_text) {
                if (this._streamedVoiceAlready) {
                    // Already sent via streaming callback — skip duplicate
                    this._streamedVoiceAlready = false;
                } else {
                    this._getSynaVoiceMeta({agent: this.name, source: 'openChat'}).then(meta => this._sendSynaVoiceGuarded(voice_text, meta)).then(result => this._recordVoiceFeedback(result)).catch(() => {});
                }
            }
            if (shouldVoice && settings.speak) {
                speak(voice_text || to_translate, this.prompter.profile.speak_model);
            }
            if (settings.chat_ingame) {this.bot.chat(message);}
            sendOutputToServer(this.name, message);
        }
    }

    startEvents() {
        // Custom events
        this.bot.on('time', () => {
            if (this.bot.time.timeOfDay == 0)
            this.bot.emit('sunrise');
            else if (this.bot.time.timeOfDay == 6000)
            this.bot.emit('noon');
            else if (this.bot.time.timeOfDay == 12000)
            this.bot.emit('sunset');
            else if (this.bot.time.timeOfDay == 18000)
            this.bot.emit('midnight');
        });

        let prev_health = this.bot.health;
        this.bot.lastDamageTime = 0;
        this.bot.lastDamageTaken = 0;
        this._lastDamageAwarenessAt = 0;
        this._lastDamageAwarenessKey = '';
        this._lastDamageAwarenessAttacker = '';
        this._playerAttackMemory ||= new Map();
        this.bot.on('health', () => {
            if (this.bot.health < prev_health) {
                const now = Date.now();
                const drop = prev_health - this.bot.health;
                this.bot.lastDamageTime = now;
                this.bot.lastDamageTaken = drop;

                const evidence = classifyLocalDamageEvidence(this.bot, this.disconnect_probe);
                const likely = evidence.kind === 'player' ? evidence.nearestPlayer : null;
                const attackerName = evidence.kind === 'player' ? (evidence.sourceName || likely?.name || 'unknown') : 'unknown';
                const distanceText = likely ? likely.distance.toFixed(1) + ' blocks' : 'unknown distance';
                const sourceKey = evidence.kind + ':' + evidence.cause + ':' + attackerName;
                const awarenessKey = sourceKey + ':' + Math.floor(now / 12000);
                console.warn('[DamageAwareness] ' + this.name + ' lost ' + drop.toFixed(1) + ' HP (' + prev_health.toFixed(1) + ' -> ' + this.bot.health.toFixed(1) + '); source=' + evidence.kind + '/' + evidence.cause + '; attacker=' + attackerName + '; confidence=' + evidence.confidence + '; details=' + (evidence.details || []).join(','));

                const newAttackWindow = now - this._lastDamageAwarenessAt >= 12000;
                const changedAttacker = sourceKey !== this._lastDamageAwarenessAttacker;
                const urgentDamage = this.bot.health <= 4 || drop >= 6;
                let combo = 1;
                let anger = 0;
                if (evidence.kind === 'player' && attackerName !== 'unknown') {
                    const remembered = this._playerAttackMemory.get(attackerName) || { count: 0, anger: 0, firstAt: now, lastAt: 0 };
                    combo = now - remembered.lastAt <= 45000 ? remembered.count + 1 : 1;
                    const baseAnger = 18;
                    const angerGain = baseAnger + Math.ceil(drop * 5) + (combo >= 3 ? 18 : 0) + (urgentDamage ? 35 : 0);
                    anger = Math.min(240, (now - remembered.lastAt <= 90000 ? remembered.anger : 0) + angerGain);
                    this._playerAttackMemory.set(attackerName, { count: combo, anger, firstAt: remembered.firstAt || now, lastAt: now });
                }
                if (this._playerAttackMemory.size > 20) {
                    for (const [name, item] of this._playerAttackMemory.entries()) {
                        if (now - (item?.lastAt || 0) > 180000) this._playerAttackMemory.delete(name);
                    }
                }
                const shouldNotify = newAttackWindow || changedAttacker || urgentDamage || (evidence.kind === 'player' && combo >= 2);
                if (shouldNotify) {
                    this._speakSystemStatus('damage', '', {source: 'damage-status', speed: 1.08, urgent: true}).catch(() => {});
                }
                if (shouldNotify) {
                    this._lastDamageAwarenessAt = now;
                    this._lastDamageAwarenessKey = awarenessKey;
                    this._lastDamageAwarenessAttacker = sourceKey;
                    const nearbyPlayerText = evidence.nearestPlayer
                        ? ' Nearby player for context only: ' + String(evidence.nearestPlayer.name) + ' at ' + evidence.nearestPlayer.distance.toFixed(1) + ' blocks.'
                        : '';
                    const attackerClause = evidence.kind === 'player'
                        ? String(attackerName) + ' is the high-confidence player attacker (' + distanceText + ', ' + evidence.confidence + ').'
                        : evidence.kind === 'mob'
                            ? 'Damage source is probably nearby mob ' + String(evidence.sourceName || evidence.cause) + ' (' + evidence.confidence + ' confidence). Do not blame the player unless a later event explicitly says a player attacked you.'
                            : evidence.kind === 'environment'
                                ? 'Damage source is probably environment: ' + String(evidence.cause) + ' (' + evidence.confidence + ' confidence). Do not blame the nearby player for this.'
                                : 'Damage source is unknown. Do not assume the nearest player attacked you; ask or infer cautiously.';
                    const escalationHint = evidence.kind === 'player'
                        ? (anger >= 190 || (combo >= 5 && anger >= 160) || this.bot.health <= 4
                            ? 'This now looks deliberate or dangerous: you may set an anger key and then use !synaHorrorTakeover("countdown" or "takeover", target, anger), but do not jump straight to hunting unless the player keeps attacking.'
                            : anger >= 95 || combo >= 3
                                ? 'Build tension first: use !synaHorror("key", target, anger, "reason") or warn/countdown if it continues. Do not summon Horror Syna yet from a few weak hits.'
                                : 'Treat this as possibly accidental or playful: complain, warn, or ask why, but do not summon Horror Syna yet.')
                        : 'React to the pain and the danger, but do not increase player-attack anger and do not target the player for Horror Syna from this event.';
                    const targetHint = evidence.kind === 'player' ? 'target=' + String(attackerName).replace(/"/g, '') : 'target=none';
                    const details = (evidence.details || []).length ? ' Evidence=' + evidence.details.join(', ') + '.' : '';
                    const msg = '[DAMAGE EVENT] Your body just took ' + drop.toFixed(1) + ' HP damage (' + prev_health.toFixed(1) + ' -> ' + this.bot.health.toFixed(1) + '). ' + attackerClause + nearbyPlayerText + details + ' Recent confirmed player attacks from this target=' + combo + ', accumulated_anger=' + anger + '/240, ' + targetHint + '. ' + escalationHint;
                    setTimeout(() => {
                        this.handleMessage('system', msg, 1).catch(() => {});
                    }, 0);
                }
            }
            prev_health = this.bot.health;
        });
        // Logging callbacks
        this.bot.on('error' , (err) => {
            console.error('Error event!', err);
        });
        // Use connection handler for runtime disconnects
        this.bot.on('end', (reason) => {
            if (this._isDead) {
                // Death-triggered disconnect: don't exit, bot will respawn
                console.log(this.name, 'connection ended during death, waiting for respawn...');
                return;
            }
            if (!this._disconnectHandled) {
                const { type, msg } = handleDisconnection(this.name, reason);
                this.cleanKill(msg, shouldRestartAfterDisconnect(type) ? 1 : 10);
            }
        });
        this.bot.on('death', () => {
            console.log(this.name, 'died, waiting for respawn...');
            this._speakSystemStatus('death', '', {interrupt: true}).catch(() => {});
            this._isDead = true;
            this.actions.cancelResume();
            this.actions.stop();
        });
        this.bot.on('respawn', () => {
            console.log(this.name, 'respawned successfully.');
            this._speakSystemStatus('respawn').catch(() => {});
            this._isDead = false;
        });
        this.bot.on('kicked', (reason) => {
            if (!this._disconnectHandled) {
                const { type, msg } = handleDisconnection(this.name, reason);
                this.cleanKill(msg, shouldRestartAfterDisconnect(type) ? 1 : 10);
            }
        });

        this.bot.on('entityDead', (entity) => {
            this._noteEntityDeath(entity);
        });
        this.bot.on('entitySpawn', (entity) => {
            this._trackItemSpawn(entity);
        });
        this.bot.on('synaBlockBroken', (info) => {
            this._noteBlockBrokenForPickup(info);
        });
        this.bot.on('synaInventoryFull', (info = {}) => {
            this._noteInventoryFull(info.reason || 'action');
        });
        this._lastInventoryCounts = this._inventoryCountsSnapshot();
        this.bot.inventory?.on?.('updateSlot', () => {
            this._noteInventorySnapshotSoon();
            setTimeout(() => this._noteInventoryFull('inventory_update'), 700);
        });
        this.bot.on('playerCollect', (collector, collected) => {
            const beforeCounts = this._lastInventoryCounts || this._inventoryCountsSnapshot();
            this._noteInventoryPickup(collector, collected, beforeCounts);
        });
        this.bot.on('messagestr', async (message, _, jsonMsg) => {
            // --- Crosshair broadcast parser (from SynaBridge mod) ---
            const crosshairMatch = message.match(/\[CROSSHAIR\]\s+(\S+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)/);
            if (crosshairMatch) {
                const [, blockName, x, y, z] = crosshairMatch;
                this._playerCrosshair = { block: blockName, x: parseInt(x), y: parseInt(y), z: parseInt(z), time: Date.now() };
                return; // Don't process further, this is internal telemetry
            }

            // --- Death handler ---
            if (jsonMsg.translate && jsonMsg.translate.startsWith('death') && message.startsWith(this.name)) {
                console.log('Agent died: ', message);
                let death_pos = this.bot.entity.position;
                this.memory_bank.rememberPlace('last_death_position', death_pos.x, death_pos.y, death_pos.z);
                let death_pos_text = null;
                if (death_pos) {
                    death_pos_text = `x: ${death_pos.x.toFixed(2)}, y: ${death_pos.y.toFixed(2)}, z: ${death_pos.z.toFixed(2)}`;
                }
                let dimention = this.bot.game.dimension;
                this.handleMessage('system', `You died at position ${death_pos_text || "unknown"} in the ${dimention} dimension with the final message: '${message}'. Your place of death is saved as 'last_death_position' if you want to return. Previous actions were stopped and you have respawned. IMPORTANT: Go back to your death position immediately to pick up your dropped items before they despawn (5 min timer)!`);
            }
        });
        this.bot.on('idle', () => {
            this.bot.clearControlStates();
            this.bot.pathfinder.stop(); // clear any lingering pathfinder
            this.bot.modes.unPauseAll();
            setTimeout(() => {
                if (this.isIdle()) {
                    this.actions.resumeAction();
                }
            }, 1000);
        });

        // Init NPC controller
        this.npc.init();

        // This update loop ensures that each update() is called one at a time, even if it takes longer than the interval
        const INTERVAL = 300;
        let last = Date.now();
        setTimeout(async () => {
            while (true) {
                let start = Date.now();
                await this.update(start - last);
                let remaining = INTERVAL - (Date.now() - start);
                if (remaining > 0) {
                    await new Promise((resolve) => setTimeout(resolve, remaining));
                }
                last = start;
            }
        }, INTERVAL);

        this.bot.emit('idle');
    }

    async update(delta) {
        await this.bot.modes.update();
        this.self_prompter.update(delta);
        await this.checkTaskDone();
    }

    isIdle() {
        return !this.actions.executing;
    }
    

    cleanKill(msg='Killing agent process...', code=1) {
        // 兜底 flush DisconnectProbe，保证 dump 一定落盘 (process.exit 是同步的)
        try { this.disconnect_probe?.flushOnExit?.(`cleanKill: ${msg}`); } catch (_) {}
        this.history.add('system', msg);
        this._speakSystemStatus('disconnect', msg, {interrupt: true}).catch(() => {});
        this.bot.chat(code > 1 ? 'Restarting.': 'Exiting.');
        this.history.save();
        process.exit(code);
    }

    async checkTaskDone() {
        if (this.task.data) {
            let res = this.task.isDone();
            if (res) {
                await this.history.add('system', `Task ended with score : ${res.score}`);
                await this.history.save();
                // await new Promise(resolve => setTimeout(resolve, 3000)); // Wait 3 second for save to complete
                console.log('Task finished:', res.message);
                this._speakSystemStatus('task_done', res.message, {interrupt: true}).catch(() => {});
                this.killAll();
            }
        }
    }

    killAll() {
        serverProxy.shutdown();
    }
}

