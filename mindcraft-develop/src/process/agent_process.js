import { spawn } from 'child_process';
import { fileURLToPath } from 'url';
import { logoutAgent } from '../mindcraft/mindserver.js';

const init_agent_path = fileURLToPath(new URL('./init_agent.js', import.meta.url));

export class AgentProcess {
    constructor(name, port) {
        this.name = name;
        this.port = port;
        this.quickRestartCount = 0;
    }

    start(load_memory=false, init_message=null, count_id=0) {
        this.count_id = count_id;
        this.running = true;

        let args = [init_agent_path, this.name];
        args.push('-n', this.name);
        args.push('-c', count_id);
        if (load_memory)
            args.push('-l', load_memory);
        if (init_message)
            args.push('-m', init_message);
        args.push('-p', this.port);

        const agentProcess = spawn(process.execPath, args, {
            stdio: 'inherit',
            stderr: 'inherit',
        });
        
        const startedAt = Date.now();
        agentProcess.on('exit', (code, signal) => {
            console.log(`Agent process exited with code ${code} and signal ${signal}`);
            this.running = false;
            logoutAgent(this.name);

            if (code === 10) {
                console.warn(`Agent ${this.name} exited due to a fatal login/configuration issue and will not be auto-restarted.`);
                return;
            }
            
            if (code > 1) {
                console.log(`Ending task`);
                process.exit(code);
            }

            if (code !== 0 && signal !== 'SIGINT') {
                const minLifetime = Number(process.env.AGENT_RESTART_MIN_LIFETIME_MS || 3000);
                const retryDelay = Number(process.env.AGENT_RESTART_DELAY_MS || 5000);
                const maxQuickRestarts = Number(process.env.AGENT_MAX_QUICK_RESTARTS || 6);
                const lifetime = Date.now() - startedAt;

                if (lifetime < minLifetime) {
                    this.quickRestartCount += 1;
                    if (this.quickRestartCount > maxQuickRestarts) {
                        console.error(`Agent process exited too quickly ${this.quickRestartCount} times and will not be restarted.`);
                        return;
                    }
                    console.warn(`Agent process exited after ${lifetime}ms. Retrying in ${retryDelay}ms (${this.quickRestartCount}/${maxQuickRestarts})...`);
                    setTimeout(() => {
                        this.start(true, 'Agent process restarted.', count_id, this.port);
                    }, retryDelay);
                    return;
                }

                this.quickRestartCount = 0;
                console.log('Restarting agent...');
                this.start(true, 'Agent process restarted.', count_id, this.port);
            }
        });
    
        agentProcess.on('error', (err) => {
            console.error('Agent process error:', err);
        });

        this.process = agentProcess;
    }

    stop() {
        if (!this.running) return;
        this.process.kill('SIGINT');
    }

    forceRestart() {
        if (this.running && this.process && !this.process.killed) {
            console.log(`Agent process for ${this.name} is still running. Attempting to force restart.`);
            
            const restartTimeout = setTimeout(() => {
                console.warn(`Agent ${this.name} did not stop in time. It might be stuck.`);
            }, 5000); // 5 seconds to exit

            this.process.once('exit', () => {
                 clearTimeout(restartTimeout);
                 console.log(`Stopped hanging agent ${this.name}. Now restarting.`);
                 this.start(true, 'Agent process restarted.', this.count_id);
            });
            this.stop(); // sends SIGINT
        } else {
             this.start(true, 'Agent process restarted.', this.count_id);
        }
    }
}