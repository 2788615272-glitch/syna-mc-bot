import taskBoard from './task_board.js';
import { buildMcRunGoalPrompt } from './library/mc_run_planner.js';

const STOPPED = 0
const ACTIVE = 1
const PAUSED = 2
const MC_RUN_IDLE_KICK_MS = 10000;
const MC_RUN_MOVE_EPSILON = 0.18;
const SELF_PROMPT_MAX_RESPONSES = 1;
const SELF_PROMPT_LLM_TIMEOUT_MS = 18000;
export class SelfPrompter {
    constructor(agent) {
        this.agent = agent;
        this.state = STOPPED;
        this.loop_active = false;
        this.interrupt = false;
        this.current_request_token = null;
        this.prompt = '';
        this.idle_time = 0;
        this.cooldown = 6000;
        this.last_response_had_chat = false; // 由 agent 在回复后回写
        this.mc_run_still_time = 0;
        this.mc_run_last_pos = null;
        this.mc_run_last_kick_at = 0;
        this.mc_run_recover_time = 0;
        this.request_seq = 0;
        this.current_request_token = null;
    }


    start(prompt) {
        console.log('Self-prompting started.');
        if (!prompt) {
            if (!this.prompt)
                return 'No prompt specified. Ignoring request.';
            prompt = this.prompt;
        }
        this.state = ACTIVE;
        this.prompt = prompt;
        if (this.loop_active && this.current_request_token) {
            this.current_request_token.cancelled = true;
            console.warn('[SelfPrompter] active loop prompt replaced; cancelling stale LLM response.');
        }
        this.startLoop();
    }

    isActive() {
        return this.state === ACTIVE;
    }

    isStopped() {
        return this.state === STOPPED;
    }

    isPaused() {
        return this.state === PAUSED;
    }

    async handleLoad(prompt, state) {
        if (state == undefined)
            state = STOPPED;
        this.state = state;
        this.prompt = prompt;
        if (state !== STOPPED && !prompt)
            throw new Error('No prompt loaded when self-prompting is active');
        if (state === ACTIVE) {
            await this.start(prompt);
        }
    }

    setPromptPaused(prompt) {
        this.prompt = prompt;
        this.state = PAUSED;
    }

    _hasMcRunTasks() {
        try {
            return taskBoard.list().some(t => t?.kind === 'mc_run' && !t?.done);
        } catch (_) {
            return false;
        }
    }

    _isMcRunActive() {
        const prompt = String(this.prompt || '');
        return prompt.includes('MC_RUN_STATUS') || prompt.includes('MC_RUN_WATCHDOG') || prompt.includes('mcRun') || prompt.includes('mc_run');
    }

    _refreshMcRunPrompt(reason = 'refresh', options = {}) {
        if (!options.force && !this._isMcRunActive()) return false;
        try {
            const refreshed = buildMcRunGoalPrompt(this.agent);
            this.prompt = [
                refreshed,
                `\n[MC_RUN_WATCHDOG] Big plan: 通关我的世界. The bot has been idle/still too long (${reason}). Re-read MC_RUN_STATUS and SUGGESTED_NEXT_ACTION. Your next reply MUST contain exactly one ACTION command, not a status/query command. Keep public speech short or silent.`
            ].join('\n');
            return true;
        } catch (err) {
            console.warn('[SelfPrompter] failed to refresh MC run prompt:', err?.message || err);
            return false;
        }
    }

    _updateMcRunStillTime(delta) {
        if (!this._isMcRunActive() && !this._hasMcRunTasks()) {
            this.mc_run_still_time = 0;
            this.mc_run_last_pos = null;
            return 0;
        }
        const pos = this.agent?.bot?.entity?.position;
        if (!pos) return this.mc_run_still_time;
        const current = { x: Number(pos.x) || 0, y: Number(pos.y) || 0, z: Number(pos.z) || 0 };
        if (!this.mc_run_last_pos) {
            this.mc_run_last_pos = current;
            this.mc_run_still_time = 0;
            return 0;
        }
        const dx = current.x - this.mc_run_last_pos.x;
        const dy = current.y - this.mc_run_last_pos.y;
        const dz = current.z - this.mc_run_last_pos.z;
        const moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (moved > MC_RUN_MOVE_EPSILON) {
            this.mc_run_last_pos = current;
            this.mc_run_still_time = 0;
        } else {
            this.mc_run_still_time += delta;
        }
        return this.mc_run_still_time;
    }

    async startLoop() {
        if (this.loop_active) {
            console.warn('Self-prompt loop is already active. Ignoring request.');
            return;
        }
        console.log('starting self-prompt loop')
        this.loop_active = true;
        let no_command_count = 0;
        const MAX_NO_COMMAND = 5;
        while (!this.interrupt) {
            if (this._isMcRunActive()) {
                this._refreshMcRunPrompt('loop_refresh');
            }
            let taskInfo = '';
            const pending = taskBoard.getPendingText();
            if (pending) {
                taskInfo = `\n【任务板 - 未完成】:\n${pending}\n优先完成任务板上的任务。`;
            }

            // 主任务焦点（用户口头交代的事）优先级最高，覆盖兜底自循环 prompt
            const focus = taskBoard.getFocus && taskBoard.getFocus();
            let msg;
            if (focus && focus.text) {
                msg = `【当前主任务（${focus.source || 'user'} 刚交代的）】：${focus.text}\n这是你必须完成的事。完成或被明确撤销前，不要去做别的，也不要把它当背景噪声忽略。${taskInfo}\nYour next response MUST contain a command with this syntax: !commandName. 围绕主任务推进。完成后用 !endGoal 或显式说明已完成。If you want to say something to nearby players, prefix with [SAY]. Inner monologue use [THINK]. Respond:`;
            } else {
                msg = `You are self-prompting with the goal: '${this.prompt}'.${taskInfo}\nYour next response MUST contain a command with this syntax: !commandName. If you want to say something to nearby players, prefix your speech with [SAY]. If this is only inner monologue or status, prefix it with [THINK] or omit speech entirely. Most routine actions should be silent. Respond:`;
            }

            // 重置标志，handleMessage 内的回复链路负责回写
            this.last_response_had_chat = false;
            const requestToken = { id: ++this.request_seq, cancelled: false, label: 'self_prompt' };
            this.current_request_token = requestToken;
            const handlePromise = this.agent.handleMessage('system', msg, SELF_PROMPT_MAX_RESPONSES, { cancelToken: requestToken })
                .catch(err => {
                    if (!requestToken.cancelled) console.warn('[SelfPrompter] handleMessage failed:', err?.message || err);
                    return false;
                });
            const timeoutPromise = new Promise(resolve => setTimeout(() => resolve('__timeout__'), SELF_PROMPT_LLM_TIMEOUT_MS));
            let used_command = await Promise.race([handlePromise, timeoutPromise]);
            if (used_command === '__timeout__') {
                if (this.agent?.actions?.executing) {
                    console.warn('[SelfPrompter] LLM/action round exceeded prompt timeout, but action is still executing; waiting instead of issuing another command. current=' + this.agent.actions.currentActionLabel);
                    used_command = await handlePromise;
                } else {
                    requestToken.cancelled = true;
                    used_command = false;
                    console.warn('[SelfPrompter] LLM round timed out; cancelling stale response and continuing watchdog loop.');
                    handlePromise.catch(() => {});
                }
            }
            if (this.current_request_token === requestToken) this.current_request_token = null;
            console.log(`[SelfPrompter] round finished used_command=${Boolean(used_command)} chat=${Boolean(this.last_response_had_chat)} loop_active=${this.loop_active}`);

            // 有命令 或 有有效聊天回应（说明 AI 在跟人对话），都算"有效响应"，不计入无命令计数
            const had_effective_response = used_command || this.last_response_had_chat;
            if (!had_effective_response) {
                no_command_count++;
                if (no_command_count >= MAX_NO_COMMAND) {
                    let out = `Agent did not use command in the last ${MAX_NO_COMMAND} auto-prompts. Stopping auto-prompting.`;
                    this.agent.openChat(out);
                    console.warn(out);
                    this.state = STOPPED;
                    break;
                }
            }
            else {
                no_command_count = 0;
                await new Promise(r => setTimeout(r, this.cooldown));
            }
        }

        console.log('self prompt loop stopped')
        this.loop_active = false;
        this.interrupt = false;
    }

    update(delta) {
        if (this.agent?.actions?.executing) {
            this.idle_time = 0;
            this.mc_run_recover_time = 0;
            this.mc_run_still_time = 0;
            this.mc_run_last_pos = null;
            return;
        }
        const mcRunStillTime = this._updateMcRunStillTime(delta);

        // MC run is allowed to kick itself back awake after a completed or stalled step.
        const hasMcRunTasks = this._hasMcRunTasks();
        const mcRunShouldRecover = hasMcRunTasks && !this.loop_active && !this.interrupt;
        if ((this.state === ACTIVE && this._isMcRunActive() && !this.loop_active && !this.interrupt) || mcRunShouldRecover) {
            if (this.agent.isIdle()) {
                this.idle_time += delta;
                this.mc_run_recover_time += delta;
            } else {
                this.idle_time = 0;
                this.mc_run_recover_time = 0;
            }

            const now = Date.now();
            const inactiveMcRun = hasMcRunTasks && (this.state !== ACTIVE || !this._isMcRunActive());
            const shouldKick = (inactiveMcRun && this.mc_run_recover_time >= MC_RUN_IDLE_KICK_MS) || this.idle_time >= MC_RUN_IDLE_KICK_MS || mcRunStillTime >= MC_RUN_IDLE_KICK_MS;
            if (shouldKick && now - this.mc_run_last_kick_at >= MC_RUN_IDLE_KICK_MS) {
                this.mc_run_last_kick_at = now;
                const reason = inactiveMcRun ? 'mc_run_tasks_without_active_loop' : (mcRunStillTime >= MC_RUN_IDLE_KICK_MS ? 'no_position_change_10s' : 'idle_10s');
                this.state = ACTIVE;
                this._refreshMcRunPrompt(reason, { force: true });
                console.log('[SelfPrompter] MC run watchdog restarting self-prompting after idle/still period. reason=' + reason);
                this.startLoop();
                this.idle_time = 0;
                this.mc_run_still_time = 0;
                this.mc_run_recover_time = 0;
            }
            return;
        }

        // automatically restarts loop
        if (this.state === ACTIVE && !this.loop_active && !this.interrupt) {
            if (this.agent.isIdle())
                this.idle_time += delta;
            else
                this.idle_time = 0;

            if (this.idle_time >= this.cooldown) {
                console.log('Restarting self-prompting...');
                this.startLoop();
                this.idle_time = 0;
            }
        }
        else {
            this.idle_time = 0;
        }
    }

    async stopLoop() {
        // you can call this without await if you don't need to wait for it to finish
        if (this.interrupt)
            return;
        console.log('stopping self-prompt loop')
        this.interrupt = true;
        while (this.loop_active) {
            await new Promise(r => setTimeout(r, 500));
        }
        this.interrupt = false;
    }

    async stop(stop_action=true) {
        this.interrupt = true;
        if (stop_action)
            await this.agent.actions.stop();
        this.stopLoop();
        this.state = STOPPED;
    }

    async pause() {
        this.interrupt = true;
        await this.agent.actions.stop();
        this.stopLoop();
        this.state = PAUSED;
    }

    shouldInterrupt(is_self_prompt) { // to be called from handleMessage
        return is_self_prompt && (this.state === ACTIVE || this.state === PAUSED) && this.interrupt;
    }

    handleUserPromptedCmd(is_self_prompt, is_action) {
        // if a user messages and the bot responds with an action, stop the self-prompt loop
        if (!is_self_prompt && is_action) {
            this.stopLoop();
            // this stops it from responding from the handlemessage loop and the self-prompt loop at the same time
        }
    }
}