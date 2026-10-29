import test, { before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, getDocs, collection, query, where, setDoc, deleteDoc, updateDoc, writeBatch, Timestamp, serverTimestamp } from 'firebase/firestore';

let env;
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-volleyball-lab', firestore: { rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } }); });
after(async () => { await env?.cleanup(); });
const client = uid => env.authenticatedContext(uid).firestore();
async function seed(path, data) { await env.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), path), data)); }
beforeEach(async () => {
  await env.clearFirestore();
  await seed('coachDirectory/coach', { name: 'Coach' });
  await seed('users/coach', { role: 'COACH' });
  await seed('roster/coach/members/player', { playerUid: 'player', status: 'Active' });
  await seed('submissions/coach_session', { coachUid: 'coach', playerUid: 'player', sessionId: 'session', reviewedAt: Date.now() });
});
const qid = 'coach_player_intro';
const question = (sessionId = null) => ({ id: `coach_player_${sessionId ?? 'intro'}`, coachUid: 'coach', playerUid: 'player', sessionId,
  topic: sessionId ? 'Technique' : 'FirstDrill', text: 'What should I practice first?', createdAt: serverTimestamp(), answer: null, answeredAt: null });
async function ask(uid = 'player', sessionId = null, patch = {}) {
  const data = { ...question(sessionId), ...patch };
  const db = client(uid); const batch = writeBatch(db);
  batch.set(doc(db, 'coachingQuestions', data.id), data);
  batch.set(doc(db, 'coachingLocks', 'coach_player'), { coachUid: 'coach', playerUid: 'player', questionId: data.id });
  return batch.commit();
}
async function answer(uid = 'coach', id = qid, patch = {}, metricPatch = {}) {
  const db = client(uid);
  const ref = doc(db, 'coachingQuestions', id);
  const current = await getDoc(ref);
  const batch = writeBatch(db);
  batch.update(ref, { answer: 'Start with the ready-position drill.', answeredAt: serverTimestamp(), ...patch });
  batch.set(doc(db, 'coachResponseTimes', id), { coachUid: uid, askedAt: current.data().createdAt, answeredAt: serverTimestamp(), ...metricPatch });
  return batch.commit();
}

test('an approved new player can ask without session history; private history remains readable after leaving', async () => {
  await assertSucceeds(ask());
  await assertSucceeds(getDocs(query(collection(client('coach'), 'coachingQuestions'), where('coachUid', '==', 'coach'))));
  await assertFails(getDoc(doc(client('other'), 'coachingQuestions', qid)));
  await seed('roster/coach/members/player', { playerUid: 'player', status: 'Ended' });
  await assertSucceeds(getDoc(doc(client('player'), 'coachingQuestions', qid)));
  await assertFails(answer());
});
test('pending players, oversized questions, attachments and mismatched topics cannot create conversations', async () => {
  await assertFails(ask('player', null, { text: 'x'.repeat(301) }));
  await assertFails(ask('player', null, { attachment: 'file' }));
  await assertFails(ask('player', null, { text: 'Look here\nHTTPS://example.com\nplease' }));
  await assertFails(ask('player', null, { topic: 'Technique' }));
  await seed('roster/coach/members/player', { playerUid: 'player', status: 'Pending' });
  await assertFails(ask());
});
test('one question per context requires the actual reviewed submission', async () => {
  await assertSucceeds(ask('player', 'session'));
  await assertFails(ask('player', 'session', { text: 'A second question' }));
  await assertFails(ask('player', 'unshared'));
  await seed('submissions/coach_pending', { coachUid: 'coach', playerUid: 'player', sessionId: 'pending', reviewedAt: null });
  await assertFails(ask('player', 'pending'));
});
test('only the coach can answer once; response telemetry must commit atomically and match the original timestamp', async () => {
  await ask();
  await assertFails(updateDoc(doc(client('player'), 'coachingQuestions', qid), { answer: 'Fake', answeredAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(client('coach'), 'coachingQuestions', qid), { answer: 'No timing receipt', answeredAt: serverTimestamp() }));
  await assertFails(answer('coach', qid, { text: 'Rewritten player question' }));
  await assertFails(answer('coach', qid, {}, { askedAt: Timestamp.fromMillis(1) }));
  await assertSucceeds(answer());
  const record = await getDoc(doc(client('player'), 'coachingQuestions', qid));
  const timing = await getDoc(doc(client('other'), 'coachResponseTimes', qid));
  assert.equal(record.data().answeredAt.toMillis(), timing.data().answeredAt.toMillis());
  await assertFails(answer());
  await assertFails(setDoc(doc(client('coach'), 'coachResponseTimes/fake'), { coachUid: 'coach', askedAt: Timestamp.now(), answeredAt: serverTimestamp() }));
});

const review = (patch = {}) => ({ id: 'coach_player_session', coachUid: 'coach', playerUid: 'player', sessionId: 'session', rating: 4,
  text: 'Clear training guidance.', createdAt: serverTimestamp(), updatedAt: serverTimestamp(), ...patch });

const qref = uid => doc(client(uid), 'coachingQuestions', qid);
const follow = (field, text = 'Should I keep my platform steady?') => updateDoc(qref('player'), {
  [field]: { text, createdAt: serverTimestamp(), answer: null, answeredAt: null },
});
const followAnswer = field => updateDoc(qref('coach'), { [`${field}.answer`]: 'Keep your shoulders level.', [`${field}.answeredAt`]: serverTimestamp() });

test('one open question per connection is enforced atomically; only resolved threads release the lock', async () => {
  await assertSucceeds(ask());
  await assertFails(ask('player', 'session'));
  await assertFails(setDoc(doc(client('player'), 'coachingQuestions/no-lock'), { ...question(), id: 'no-lock' }));
  await assertFails(updateDoc(qref('player'), { resolvedAt: serverTimestamp() }));
  await answer();
  await assertSucceeds(updateDoc(qref('player'), { resolvedAt: serverTimestamp() }));
  await assertSucceeds(ask('player', 'session'));
});

test('two simultaneous new questions cannot both acquire the same connection lock', async () => {
  const attempts = await Promise.allSettled([
    ask('player', null, { id: 'race-a' }), ask('player', null, { id: 'race-b' }),
  ]);
  assert.equal(attempts.filter(entry => entry.status === 'fulfilled').length, 1);
  assert.equal(attempts.filter(entry => entry.status === 'rejected').length, 1);
});

test('only two follow-ups are possible, turns alternate, and existing messages cannot be rewritten', async () => {
  await ask();
  await assertFails(follow('followUp1'));
  await answer();
  await assertSucceeds(follow('followUp1'));
  await assertFails(follow('followUp2'));
  await assertFails(updateDoc(qref('coach'), { 'followUp1.text': 'Rewritten' }));
  await assertSucceeds(followAnswer('followUp1'));
  await assertFails(followAnswer('followUp1'));
  await assertSucceeds(follow('followUp2'));
  await assertSucceeds(followAnswer('followUp2'));
  const latest = (await getDoc(qref('coach'))).data().followUp2.createdAt;
  await assertSucceeds(updateDoc(qref('coach'), { coachReadAt: latest }));
  await assertFails(follow('followUp3'));
  await assertSucceeds(updateDoc(qref('coach'), { resolvedAt: serverTimestamp() }));
  await assertFails(updateDoc(qref('coach'), { resolvedAt: null }));
  await assertFails(follow('followUp1'));
});

test('read markers are participant-owned, monotonic, and cannot be forged into the future', async () => {
  await ask();
  const created = (await getDoc(qref('coach'))).data().createdAt;
  await assertSucceeds(updateDoc(qref('coach'), { coachReadAt: created }));
  await assertFails(updateDoc(qref('player'), { coachReadAt: created }));
  await assertFails(updateDoc(qref('other'), { coachReadAt: created }));
  await assertFails(updateDoc(qref('coach'), { coachReadAt: Timestamp.fromMillis(created.toMillis() - 1) }));
  await assertFails(updateDoc(qref('coach'), { coachReadAt: Timestamp.fromMillis(Date.now() + 100000) }));
  await answer();
  const answered = (await getDoc(qref('player'))).data().answeredAt;
  await assertSucceeds(updateDoc(qref('player'), { playerReadAt: answered }));
});

test('disconnected and pending members cannot send follow-ups, reply, resolve, or start a new thread', async () => {
  await ask(); await answer();
  await seed('roster/coach/members/player', { playerUid: 'player', status: 'Ended' });
  await assertFails(follow('followUp1'));
  await assertFails(updateDoc(qref('player'), { resolvedAt: serverTimestamp() }));
  await assertFails(ask('player', 'session'));
  await assertSucceeds(getDoc(qref('player')));
  const at = (await getDoc(qref('coach'))).data().createdAt;
  await assertSucceeds(updateDoc(qref('coach'), { coachReadAt: at }));
});

test('contact details and attachments are blocked for initial questions, responses and follow-ups', async () => {
  for (const text of ['   ', 'Call 0917 123 4567', 'Email me player@example.com', 'DM @player123', 'example.com', 'coach.ph', 'training.xyz', 'WhatsApp me']) {
    await assertFails(ask('player', null, { text }));
  }
  await assertSucceeds(ask('player', null, { text: 'Should I do 3 sets of 10 reps?' }));
  await assertFails(answer('coach', qid, { answer: 'Call 0917 123 4567' }));
  await answer();
  await assertFails(follow('followUp1', 'Message @player123'));
  await assertFails(updateDoc(qref('player'), { followUp1: { text: 'Training help', createdAt: serverTimestamp(), answer: null, answeredAt: null, attachment: 'file' } }));
});
test('ratings are verified, bounded, player-owned and editable without changing their session or creation date', async () => {
  const ref = doc(client('player'), 'coachReviews/coach_player_session');
  await assertFails(setDoc(ref, review({ rating: 6 })));
  await assertFails(setDoc(ref, review({ rating: 0 })));
  await assertFails(setDoc(ref, review({ text: 'x'.repeat(501) })));
  await assertFails(setDoc(doc(client('coach'), 'coachReviews/coach_player_session'), review()));
  await assertSucceeds(setDoc(ref, review()));
  const createdAt = (await getDoc(ref)).data().createdAt;
  await assertSucceeds(updateDoc(ref, { rating: 2, text: 'Updated review', updatedAt: serverTimestamp() }));
  assert.equal((await getDoc(ref)).data().createdAt.toMillis(), createdAt.toMillis());
  await assertFails(updateDoc(ref, { coachUid: 'other', updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(client('coach'), 'coachReviews/coach_player_session'), { rating: 5, updatedAt: serverTimestamp() }));
  await assertSucceeds(getDoc(doc(client('other'), 'coachReviews/coach_player_session')));
});
test('unreviewed sessions cannot earn a coach review', async () => {
  await seed('submissions/coach_session', { coachUid: 'coach', playerUid: 'player', sessionId: 'session', reviewedAt: null });
  await assertFails(setDoc(doc(client('player'), 'coachReviews/coach_player_session'), review()));
});
test('legacy manual availability is read-only', async () => {
  await assertFails(setDoc(doc(client('coach'), 'coachAvailability/coach'), { coachUid: 'coach', status: 'Busy', updatedAt: serverTimestamp() }));
});

const presence = (patch = {}) => ({ coachUid: 'coach', status: 'Online', updatedAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + 120_000), ...patch });
test('listed coaches publish independent fresh device leases and signed-in players can read them', async () => {
  await assertSucceeds(setDoc(doc(client('coach'), 'coachPresence/phone'), presence()));
  await assertSucceeds(setDoc(doc(client('coach'), 'coachPresence/browser'), presence({ status: 'Busy' })));
  const records = await assertSucceeds(getDocs(query(collection(client('player'), 'coachPresence'), where('coachUid', '==', 'coach'))));
  assert.equal(records.size, 2);
  await assertSucceeds(deleteDoc(doc(client('coach'), 'coachPresence/phone')));
  assert.equal((await getDoc(doc(client('player'), 'coachPresence/browser'))).data().status, 'Busy');
});
test('players cannot spoof a coach, overwrite another connection or publish their own coach status', async () => {
  await assertFails(setDoc(doc(client('player'), 'coachPresence/fake'), presence()));
  await seed('users/player', { role: 'PLAYER' });
  await seed('coachDirectory/player', { name: 'Old listing' });
  await assertFails(setDoc(doc(client('player'), 'coachPresence/fake'), presence({ coachUid: 'player' })));
  await setDoc(doc(client('coach'), 'coachPresence/phone'), presence());
  await assertFails(updateDoc(doc(client('player'), 'coachPresence/phone'), { status: 'Offline', updatedAt: serverTimestamp() }));
  await assertFails(deleteDoc(doc(client('player'), 'coachPresence/phone')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'coachPresence/phone')));
});
test('presence rejects manual capacity, arbitrary fields, client update times and stale or excessive leases', async () => {
  const ref = doc(client('coach'), 'coachPresence/phone');
  await assertFails(setDoc(ref, presence({ status: 'FullyBooked' })));
  await assertFails(setDoc(ref, presence({ email: 'private@example.test' })));
  await assertFails(setDoc(ref, presence({ updatedAt: Timestamp.fromMillis(1) })));
  await assertFails(setDoc(ref, presence({ expiresAt: Timestamp.fromMillis(Date.now() - 1) })));
  await assertFails(setDoc(ref, presence({ expiresAt: Timestamp.fromMillis(Date.now() + 180_000) })));
  const missingExpiry = presence(); delete missingExpiry.expiresAt;
  await assertFails(setDoc(ref, missingExpiry));
  await assertSucceeds(setDoc(ref, presence({ status: 'Offline' })));
});
test('presence cannot change owners and account deletion fences new heartbeats', async () => {
  const ref = doc(client('coach'), 'coachPresence/phone');
  await setDoc(ref, presence());
  await assertFails(setDoc(ref, presence({ coachUid: 'player' })));
  await seed('accountDeletions/coach', { status: 'deleting' });
  await assertFails(setDoc(ref, presence({ status: 'Busy' })));
});
