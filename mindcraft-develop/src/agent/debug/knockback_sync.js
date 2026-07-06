// 击退同步守护
//
// 解决问题: 服务端反作弊 (尤其 Forge 服) 在 bot 被击退的瞬间，
// 因为 mineflayer physics 模块算出的 onGround / 坐标 与服务端权威坐标对不齐，
// 立刻触发 multiplayer.disconnect.invalid_player_movement 把 bot 踢掉。
//
// 修法: 监听 bot 自己的 entity_velocity / damage_event，
//       下一 tick 强制发一个权威 position_look 包 (onGround=false)，
//       并暂冻 pathfinder + clearControlStates 600ms，让服务端重新校准。
//
// 这一层是兜底 + 容错，不会和正常 physics 冲突 (mineflayer 内部仍然在按 50ms 发 position 包，
// 我们只是在击退瞬间额外塞一个同步点)。
import settings from '../settings.js';

export function attachKnockbackSync(agent) {
    if (!settings.knockback_sync_enabled && settings.knockback_sync_enabled !== undefined) return;
    const bot = agent?.bot;
    if (!bot || !bot._client) return;
    if (bot.__knockback_sync_attached) return;
    bot.__knockback_sync_attached = true;

    const FREEZE_MS = settings.knockback_freeze_ms ?? 600;
    const VEL_TRIGGER = settings.knockback_vel_trigger ?? 0.15; // m/tick, 任何一轴超过即认为是击退
    let frozenUntil = 0;
    let savedCtrl = null;

    function nowMs() { return Date.now(); }

    function flushPositionLook(reasonTag) {
        try {
            const e = bot.entity;
            if (!e || !e.position) return;
            const pkt = {
                x: e.position.x,
                y: e.position.y,
                z: e.position.z,
                yaw: e.yaw ?? 0,
                pitch: e.pitch ?? 0,
                onGround: false, // 关键: 被击退一定不在地面，强制和服务端对齐
            };
            bot._client.write('position_look', pkt);
            // 部分协议版本叫 'position_and_look', 写双份兜底
            try { bot._client.write('position_and_look', pkt); } catch (_) {}
            if (settings.knockback_sync_log) {
                console.log(`[knockbackSync] flush position_look (${reasonTag}) at (${pkt.x.toFixed(2)},${pkt.y.toFixed(2)},${pkt.z.toFixed(2)}) onGround=false`);
            }
        } catch (e) {
            // 静默，反正下一轮还会被触发
        }
    }

    function freezeControls(reasonTag) {
        try {
            // 保存 pathfinder 目标，FREEZE 结束后由用户层自行恢复（我们不替它续）
            try { bot.pathfinder?.stop?.(); } catch (_) {}
            savedCtrl = bot.controlState ? Object.assign({}, bot.controlState) : null;
            try { bot.clearControlStates?.(); } catch (_) {}
            frozenUntil = nowMs() + FREEZE_MS;
            if (settings.knockback_sync_log) {
                console.log(`[knockbackSync] freeze ${FREEZE_MS}ms (${reasonTag})`);
            }
        } catch (_) {}
    }

    function onTrigger(reasonTag) {
        // 下一 tick flush，给 mineflayer 一帧时间让 entity.position 更新到击退后位置
        setImmediate(() => flushPositionLook(reasonTag));
        // 200ms 再 flush 一次，覆盖击退轨迹中段
        setTimeout(() => flushPositionLook(reasonTag + '+200'), 200);
        freezeControls(reasonTag);
    }

    // 1) entity_velocity: 服务端把击退速度推给客户端时一定经过这里
    bot._client.on('packet', (data, meta) => {
        try {
            if (!meta || !meta.name) return;
            // bot 自己的 entityId 才管
            const myId = bot.entity?.id;
            if (meta.name === 'entity_velocity') {
                if (myId == null || data.entityId !== myId) return;
                const vx = (data.velocityX || 0) / 8000;
                const vy = (data.velocityY || 0) / 8000;
                const vz = (data.velocityZ || 0) / 8000;
                if (Math.abs(vx) > VEL_TRIGGER || Math.abs(vy) > VEL_TRIGGER || Math.abs(vz) > VEL_TRIGGER) {
                    onTrigger('entity_velocity');
                }
            } else if (meta.name === 'explosion') {
                // 爆炸推动玩家也会触发同样的 onGround 不一致
                onTrigger('explosion');
            } else if (meta.name === 'damage_event') {
                // damage_event 比 update_health 早一帧，作为兜底
                if (myId != null && data.entityId === myId) {
                    onTrigger('damage_event');
                }
            }
        } catch (_) {}
    });

    // 2) physicsTick: 在冻结期内持续清空 controlState，防止上层逻辑重新按键
    bot.on('physicsTick', () => {
        if (nowMs() < frozenUntil) {
            try { bot.clearControlStates?.(); } catch (_) {}
        }
    });

    console.log('[knockbackSync] attached');
}
