// idle_heartbeat.js - 防 AFK/idle kick 守护
//
// 每 25 秒做一次极小幅度合法动作（swingArm 或 yaw 微抖），
// 让服务端认为客户端仍然活跃，躲掉 idle/AFK 类踢人规则。
// 同时作为活性指标：如果 heartbeat 回调没被调用，说明 Node 主线程阻塞。
//
// 挂载点: agent.js spawn 钩子里调用 attachIdleHeartbeat(agent)

import settings from '../settings.js';

const DEFAULT_INTERVAL_MS = 25000; // 25s，远低于常见 AFK 阈值 (60-300s)
const YAW_JITTER = 0.02; // 极小 yaw 抖动，肉眼不可见

export function attachIdleHeartbeat(agent) {
    const cfg = settings.idle_heartbeat || {};
    if (cfg.enabled === false) return;

    const bot = agent?.bot;
    if (!bot) return;
    if (bot.__idle_heartbeat_attached) return;
    bot.__idle_heartbeat_attached = true;

    const intervalMs = cfg.interval_ms ?? DEFAULT_INTERVAL_MS;
    let tick = 0;
    let lastBeatAt = Date.now();

    const timer = setInterval(() => {
        try {
            if (!bot.entity) return;
            lastBeatAt = Date.now();
            tick++;

            // 交替使用两种动作，避免被模式检测
            if (tick % 2 === 0) {
                // swingArm: 最轻量的合法动作，服务端会重置 idle 计时器
                bot.swingArm('right');
            } else {
                // yaw 微抖: 改变朝向一个极小值然后复位
                const origYaw = bot.entity.yaw || 0;
                bot.look(origYaw + YAW_JITTER, bot.entity.pitch || 0, false);
                // 下一 tick 复位（不用 await，fire-and-forget）
                setTimeout(() => {
                    try {
                        bot.look(origYaw, bot.entity.pitch || 0, false);
                    } catch (_) {}
                }, 100);
            }

            if (cfg.log) {
                console.log(`[idleHeartbeat] tick#${tick} at ${new Date().toLocaleTimeString()}`);
            }
        } catch (_) {
            // 静默，不影响主流程
        }
    }, intervalMs);

    // bot end 时清理
    bot.once('end', () => {
        clearInterval(timer);
    });

    console.log(`[idleHeartbeat] attached (interval=${intervalMs}ms)`);

    // 暴露活性检查接口
    bot.__idle_heartbeat_lastBeat = () => lastBeatAt;
}
