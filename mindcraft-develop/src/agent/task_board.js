import fs from 'fs';
import path from 'path';

const BOARD_FILE = './task_board.json';
const DEFAULT_VOICE_FOCUS_TTL_MS = 10 * 60 * 1000;

export class TaskBoard {
    constructor(boardFile = BOARD_FILE, options = {}) {
        this.boardFile = boardFile;
        this.voiceFocusTtlMs = options.voiceFocusTtlMs ?? options.focusTtlMs ?? DEFAULT_VOICE_FOCUS_TTL_MS;
        this.tasks = [];
        this.nextId = 1;
        this.focus = null; // 当前主任务焦点 { text, source, set_at }
        this.load();
    }

    load() {
        try {
            if (fs.existsSync(this.boardFile)) {
                const data = JSON.parse(fs.readFileSync(this.boardFile, 'utf-8'));
                this.tasks = data.tasks || [];
                this.nextId = data.nextId || (this.tasks.length > 0 ? Math.max(...this.tasks.map(t => t.id)) + 1 : 1);
                this.focus = data.focus || null;
            }
        } catch (e) {
            console.warn('[TaskBoard] Failed to load:', e.message);
            this.tasks = [];
            this.nextId = 1;
            this.focus = null;
        }
    }

    save() {
        try {
            fs.writeFileSync(this.boardFile, JSON.stringify({
                tasks: this.tasks,
                nextId: this.nextId,
                focus: this.focus
            }, null, 2), 'utf-8');
        } catch (e) {
            console.warn('[TaskBoard] Failed to save:', e.message);
        }
    }

    setFocus(text, source = 'user', options = {}) {
        if (!text || !text.trim()) return null;
        this.focus = {
            text: text.trim(),
            source,
            set_at: (options.now || new Date()).toISOString()
        };
        this.save();
        return this.focus;
    }

    isFocusExpired(focus = this.focus, options = {}) {
        if (!focus || !focus.set_at) return false;
        const source = String(focus.source || '');
        const text = String(focus.text || '');
        const isVoice = source === 'SynaMic' || /mic|microphone/i.test(source);
        if (!isVoice) return false;
        const ttlMs = options.voiceFocusTtlMs ?? this.voiceFocusTtlMs;
        if (!Number.isFinite(ttlMs) || ttlMs <= 0) return false;
        const setAt = Date.parse(focus.set_at);
        if (!Number.isFinite(setAt)) return false;
        const now = options.now ? new Date(options.now).getTime() : Date.now();
        return now - setAt > ttlMs;
    }

    getFocus(options = {}) {
        if (this.isFocusExpired(this.focus, options)) {
            const old = this.focus;
            this.focus = null;
            this.save();
            console.log(`[TaskBoard] voice focus expired and was cleared: "${old.text}"`);
            return null;
        }
        return this.focus;
    }

    clearFocus() {
        const old = this.focus;
        this.focus = null;
        this.save();
        return old;
    }

    hasFocus() {
        return this.getFocus() !== null;
    }


    add(text, meta = {}) {
        const task = {
            id: this.nextId++,
            text: text.trim(),
            done: false,
            added_at: new Date().toISOString(),
            ...meta
        };
        this.tasks.push(task);
        this.save();
        return task;
    }

    addMany(texts, meta = {}) {
        const added = [];
        for (const text of texts || []) {
            if (!text || !String(text).trim()) continue;
            added.push(this.add(String(text), meta));
        }
        return added;
    }

    clear(options = {}) {
        const oldLength = this.tasks.length;
        const prefix = options.prefix == null ? null : String(options.prefix);
        const kind = options.kind == null ? null : String(options.kind);
        if (kind) {
            this.tasks = this.tasks.filter(t => String(t.kind || '') !== kind);
        } else if (prefix) {
            this.tasks = this.tasks.filter(t => !String(t.text || '').startsWith(prefix));
        } else {
            this.tasks = [];
        }
        this.save();
        return oldLength - this.tasks.length;
    }
    done(id) {
        const task = this.tasks.find(t => t.id === id);
        if (!task) return null;
        task.done = true;
        task.completed_at = new Date().toISOString();
        this.save();
        return task;
    }

    remove(id) {
        const idx = this.tasks.findIndex(t => t.id === id);
        if (idx === -1) return null;
        const removed = this.tasks.splice(idx, 1)[0];
        this.save();
        return removed;
    }

    list() {
        return this.tasks;
    }

    getStatusText() {
        if (this.tasks.length === 0) return '（任务板为空）';
        return this.tasks.map(t => {
            const mark = t.done ? '✓' : '○';
            return `${mark} #${t.id} ${t.text}`;
        }).join('\n');
    }

    getPendingText() {
        const pending = this.tasks.filter(t => !t.done);
        if (pending.length === 0) return '';
        return pending.map(t => `○ #${t.id} ${t.text}`).join('\n');
    }
}

// Singleton
const taskBoard = new TaskBoard();
export default taskBoard;
