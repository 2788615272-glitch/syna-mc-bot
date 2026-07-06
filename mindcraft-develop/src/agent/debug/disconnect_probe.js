// 掉线探针 - 60s 环形 buffer + dump json + 人话摘要 txt + 幸存对照组
// dump 路径: bots/{name}/被攻击掉线日志/{时间戳}.json + 最新.json + 最新_摘要.txt
import fs from 'fs';
import path from 'path';
import settings from '../settings.js';

function nowMs() { return Date.now(); }
function nowIsoSafe() {
    return new Date().toISOString().replace(/[:.]/g, '-');
}

export class DisconnectProbe {
    constructor(agent) {
        this.agent = agent;
        const cfg = settings.disconnect_probe || {};
        this.enabled = cfg.enabled !== false;
        this.ringSeconds = cfg.ring_seconds ?? 60;
        this.frameIntervalMs = cfg.frame_interval_ms ?? 250;
        this.dumpPackets = cfg.dump_packets !== false;
        this.maxPackets = cfg.max_packets ?? 200;
        this.survivorThreshold = cfg.survivor_hp_drop ?? 4;
        this.hpConsoleLog = cfg.hp_console_log !== false;

        this.frames = [];
        this.events = [];
        this.packetsTail = [];
        this.dumped = false;
        this._lastSurvivorDumpAt = 0;
        this._frameTimer = null;
        this._lastDamageCause = null; // {cause, sourceTypeId, sourceCauseId, t}
        this._lastDamageEventPkt = null;
        this._lastUpdateHealthPkt = null;
        this._lastKeepAlivePkt = null;     // {t, data}
        this._lastSelfVelPkt = null;       // {t, data}  指向 bot.entity.id 的 entity_velocity
        this._lastSelfExplosionPkt = null; // {t, data}  bot 受到 explosion 推动
        this._attached = false;


        this.dir = path.join('bots', agent.name, '被攻击掉线日志');
        try { fs.mkdirSync(this.dir, { recursive: true }); } catch (_) {}
    }

    attach() {
        if (!this.enabled) return;
        if (this._attached) return;
        const bot = this.agent.bot;
        if (!bot) return;
        this._attached = true;
        console.log(`[DisconnectProbe] attached (frame=${this.frameIntervalMs}ms, ring=${this.ringSeconds}s, hpLog=${this.hpConsoleLog})`);

        // 帧采样
        this._frameTimer = setInterval(() => this._sampleFrame(), this.frameIntervalMs);


        bot.on('entityHurt', (entity) => {
            if (entity === bot.entity) {
                const last = bot.lastDamageTaken || 0;
                this._pushEvent({
                    type: 'self_hurt',
                    amount: last,
                    hpAfter: bot.health,
                });
            } else if (entity?.username || entity?.name) {
                // 别人挨打，不记
            }
        });

        let prevHp = bot.health;
        bot.on('health', () => {
            const drop = prevHp - bot.health;
            if (drop > 0) {
                this._pushEvent({ type: 'health_drop', drop, hp: bot.health, food: bot.food, cause: this._lastDamageCause });
                if (this.hpConsoleLog) {
                    try {
                        const pos = bot.entity?.position;
                        const vel = bot.entity?.velocity;
                        const horizV = vel ? Math.hypot(vel.x || 0, vel.z || 0) : 0;
                        let pf = false;
                        try { pf = !!bot.pathfinder?.isMoving?.(); } catch (_) {}
                        const cause = this._lastDamageCause;
                        const causeStr = cause ? ` from ${cause.cause || 'unknown'}${cause.sourceTypeId != null ? '#' + cause.sourceTypeId : ''}` : '';
                        console.log(`[hp] -${drop.toFixed(1)}${causeStr} hp=${bot.health.toFixed(1)} food=${bot.food} onGround=${bot.entity?.onGround} pf=${pf} vH=${horizV.toFixed(2)} vY=${(vel?.y ?? 0).toFixed(2)} pos=(${pos?.x?.toFixed?.(1)},${pos?.y?.toFixed?.(1)},${pos?.z?.toFixed?.(1)})`);
                    } catch (_) {}
                }
                if (drop >= this.survivorThreshold && bot.health > 0) {

                    // 幸存对照组：挨了重击但还没掉线
                    const since = nowMs() - this._lastSurvivorDumpAt;
                    if (since > 5000) {
                        this._lastSurvivorDumpAt = nowMs();
                        // 延迟 1.5s 再 dump，看后续是否真的掉线了；如果中间已经 dumped 就跳过
                        const snapshotEvents = this.events.slice();
                        setTimeout(() => {
                            if (!this.dumped) {
                                this._dump('幸存_', '挨重击但未掉线', { lastDrop: drop, snapshotEvents });
                            }
                        }, 1500);
                    }
                }
            }
            prevHp = bot.health;
        });

        bot.on('forcedMove', () => {
            this._pushEvent({
                type: 'forced_move',
                pos: this._serPos(bot.entity?.position),
                vel: this._serPos(bot.entity?.velocity),
            });
        });

        bot.on('error', (err) => {
            this._pushEvent({ type: 'error', msg: String(err) });
        });

        bot.once('kicked', (reason) => {
            this._pushEvent({ type: 'kicked', raw: this._stringifyReason(reason) });
            this._dump('掉线_', '被踢出 (kicked)', { reason: this._stringifyReason(reason) });
        });
        bot.once('end', (reason) => {
            this._pushEvent({ type: 'end', raw: this._stringifyReason(reason) });
            // end 可能在 kicked 之后；dumped 标志已经避免重复
            this._dump('掉线_', '连接断开 (end)', { reason: this._stringifyReason(reason) });
        });

        // 网络层 packet 抓尾巴
        if (this.dumpPackets && bot._client && bot._client.on) {
            const watch = new Set([
                'position', 'position_look', 'look', 'explosion', 'entity_velocity',
                'disconnect', 'kick_disconnect',
                'damage_event', 'update_health', 'entity_status',
                'hurt_animation', 'set_health', 'player_position',
                'keep_alive',  // 用于诊断 keep_alive 超时
            ]);
            bot._client.on('packet', (data, meta) => {
                try {
                    if (!watch.has(meta?.name)) return;
                    // 记录最近一次 damage_event，给 [hp] 日志当 cause
                    if (meta.name === 'damage_event') {
                        this._lastDamageEventPkt = { t: nowMs(), data };
                        const cause = this._classifyDamageCause(data);
                        this._lastDamageCause = { ...cause, t: nowMs() };
                    }
                    if (meta.name === 'update_health' || meta.name === 'set_health') {
                        this._lastUpdateHealthPkt = { t: nowMs(), data };
                    }
                    if (meta.name === 'keep_alive') {
                        this._lastKeepAlivePkt = { t: nowMs(), data };
                    }
                    if (meta.name === 'entity_velocity') {
                        const myId = bot.entity?.id;
                        if (myId != null && data?.entityId === myId) {
                            this._lastSelfVelPkt = { t: nowMs(), data };
                        }
                    }
                    if (meta.name === 'explosion') {
                        this._lastSelfExplosionPkt = { t: nowMs(), data };
                    }
                    this.packetsTail.push({
                        t: nowMs(),
                        name: meta.name,
                        data: this._slimPacket(meta.name, data),
                    });
                    if (this.packetsTail.length > this.maxPackets) {
                        this.packetsTail.shift();
                    }
                } catch (_) {}
            });
        }
    }

    _classifyDamageCause(data) {
        // damage_event 字段名因协议版本而异，最大化兜底
        const out = {
            cause: null,
            sourceTypeId: data?.sourceTypeId ?? data?.sourceType ?? null,
            sourceCauseId: data?.sourceCauseId ?? null,
            sourceDirectId: data?.sourceDirectId ?? null,
            entityId: data?.entityId ?? null,
        };
        // sourceTypeId 是 mc registry 的 damage_type 索引，无法离线翻译，但相对 ID 可对照
        // 用启发式：有 sourceCauseId/sourceDirectId 就大概率是实体伤害
        if (out.sourceCauseId || out.sourceDirectId) {
            out.cause = 'entity';
        } else if (out.sourceTypeId != null) {
            out.cause = 'env'; // 摔/淹/火/仙人掌/虚空 等
        }
        return out;
    }


    _slimPacket(name, data) {
        if (!data) return data;
        if (name === 'position' || name === 'position_look') {
            return { x: data.x, y: data.y, z: data.z, yaw: data.yaw, pitch: data.pitch, onGround: data.onGround };
        }
        if (name === 'explosion') {
            return { x: data.x, y: data.y, z: data.z, radius: data.radius, playerVelX: data.playerMotionX, playerVelY: data.playerMotionY, playerVelZ: data.playerMotionZ };
        }
        if (name === 'entity_velocity') {
            return { entityId: data.entityId, vx: data.velocityX, vy: data.velocityY, vz: data.velocityZ };
        }
        if (name === 'disconnect' || name === 'kick_disconnect') {
            return { reason: this._stringifyReason(data.reason || data) };
        }
        return data;
    }

    _serPos(p) {
        if (!p) return null;
        return { x: +p.x?.toFixed?.(2), y: +p.y?.toFixed?.(2), z: +p.z?.toFixed?.(2) };
    }

    _stringifyReason(r) {
        if (r == null) return '';
        if (typeof r === 'string') return r;
        try { return JSON.stringify(r); } catch (_) { return String(r); }
    }

    _sampleFrame() {
        const bot = this.agent.bot;
        if (!bot || !bot.entity) return;
        const cap = Math.ceil((this.ringSeconds * 1000) / this.frameIntervalMs);
        let pf = false;
        try { pf = !!bot.pathfinder?.isMoving?.(); } catch (_) {}
        const ctrl = bot.controlState ? Object.assign({}, bot.controlState) : null;
        this.frames.push({
            t: nowMs(),
            hp: bot.health,
            food: bot.food,
            pos: this._serPos(bot.entity.position),
            vel: this._serPos(bot.entity.velocity),
            yaw: +bot.entity.yaw?.toFixed?.(2),
            pitch: +bot.entity.pitch?.toFixed?.(2),
            onGround: bot.entity.onGround,
            inWater: bot.entity.isInWater,
            pf,
            ctrl,
        });
        while (this.frames.length > cap) this.frames.shift();
    }

    _pushEvent(ev) {
        ev.t = nowMs();
        this.events.push(ev);
        // 事件保 200 条上限即可
        while (this.events.length > 200) this.events.shift();
    }

    _diagnose(payload) {
        const lines = [];
        const reason = String(payload.reason || payload.note || '');
        const evs = this.events.slice(-20);
        const lastHurt = evs.slice().reverse().find(e => e.type === 'self_hurt');
        const recentDrops = evs.filter(e => e.type === 'health_drop');
        const totalDrop = recentDrops.reduce((s, e) => s + (e.drop || 0), 0);

        // ===== socketClosed 静默断连指纹识别 =====
        if (/socketClosed/i.test(reason)) {
            const lastFrames = this.frames.slice(-6);
            const botStill = lastFrames.length >= 3 && lastFrames.every(f => {
                const hVel = f.vel ? Math.hypot(f.vel.x || 0, f.vel.z || 0) : 0;
                return hVel < 0.05 && !f.pf && (!f.ctrl || (!f.ctrl.forward && !f.ctrl.back && !f.ctrl.left && !f.ctrl.right));
            });
            const hpFull = lastFrames.length > 0 && lastFrames[lastFrames.length - 1].hp >= 19;
            const noSelfVel = !this._lastSelfVelPkt || (nowMs() - this._lastSelfVelPkt.t > 10000);
            const noSelfExplosion = !this._lastSelfExplosionPkt || (nowMs() - this._lastSelfExplosionPkt.t > 10000);

            if (botStill && hpFull && noSelfVel && noSelfExplosion) {
                lines.push(`★ 指纹匹配: 远端静默踢 (socketClosed + bot静止 + HP满 + 无自身velocity/explosion)`);
                lines.push(`  可能原因: 反作弊插件静默close / BungeeCord超时 / keep_alive超时`);
            } else if (botStill && noSelfVel) {
                lines.push(`★ socketClosed + bot静止 + 无击退 -> 大概率服务端主动断开 (非战斗相关)`);
            } else {
                lines.push(`socketClosed 但 bot 不完全静止或有近期velocity -> 可能是击退后延迟断连`);
            }

            // keep_alive 间隔诊断
            if (this._lastKeepAlivePkt) {
                const gap = nowMs() - this._lastKeepAlivePkt.t;
                lines.push(`最后一次 keep_alive 距断线: ${(gap / 1000).toFixed(2)}s`);
                if (gap > 20000) {
                    lines.push(`  ⚠ keep_alive 超过 20s 未收到 -> 极可能是 keep_alive 超时踢人`);
                } else if (gap > 15000) {
                    lines.push(`  ⚠ keep_alive 间隔 >15s -> 接近超时阈值，Node主线程可能阻塞`);
                }
            } else {
                lines.push(`未记录到 keep_alive 包 -> 连接时间极短或抓包未覆盖`);
            }

            // socket 状态
            const sockDestroyed = payload.socketDestroyed;
            if (sockDestroyed === false) {
                lines.push(`socketDestroyed=false -> 不是 mineflayer 主动断的，是远端关闭`);
            }
        }

        if (lastHurt) {
            const dt = ((nowMs() - lastHurt.t) / 1000).toFixed(2);
            lines.push(`最近一次挨打: -${lastHurt.amount} HP, ${dt}s 前`);
        }
        if (totalDrop > 0) {
            lines.push(`最近共掉血: ${totalDrop} HP (${recentDrops.length} 次)`);
        }

        // 击退期间 pathfinder 是否仍在跑
        if (lastHurt) {
            const after = this.frames.filter(f => f.t >= lastHurt.t && f.t <= lastHurt.t + 1500);
            const stillMoving = after.filter(f => f.pf || f.ctrl?.forward || f.ctrl?.back).length;
            if (stillMoving > 0) {
                lines.push(`挨打后 1.5s 内仍有 ${stillMoving} 帧 pathfinder/前进键在动 -> 击退+主动移动叠加 -> 大概率 invalid_player_movement`);
            }
            // 速度峰值
            const peakVel = after.reduce((m, f) => {
                const v = f.vel ? Math.hypot(f.vel.x || 0, f.vel.z || 0) : 0;
                return v > m ? v : m;
            }, 0);
            if (peakVel > 8) {
                lines.push(`挨打后水平速度峰值 ${peakVel.toFixed(2)} m/s (超 8 即触发反作弊)`);
            }
        }

        if (/invalid_player_movement|moved too quickly|flying/i.test(reason)) {
            lines.push(`服务端原因: 行为异常 (${reason})`);
            // 专项诊断：区分两种根因
            //   A. throttle 吞包：站立挨打 -> 击退后 vel 变 null -> 关键 position 包没发出去
            //   B. 主动跑动叠击退：peakVel 超阈值
            const lastFrame = this.frames[this.frames.length - 1];
            const velNullAtEnd = lastFrame
                && lastFrame.vel
                && (lastFrame.vel.x === null || lastFrame.vel.y === null || lastFrame.vel.z === null);
            // 看挨打前 1.5s 内 bot 是否基本静止（同一格）
            const hurtEv = this.events.slice().reverse().find(e => e.type === 'self_hurt' || e.type === 'health_drop');
            let staticBeforeHurt = false;
            if (hurtEv) {
                const window = this.frames.filter(f => f.t >= hurtEv.t - 1500 && f.t <= hurtEv.t);
                if (window.length >= 3) {
                    const dx = Math.max(...window.map(f => f.pos.x)) - Math.min(...window.map(f => f.pos.x));
                    const dz = Math.max(...window.map(f => f.pos.z)) - Math.min(...window.map(f => f.pos.z));
                    staticBeforeHurt = (dx + dz) < 0.5;
                }
            }
            const throttleOn = !!settings.position_packet_throttle;
            if (velNullAtEnd && throttleOn) {
                lines.push(`>> 根因画像: position_packet_throttle=true + 挨打瞬间 vel=null -> throttle 吞掉了击退后的位置同步包`);
                lines.push(`>> 修法: 把 settings.position_packet_throttle 设为 false（这版应该已经是 false，再出现就是别的原因）`);
            } else if (velNullAtEnd) {
                lines.push(`>> 挨打瞬间 vel 变 null（mineflayer 内部状态过渡），但 throttle 已关 -> 检查 entityHurt 后是否有强制 stop 把 onGround 状态打乱`);
            } else if (staticBeforeHurt) {
                lines.push(`>> bot 挨打前基本静止 -> 服务端嫌客户端汇报频率太低 -> 不是主动移动问题`);
            } else {
                lines.push(`>> 修法候选: entityHurt 时立刻 clearControlStates + pathfinder.stop, 800ms 内冷冻所有动作（主动移动叠击退导致）`);
            }
            // 击退后 onGround 状态不一致诊断: 挨打前后 0.5s 内 onGround 是否抖动
            if (hurtEv) {
                const around = this.frames.filter(f => f.t >= hurtEv.t - 200 && f.t <= hurtEv.t + 800);
                const grounds = around.map(f => f.onGround).filter(v => v !== undefined && v !== null);
                if (grounds.length >= 2) {
                    const flips = grounds.reduce((n, v, i) => n + (i > 0 && v !== grounds[i-1] ? 1 : 0), 0);
                    if (flips >= 2) {
                        lines.push(`>> onGround 在击退窗口内抖动 ${flips} 次 -> 服务端权威坐标与客户端 onGround 不一致 -> knockbackSync 应已挂载，会在 entity_velocity/explosion 后强制 flush position_look(onGround=false)`);
                    }
                }
                // 挨打那一帧 vel 已被服务端设为大值，但客户端仍报 onGround=true 也是经典指纹
                const hurtFrame = around.find(f => Math.abs((f.t || 0) - hurtEv.t) < 80);
                if (hurtFrame && hurtFrame.onGround === true && hurtFrame.vel) {
                    const vmag = Math.hypot(hurtFrame.vel.x || 0, hurtFrame.vel.y || 0, hurtFrame.vel.z || 0);
                    if (vmag > 0.3) {
                        lines.push(`>> 挨打瞬间客户端仍报 onGround=true 但 vel=${vmag.toFixed(2)} -> 击退中却自称落地 -> invalid_player_movement 经典指纹`);
                    }
                }
            }
        }
        if (/timeout|timed out|keepalive/i.test(reason)) {
            lines.push(`服务端原因: 网络超时 -> 与战斗无关`);
        }
        if (/duplicate|already/i.test(reason)) {
            lines.push(`服务端原因: 重复登录 -> 上一次进程没退干净`);
        }

        if (lines.length === 0) {
            lines.push('未匹配到已知模式，请直接看 frames / packetsTail 末尾几帧。');
        }
        return lines;
    }

    _writeSummary(prefix, headline, payload, fname) {
        const lines = [];
        lines.push(`时间: ${new Date().toLocaleString('zh-CN')}`);
        lines.push(`类型: ${prefix.replace(/_$/, '')} - ${headline}`);
        if (payload.reason) lines.push(`raw reason: ${payload.reason}`);
        if (payload.lastDrop) lines.push(`触发的单次掉血: ${payload.lastDrop} HP`);
        lines.push('');
        lines.push('诊断:');
        for (const l of this._diagnose(payload)) lines.push('  - ' + l);
        lines.push('');
        lines.push(`详细数据: ${fname}`);
        return lines.join('\n');
    }

    _dump(prefix, headline, payload) {
        // 防重复：掉线类只允许 dump 一次
        if (prefix.startsWith('掉线') && this.dumped) return;
        if (prefix.startsWith('掉线')) this.dumped = true;

        try {
            const ts = nowIsoSafe();
            const fname = `${prefix}${ts}.json`;
            const fpath = path.join(this.dir, fname);
            const dump = {
                time: new Date().toISOString(),
                type: prefix.replace(/_$/, ''),
                headline,
                payload,
                events: this.events.slice(),
                frames: this.frames.slice(),
                packetsTail: this.packetsTail.slice(),
            };
            fs.writeFileSync(fpath, JSON.stringify(dump, null, 2), 'utf8');
            // 最新副本
            fs.writeFileSync(path.join(this.dir, '最新.json'), JSON.stringify(dump, null, 2), 'utf8');
            // 摘要 txt
            const summary = this._writeSummary(prefix, headline, payload, fname);
            fs.writeFileSync(path.join(this.dir, '最新_摘要.txt'), summary, 'utf8');
            console.log(`[DisconnectProbe] dumped -> ${fpath}`);
            console.log('[DisconnectProbe] 摘要:\n' + summary);
        } catch (e) {
            console.warn('[DisconnectProbe] dump failed:', e?.message || e);
        }
    }

    /**
     * 同步兜底 dump：在 cleanKill / process.exit 之前调用，保证 dump 一定落盘。
     * cleanKill 同步触发 process.exit() 会让后注册的 'end'/'kicked' once 监听器
     * 来不及执行——因此必须在 exit 之前主动 flush 一次。
     */
    flushOnExit(reasonHint) {
        try {
            if (!this.enabled) return;
            if (this.dumped) return;
            const reason = this._stringifyReason(reasonHint);
            const sock = this.agent?.bot?._client?.socket;
            this._pushEvent({
                type: 'flush_on_exit',
                reason,
                socketDestroyed: sock ? !!sock.destroyed : null,
            });
            this._dump('掉线_', 'cleanKill 兜底 flush', {
                reason,
                socketDestroyed: sock ? !!sock.destroyed : null,
                lastDamageEventPkt: this._lastDamageEventPkt,
                lastUpdateHealthPkt: this._lastUpdateHealthPkt,
                lastDamageCause: this._lastDamageCause,
            });
        } catch (e) {
            try { console.warn('[DisconnectProbe] flushOnExit failed:', e?.message || e); } catch (_) {}
        }
    }

    detach() {
        if (this._frameTimer) { clearInterval(this._frameTimer); this._frameTimer = null; }
    }
}


