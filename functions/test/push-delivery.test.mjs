import test from 'node:test';
import assert from 'node:assert/strict';
import { createPushSender, connectionEvent } from '../push-delivery.mjs';

function harness({ count = 1, deleting = false, result, changedOwner = false } = {}) {
  const sent = [], removed = [];
  const db = {
    doc(path) { return { path, async get() { return { exists: deleting }; } }; },
    collection(name) {
      assert.equal(name, 'pushTokens');
      return { where(field, op, uid) {
        assert.deepEqual([field, op, uid], ['uid', '==', 'player']);
        return { async get() { return { docs: Array.from({ length: count }, (_, i) => ({ id: `fid-${i}` })) }; } };
      } };
    },
    async runTransaction(action) {
      await action({ async get() { return { data: () => ({ uid: changedOwner ? 'other' : 'player' }) }; },
        delete(ref) { removed.push(ref.path); } });
    },
  };
  const messaging = { async sendEachForMulticast(message) {
    sent.push(message);
    return { responses: message.fids.map(() => result ?? { success: true }) };
  } };
  return { send: createPushSender({ db, messaging }), sent, removed };
}
const event = { type: 'feedback', eventId: 'feedback:note', title: 'Coach feedback', body: 'Keep practicing.', createdAt: 1000 };

test('pushes carry account and stable inbox identity without automatic system display', async () => {
  const h = harness();
  await h.send('player', event);
  const message = h.sent[0];
  assert.deepEqual(message.fids, ['fid-0']);
  assert.equal(message.data.uid, 'player');
  assert.equal(message.data.createdAt, '1000');
  assert.equal(message.data.eventId, 'feedback:note');
  assert.equal(message.notification, undefined);
  assert.equal(message.android.priority, 'high');
});

test('empty devices, signed-out recipients and deletion fences never send', async () => {
  for (const options of [{ count: 0 }, { deleting: true }]) {
    const h = harness(options);
    await h.send('player', event);
    assert.equal(h.sent.length, 0);
  }
  const h = harness();
  await h.send(null, event);
  assert.equal(h.sent.length, 0);
});

test('delivery splits device lists at the 500-recipient FCM limit', async () => {
  const h = harness({ count: 1001 });
  await h.send('player', event);
  assert.deepEqual(h.sent.map(message => message.fids.length), [500, 500, 1]);
});

test('expired device registrations are removed without deleting a new account registration', async () => {
  const result = { success: false, error: { code: 'messaging/registration-token-not-registered' } };
  const h = harness({ result });
  await h.send('player', event);
  assert.deepEqual(h.removed, ['pushTokens/fid-0']);
  const changed = harness({ result, changedOwner: true });
  await changed.send('player', event);
  assert.deepEqual(changed.removed, []);
});

test('transient delivery failures propagate for trigger retry without removing devices', async () => {
  const h = harness({ result: { success: false, error: { code: 'messaging/server-unavailable' } } });
  await assert.rejects(h.send('player', event), /push_delivery_retry/);
  assert.deepEqual(h.removed, []);
});

test('connection decisions have stable identities and skip pending or unrelated edits', () => {
  const pending = { status: 'Pending', requestedAt: 1000, decidedAt: null };
  const active = { ...pending, status: 'Active', decidedAt: 2000 };
  const approved = connectionEvent('coach', pending, active);
  assert.equal(approved.eventId, 'connection:coach:1000:Active:2000');
  assert.equal(approved.title, 'Coach request approved');
  assert.equal(connectionEvent('coach', active, { ...active, playerName: 'Updated' }), null);
  assert.equal(connectionEvent('coach', null, pending), null);
  assert.equal(connectionEvent('coach', active, pending), null);
  assert.equal(connectionEvent('coach', pending, { ...active, decidedAt: null }), null);
  const ended = connectionEvent('coach', pending, { ...active, status: 'Ended' });
  assert.equal(ended.title, 'Coach connection ended');
  assert.equal(ended.eventId, 'connection:coach:1000:Ended:2000');
});
