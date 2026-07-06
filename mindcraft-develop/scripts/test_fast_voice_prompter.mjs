import assert from 'node:assert/strict';
import { Prompter } from '../src/models/prompter.js';

const prompter = Object.create(Prompter.prototype);
prompter.profile = {
    fast_voice: {
        enabled: true,
        max_history_messages: 8,
        fallback_to_main_on_complex: true,
        complex_triggers: ['建造', '看看'],
    },
    fast_voice_conversing: '快速回应\n$CONVO',
};
prompter.agent = {
    name: 'syna',
    history: { memory: '' },
    self_prompter: { isStopped: () => true },
    actions: { currentActionLabel: '' },
};
prompter.replaceStrings = async function(prompt, messages) {
    return prompt.replace('$CONVO', messages.map(m => m.content).join('\n'));
};
prompter._saveLog = async function() {};
prompter.fast_chat_model = {
    async sendRequestStreaming(messages, prompt, stop, onSentence) {
        assert.equal(messages.length, 8);
        assert.ok(prompt.includes('msg-11'));
        onSentence('来了。');
        return '来了。';
    }
};

const manyMessages = Array.from({ length: 12 }, (_, i) => ({ role: i % 2 ? 'assistant' : 'user', content: `msg-${i}` }));
assert.equal(prompter.buildFastVoiceMessages(manyMessages).length, 8);
assert.equal(prompter.isComplexVoiceMessage([{ role: 'user', content: '帮我建造房子' }]), true);
assert.equal(prompter.isComplexVoiceMessage([{ role: 'user', content: '过来一下' }]), false);

const spoken = [];
const result = await prompter.promptFastVoiceConvoStreaming(manyMessages, sentence => spoken.push(sentence));
assert.equal(result, '来了。');
assert.deepEqual(spoken, ['来了。']);

const fallback = await prompter.promptFastVoiceConvoStreaming([{ role: 'user', content: '你看看这里' }], () => {});
assert.equal(fallback, null);

prompter.fast_chat_model = {
    async sendRequestStreaming(messages, prompt, stop, onSentence) {
        onSentence('NEED_MAIN');
        return 'NEED_MAIN';
    }
};
const noSpeak = [];
const needMain = await prompter.promptFastVoiceConvoStreaming([{ role: 'user', content: '解释一下' }], s => noSpeak.push(s));
assert.equal(needMain, null);
assert.deepEqual(noSpeak, []);

console.log('fast voice prompter tests passed');
