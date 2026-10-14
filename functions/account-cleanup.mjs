/** Auth ownership must be established before calling this administrator-only operation. */
export async function removeAccountData({ db, bucket, auth, fieldValue }, uid) {
  if (typeof uid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) throw new Error('invalid_uid');
  const job = db.doc(`accountDeletions/${uid}`);
  await job.set({ status: 'deleting', updatedAt: fieldValue.serverTimestamp() }, { merge: true });
  // The marker fences client writes. Each phase is safe to repeat after a timeout.
  async function erase(query, beforeDelete = async () => {}) {
    while (true) {
      const page = await query.limit(200).get();
      if (page.empty) break;
      for (const doc of page.docs) await beforeDelete(doc);
      const batch = db.batch();
      page.docs.forEach(doc => batch.delete(doc.ref));
      await batch.commit();
    }
  }
  await erase(db.collection('sessions').where('ownerUid', '==', uid));
  await erase(db.collection('recordings').where('ownerUid', '==', uid));
  for (const collection of ['assignments', 'submissions', 'notes']) {
    for (const field of ['playerUid', 'coachUid']) await erase(db.collection(collection).where(field, '==', uid));
  }
  await erase(db.collectionGroup('members').where('playerUid', '==', uid));
  await db.recursiveDelete(db.doc(`roster/${uid}`));
  // A deleted coach loses playback access to surviving players' sessions.
  while (true) {
    const page = await db.collection('sessions').where('sharedWith', 'array-contains', uid).limit(200).get();
    if (page.empty) break;
    const batch = db.batch();
    page.docs.forEach(doc => batch.update(doc.ref, { sharedWith: fieldValue.arrayRemove(uid) }));
    await batch.commit();
  }
  await erase(db.collection('billingOrders').where('ownerUid', '==', uid), async order => {
    await erase(db.collection('billingSessions').where('orderId', '==', order.id));
    await erase(db.collection('billingPayments').where('orderId', '==', order.id));
  });
  for (const collection of ['profiles', 'users', 'coaches', 'coachDirectory', 'entitlements', 'billingPending'])
    await db.doc(`${collection}/${uid}`).delete();
  await bucket.deleteFiles({ prefix: `recordings/${uid}/`, force: true });
  // The authentication identity is removed last, after all data operations succeed.
  try { await auth.deleteUser(uid); }
  catch (error) { if (error.code !== 'auth/user-not-found') throw error; }
  // Retain only the UID fence. It prevents a late cached write from restoring deleted data.
  await job.set({ status: 'deleted', updatedAt: fieldValue.serverTimestamp() });
  return { deleted: true };
}

export function validateDeletion(request, nowSeconds = Date.now() / 1000) {
  if (!request.auth?.uid) return 'unauthenticated';
  if (request.data?.confirmation !== 'DELETE' || request.data.expectedUid !== request.auth.uid ||
    Object.keys(request.data).some(key => !['confirmation', 'expectedUid'].includes(key))) return 'invalid-argument';
  const authTime = request.auth.token?.auth_time;
  if (typeof authTime !== 'number' || authTime > nowSeconds + 30 || nowSeconds - authTime > 300) return 'failed-precondition';
  return null;
}
