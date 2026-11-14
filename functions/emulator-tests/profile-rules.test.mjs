import test, { before, after, beforeEach } from 'node:test';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, collection, getDoc, getDocs, setDoc, updateDoc, deleteDoc, deleteField, serverTimestamp, Timestamp } from 'firebase/firestore';
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-volleyball-profiles', firestore: {
  rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });
const db = uid => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const ref = (uid = 'player', owner = uid) => doc(db(uid), `profiles/${owner}`);
const option = (id, name) => ({ id, name });
const address = () => ({ country: option('PH', 'Philippines'), area: option('0702200000', 'Cebu'),
  city: option('0730600000', 'City of Cebu'), barangay: option('0730600041', 'Lahug') });
const profile = () => ({ name: 'Kirt Deniel Magno', firstName: 'Kirt Deniel', lastName: 'Magno', address: address(), updatedAt: serverTimestamp() });

test('private profile belongs only to owner; coach, other users and anonymous cannot read/write/list', async () => {
  await assertSucceeds(setDoc(ref(), profile()));
  await assertSucceeds(getDoc(ref()));
  for (const uid of ['coach', 'other', null]) {
    await assertFails(getDoc(ref(uid, 'player')));
    await assertFails(setDoc(ref(uid, 'player'), profile()));
    await assertFails(updateDoc(ref(uid, 'player'), { name: 'Hijacked', updatedAt: serverTimestamp() }));
    await assertFails(deleteDoc(ref(uid, 'player')));
    await assertFails(getDocs(collection(db(uid), 'profiles')));
  }
  await assertFails(deleteDoc(ref()));
});
test('partial identity/address patches and country replacement work without retaining old subdivisions', async () => {
  await assertSucceeds(setDoc(ref(), { address: address(), updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(ref(), { firstName: 'Kirt', updatedAt: serverTimestamp() }));
  const replacement = { country: option('US', 'United States'), area: null, city: null, barangay: null };
  await assertSucceeds(updateDoc(ref(), { address: replacement, updatedAt: serverTimestamp() }));
  const saved = (await getDoc(ref())).data();
  if (saved.address.city !== null || saved.address.barangay !== null || saved.firstName !== 'Kirt') throw new Error('Stale address or erased name');
  await assertSucceeds(updateDoc(ref(), { address: null, updatedAt: serverTimestamp() }));
});
test('invalid fields, role injection, large strings and wrong types fail on create and update', async () => {
  const mutations = [
    { role: 'COACH' }, { ownerUid: 'other' }, { extraData: 'x' }, { name: 'x'.repeat(82) },
    { firstName: 'x'.repeat(41) }, { lastName: 7 }, { updatedAt: Date.now() }, { updatedAt: Timestamp.fromMillis(0) },
    { address: { ...address(), extra: 'x' } },
    { address: { ...address(), country: option('php', 'Philippines') } },
    { address: { ...address(), country: option('PH', 'x'.repeat(161)) } },
    { address: { ...address(), area: option('x'.repeat(33), 'Cebu') } },
    { address: { ...address(), city: { id: 'city', name: 1 } } },
    { address: { ...address(), barangay: { ...option('b', 'Lahug'), secret: 'x' } } },
    { address: { ...address(), area: null } }, { address: { ...address(), city: null } },
    { address: { ...address(), country: option('US', 'United States') } }, { address: [] },
  ];
  for (const [index, mutation] of mutations.entries()) {
    await assertFails(setDoc(doc(db('player'), `profiles/player-bad-${index}`), { ...profile(), ...mutation }));
    // Test invalid CREATE at the actual owner's path too, avoiding an ownership-only rejection.
    await assertFails(setDoc(ref(), { ...profile(), ...mutation }));
  }
  await assertSucceeds(setDoc(ref(), profile()));
  for (const mutation of mutations) await assertFails(updateDoc(ref(), { updatedAt: serverTimestamp(), ...mutation }));
  await assertFails(updateDoc(ref(), { updatedAt: deleteField() }));
  await assertFails(setDoc(ref('new-owner'), { name: 'No timestamp' }));
});

const starterPlan = () => ({ focusSkill: 'Serving', weeklySessionGoal: 3, goalText: 'Keep serves in',
  experience: 'New', concern: 'Aiming', summary: 'Start with serving practice.', source: 'groq',
  starterDrillId: 'serve-standing-float', completedAtEpochMs: 123,
  reason: 'You chose serving.', questionId: 'serving', optionId: 'aim' });
const trainingSetup = () => ({ version: 1, hasOnboarded: true,
  assessment: { ratings: { Serving: 'Okay', Receiving: 'Confident', Setting: 'Struggling', Spiking: 'Okay', Blocking: 'Confident' }, experience: 'New', completedAtEpochMs: 123 }, plan: starterPlan() });

test('confirmed training setup and independent goal updates are private, bounded, and merge compatible', async () => {
  await assertSucceeds(setDoc(ref(), { ...profile(), trainingSetup: trainingSetup(),
    goalSkill: 'Serving', weeklySessionGoal: 3, goalText: 'Keep serves in', position: null, dominantHand: 'Left' }));
  await assertSucceeds(updateDoc(ref(), { name: 'Updated name', updatedAt: serverTimestamp() }));
  const saved = (await getDoc(ref())).data();
  if (saved.trainingSetup.plan.focusSkill !== 'Serving' || saved.trainingSetup.assessment.ratings.Receiving !== 'Confident')
    throw new Error('Profile edit erased saved setup');
  for (const uid of ['coach', 'other', null]) await assertFails(getDoc(ref(uid, 'player')));
  const invalid = [
    { version: 2 }, { transcript: 'Raw provider prompt' }, { hasOnboarded: false },
    { assessment: { ratings: { Serving: 'AI guessed' }, experience: 'New', completedAtEpochMs: 123 } },
    { plan: { ...starterPlan(), score: 99 } }, { plan: { ...starterPlan(), weeklySessionGoal: 0 } },
    { plan: { ...starterPlan(), weeklySessionGoal: 3.5 } }, { plan: { ...starterPlan(), goalText: 'x'.repeat(161) } },
    { plan: { ...starterPlan(), starterDrillId: 'serve-jump-float' } },
    { plan: { ...starterPlan(), starterDrillId: 'receive-wall-platform' } },
    { plan: { ...starterPlan(), source: 'measured' } },
  ];
  for (const mutation of invalid) await assertFails(updateDoc(ref(), {
    trainingSetup: { ...trainingSetup(), ...mutation }, updatedAt: serverTimestamp() }));
  for (const mutation of [{ weeklySessionGoal: 15 }, { goalText: 'x'.repeat(161) }, { position: 'Invented' }, { dominantHand: 'Both' }])
    await assertFails(updateDoc(ref(), { ...mutation, updatedAt: serverTimestamp() }));
});

test('skipped setup restores completion without inventing a plan and deletion blocks setup access', async () => {
  await assertSucceeds(setDoc(ref(), { trainingSetup: { ...trainingSetup(), plan: null }, updatedAt: serverTimestamp() }));
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'accountDeletions/player'), { status: 'pending' });
  });
  await assertFails(getDoc(ref()));
  await assertFails(updateDoc(ref(), { trainingSetup: trainingSetup(), updatedAt: serverTimestamp() }));
});
