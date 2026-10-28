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
  return setDoc(doc(client(uid), 'coachingQuestions', data.id), data);
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
