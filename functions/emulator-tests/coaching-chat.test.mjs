import test, { before, after, beforeEach } from 'node:test';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, getDocs, collection, query, where, setDoc, deleteDoc, updateDoc, Timestamp, serverTimestamp } from 'firebase/firestore';

let env;
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-volleyball-chat', firestore: { rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } }); });
after(async () => { await env?.cleanup(); });
const client = uid => env.authenticatedContext(uid).firestore();
async function seed(path, data) { await env.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), path), data)); }
beforeEach(async () => {
  await env.clearFirestore();
  await seed('roster/coach/members/player', { playerUid: 'player', status: 'Active' });
});
let n = 0;
function send(uid, patch = {}, id = `m${++n}`) {
  return setDoc(doc(client(uid), 'coachingMessages', id), { id, coachUid: 'coach', playerUid: 'player', senderUid: uid,
    text: 'Keep your platform steady.', createdAt: serverTimestamp(), ...patch });
}

test('a coach can start the chat and either side can send several messages in a row', async () => {
  await assertSucceeds(send('coach'));
  await assertSucceeds(send('coach', { text: 'And bend your knees.' }));
  for (let i = 0; i < 5; i++) await assertSucceeds(send('player', { text: `Question ${i + 1} about my serve` }));
  await assertSucceeds(send('coach', { text: 'x'.repeat(1000) }));
});
test('only the two participants can read a chat', async () => {
  await send('coach', {}, 'first');
  for (const uid of ['coach', 'player']) await assertSucceeds(getDoc(doc(client(uid), 'coachingMessages/first')));
  await assertSucceeds(getDocs(query(collection(client('coach'), 'coachingMessages'), where('coachUid', '==', 'coach'))));
  await assertSucceeds(getDocs(query(collection(client('player'), 'coachingMessages'), where('playerUid', '==', 'player'))));
  await assertFails(getDoc(doc(client('other'), 'coachingMessages/first')));
  await assertFails(getDocs(query(collection(client('other'), 'coachingMessages'), where('coachUid', '==', 'coach'))));
  await assertFails(getDocs(collection(client('coach'), 'coachingMessages')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'coachingMessages/first')));
});
test('contact details, links, empty and oversized text stay blocked for both sides', async () => {
  for (const text of ['   ', '', 'Call 0917 123 4567', 'Email me player@example.com', 'DM @player123', 'example.com', 'coach.ph',
    'https://example.com', 'WhatsApp me', 'add me on\nFacebook', 'x'.repeat(1001)]) {
    await assertFails(send('coach', { text }));
    await assertFails(send('player', { text }));
  }
  await assertSucceeds(send('player', { text: 'Should I do 3 sets of 10 reps?' }));
});
test('nobody can send as someone else, outside their connection, or with extra fields', async () => {
  await assertFails(send('player', { senderUid: 'coach' }));
  await assertFails(send('other'));
  await assertFails(send('other', { playerUid: 'other' }));
  await assertFails(send('coach', { playerUid: 'stranger' }));
  await assertFails(send('coach', { playerUid: 'coach' }));
  await assertFails(send('player', { attachment: 'file' }));
  await assertFails(send('player', { createdAt: Timestamp.fromMillis(1) }));
  await assertFails(send('player', { id: 'mismatch' }));
});
test('pending, ended and deleting accounts cannot send, but history stays readable', async () => {
  await send('player', {}, 'kept');
  for (const status of ['Pending', 'Ended']) {
    await seed('roster/coach/members/player', { playerUid: 'player', status });
    await assertFails(send('player')); await assertFails(send('coach'));
  }
  await assertSucceeds(getDoc(doc(client('player'), 'coachingMessages/kept')));
  await seed('roster/coach/members/player', { playerUid: 'player', status: 'Active' });
  await seed('accountDeletions/player', { status: 'deleting' });
  await assertFails(send('player')); await assertFails(send('coach'));
});
test('sent messages cannot be edited or deleted', async () => {
  await send('player', {}, 'fixed');
  for (const uid of ['player', 'coach']) {
    await assertFails(updateDoc(doc(client(uid), 'coachingMessages/fixed'), { text: 'Changed' }));
    await assertFails(deleteDoc(doc(client(uid), 'coachingMessages/fixed')));
  }
  await assertFails(send('player', { text: 'Overwrite' }, 'fixed'));
});
test('read markers belong to one side, only move forward and cannot be in the future', async () => {
  const ref = uid => doc(client(uid), 'coachingReads/coach_player');
  const at = ms => Timestamp.fromMillis(ms);
  const base = { coachUid: 'coach', playerUid: 'player' };
  await assertSucceeds(getDoc(ref('player')));
  await assertFails(setDoc(ref('player'), { ...base, coachReadAt: at(1000) }));
  await assertFails(setDoc(ref('player'), { ...base, playerReadAt: at(Date.now() + 100000) }));
  await assertFails(setDoc(ref('other'), { ...base, playerReadAt: at(1000) }));
  await assertFails(setDoc(doc(client('coach'), 'coachingReads/coach_stranger'), { coachUid: 'coach', playerUid: 'stranger', coachReadAt: at(1000) }));
  await assertSucceeds(setDoc(ref('player'), { ...base, playerReadAt: at(1000) }, { merge: true }));
  await assertSucceeds(setDoc(ref('coach'), { ...base, coachReadAt: at(2000) }, { merge: true }));
  await assertSucceeds(setDoc(ref('player'), { ...base, playerReadAt: at(3000) }, { merge: true }));
  await assertFails(setDoc(ref('player'), { ...base, playerReadAt: at(2000) }, { merge: true }));
  await assertFails(updateDoc(ref('player'), { coachReadAt: at(9000) }));
  await assertFails(updateDoc(ref('coach'), { coachUid: 'other' }));
  await assertFails(updateDoc(ref('coach'), { note: 'x', coachReadAt: at(4000) }));
  await assertFails(getDoc(ref('other')));
  await assertFails(deleteDoc(ref('coach')));
});
