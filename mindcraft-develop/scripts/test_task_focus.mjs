import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { TaskBoard } from '../src/agent/task_board.js';
import { detectFocus } from '../src/agent/agent.js';

const dir = mkdtempSync(path.join(tmpdir(), 'syna-task-focus-'));
try {
    const board = new TaskBoard(path.join(dir, 'task_board.json'), { focusTtlMs: 10 * 60 * 1000 });

    board.setFocus('【哥在麦克风对你说】：哎，先不做防弹，先不做防弹。', 'SynaMic', {
        now: new Date('2026-06-20T08:22:47.439Z'),
    });

    assert.equal(
        board.getFocus({ now: new Date('2026-06-20T08:31:47.439Z') })?.text,
        '【哥在麦克风对你说】：哎，先不做防弹，先不做防弹。'
    );
    assert.equal(board.getFocus({ now: new Date('2026-06-20T08:40:47.439Z') }), null);

    assert.equal(detectFocus('【哥在麦克风对你说】：哎，先不做防弹，先不做防弹。'), '__CLEAR__');
    assert.equal(detectFocus('先别做房子了'), '__CLEAR__');
    assert.equal(detectFocus('帮我找铁矿'), '帮我找铁矿');

    console.log('task focus tests passed');
} finally {
    rmSync(dir, { recursive: true, force: true });
}
