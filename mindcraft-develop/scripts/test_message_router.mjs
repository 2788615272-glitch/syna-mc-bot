import assert from 'assert/strict';
import { routeIncomingMessage } from '../src/agent/message_router.js';

const common = { source: 'friend', botName: 'syna', aliases: ['syna', 'Syna'] };

assert.deepEqual(
  routeIncomingMessage({ ...common, message: 'hello', channel: 'chat' }),
  { respond: false, reason: 'public_not_addressed' }
);

const addressed = routeIncomingMessage({ ...common, message: 'hello syna', channel: 'chat' });
assert.equal(addressed.respond, true);
assert.equal(addressed.reason, 'direct_address');
assert.equal(addressed.addressed, true);
assert.equal(addressed.message, 'hello');

const voice = routeIncomingMessage({ ...common, source: 'SynaMic', message: '你好', channel: 'voice' });
assert.equal(voice.respond, true);
assert.equal(voice.reason, 'voice');
assert.equal(voice.addressed, true);
assert.equal(voice.message, '你好');

console.log('message router tests ok');
