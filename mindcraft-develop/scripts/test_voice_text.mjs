import assert from 'node:assert/strict';
import { extractVoiceText } from '../src/agent/voice_text.js';

assert.equal(extractVoiceText('[THINK] 先看看周围再行动。'), '');
assert.equal(extractVoiceText(' [THINK] 内心话，不该出声'), '');
assert.equal(extractVoiceText('[SAY] 来了，别催。'), '来了，别催。');
assert.equal(extractVoiceText('[SAY] 我去找铁。\n!searchForBlock("iron_ore", 64)'), '我去找铁。');
assert.equal(extractVoiceText('!scanAroundMe 16'), '');

console.log('voice text tests passed');
