import { writeFileSync, readFileSync, mkdirSync, existsSync } from 'fs';
import { NPCData } from './npc/data.js';
import settings from './settings.js';

function estimateTokens(value) {
    if (!value) return 0;
    const text = typeof value === 'string' ? value : JSON.stringify(value);
    let tokens = 0;
    let asciiRun = 0;
    for (const ch of text) {
        if (ch.charCodeAt(0) < 128) {
            asciiRun++;
        } else {
            if (asciiRun > 0) {
                tokens += Math.ceil(asciiRun / 4);
                asciiRun = 0;
            }
            tokens += 1;
        }
    }
    if (asciiRun > 0) tokens += Math.ceil(asciiRun / 4);
    return tokens;
}

export class History {
    constructor(agent) {
        this.agent = agent;
        this.name = agent.name;
        this.memory_fp = `./bots/${this.name}/memory.json`;
        this.full_history_fp = undefined;

        mkdirSync(`./bots/${this.name}/histories`, { recursive: true });

        this.turns = [];
        this.memory = '';

        this.max_messages = settings.max_messages;
        this.summary_chunk_size = settings.summary_chunk_size ?? 5;
        this.max_context_tokens = settings.max_context_tokens ?? 180000;
        this.context_compress_target_tokens = settings.context_compress_target_tokens ?? 120000;
        this.context_recent_keep_messages = settings.context_recent_keep_messages ?? 24;
    }

    getHistory() {
        return JSON.parse(JSON.stringify(this.turns));
    }

    estimateTurnTokens(turns = this.turns) {
        return estimateTokens(turns);
    }

    shouldCompressByTokens() {
        if (!this.max_context_tokens || this.max_context_tokens <= 0) return false;
        const promptReserve = settings.prompt_token_reserve ?? 12000;
        const current = this.estimateTurnTokens(this.turns) + estimateTokens(this.memory) + promptReserve;
        return current >= this.max_context_tokens;
    }

    chooseTokenCompressionChunk() {
        const minKeep = Math.max(4, this.context_recent_keep_messages);
        if (this.turns.length <= minKeep) return [];

        let removable = Math.max(1, this.turns.length - minKeep);
        let chunk = this.turns.slice(0, removable);
        const target = Math.max(2000, this.context_compress_target_tokens);
        while (removable > this.summary_chunk_size && this.estimateTurnTokens(this.turns.slice(removable)) < target) {
            removable--;
            chunk = this.turns.slice(0, removable);
        }
        return chunk;
    }

    async summarizeMemories(turns) {
        if (!turns || turns.length === 0) return;
        console.log(`Storing memories from ${turns.length} turns...`);
        this.memory = await this.agent.prompter.promptMemSaving(turns);

        const memoryLimit = settings.memory_summary_chars ?? 6000;
        if (this.memory.length > memoryLimit) {
            this.memory = this.memory.slice(0, memoryLimit);
            this.memory += `...(Memory truncated to ${memoryLimit} chars. Compress it more next time)`;
        }

        console.log("Memory updated to: ", this.memory);
    }

    async appendFullHistory(to_store) {
        if (!to_store || to_store.length === 0) return;
        if (this.full_history_fp === undefined) {
            const string_timestamp = new Date().toLocaleString().replace(/[/:]/g, '-').replace(/ /g, '').replace(/,/g, '_');
            this.full_history_fp = `./bots/${this.name}/histories/${string_timestamp}.json`;
            writeFileSync(this.full_history_fp, '[]', 'utf8');
        }
        try {
            const data = readFileSync(this.full_history_fp, 'utf8');
            let full_history = JSON.parse(data);
            full_history.push(...to_store);
            writeFileSync(this.full_history_fp, JSON.stringify(full_history, null, 4), 'utf8');
        } catch (err) {
            console.error(`Error reading ${this.name}'s full history file: ${err.message}`);
        }
    }

    async compressIfNeeded(reason = 'message_count') {
        let chunk = [];
        if (reason === 'token_budget') {
            chunk = this.chooseTokenCompressionChunk();
        } else if (this.turns.length >= this.max_messages) {
            chunk = this.turns.slice(0, this.summary_chunk_size);
        }
        if (chunk.length === 0) return false;

        this.turns.splice(0, chunk.length);
        while (this.turns.length > 0 && this.turns[0].role === 'assistant') {
            chunk.push(this.turns.shift());
        }

        await this.summarizeMemories(chunk);
        await this.appendFullHistory(chunk);
        console.log(`[History] compressed ${chunk.length} turns, reason=${reason}, remaining=${this.turns.length}, estimatedTokens=${this.estimateTurnTokens(this.turns)}`);
        return true;
    }

    async prepareForPrompt() {
        let compressed = false;
        let guard = 0;
        while (this.shouldCompressByTokens() && guard < 4) {
            const didCompress = await this.compressIfNeeded('token_budget');
            if (!didCompress) break;
            compressed = true;
            guard++;
        }
        if (compressed) await this.save();
        return compressed;
    }

    async add(name, content) {
        let role = 'assistant';
        if (name === 'system') {
            role = 'system';
        }
        else if (name !== this.name) {
            role = 'user';
            content = `${name}: ${content}`;
        }
        this.turns.push({role, content});

        if (this.turns.length >= this.max_messages) {
            await this.compressIfNeeded('message_count');
        }
    }

    async save() {
        try {
            const data = {
                memory: this.memory,
                turns: this.turns,
                self_prompting_state: this.agent.self_prompter.state,
                self_prompt: this.agent.self_prompter.isStopped() ? null : this.agent.self_prompter.prompt,
                taskStart: this.agent.task.taskStartTime,
                last_sender: this.agent.last_sender
            };
            writeFileSync(this.memory_fp, JSON.stringify(data, null, 2));
            console.log('Saved memory to:', this.memory_fp);
        } catch (error) {
            console.error('Failed to save history:', error);
            throw error;
        }
    }

    load() {
        try {
            if (!existsSync(this.memory_fp)) {
                console.log('No memory file found.');
                return null;
            }
            const data = JSON.parse(readFileSync(this.memory_fp, 'utf8'));
            this.memory = data.memory || '';
            this.turns = data.turns || [];
            console.log('Loaded memory:', this.memory);
            return data;
        } catch (error) {
            console.error('Failed to load history:', error);
            throw error;
        }
    }

    clear() {
        this.turns = [];
        this.memory = '';
    }
}