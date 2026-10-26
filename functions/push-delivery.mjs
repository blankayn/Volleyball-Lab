/** Data-only delivery lets Android check the current account and alert preference. */
export function createPushSender({ db, messaging }) {
  return async function sendToUid(uid, event) {
    if (typeof uid !== 'string' || !uid || !event) return;
    if ((await db.doc(`accountDeletions/${uid}`).get()).exists) return;
    const snapshot = await db.collection('pushTokens').where('uid', '==', uid).get();
    const fids = snapshot.docs.map(doc => doc.id);
    const data = Object.fromEntries(Object.entries({ ...event, uid })
      .filter(([, value]) => value !== undefined && value !== null)
      .map(([key, value]) => [key, String(value)]));
    for (let start = 0; start < fids.length; start += 500) {
      const batch = fids.slice(start, start + 500);
      const response = await messaging.sendEachForMulticast({
        fids: batch, data, android: { priority: 'high', ttl: 86_400_000 },
      });
      let retry = false;
      for (const [index, result] of response.responses.entries()) {
        if (result.success) continue;
        if (['messaging/registration-token-not-registered', 'messaging/invalid-registration-token',
          'messaging/installation-not-found'].includes(result.error?.code)) {
          // A device may have changed accounts while FCM was sending.
          const ref = db.doc(`pushTokens/${batch[index]}`);
          await db.runTransaction(async tx => {
            if ((await tx.get(ref)).data()?.uid === uid) tx.delete(ref);
          });
        } else retry = true;
      }
      if (retry) throw new Error('push_delivery_retry');
    }
  };
}

export function connectionEvent(coachUid, before, after) {
  if (!before || !after || before.status === after.status ||
    !['Active', 'Ended'].includes(after.status) || !Number.isFinite(after.decidedAt) ||
    !Number.isFinite(after.requestedAt)) return null;
  const active = after.status === 'Active';
  return {
    type: 'connection',
    eventId: `connection:${coachUid}:${after.requestedAt}:${after.status}:${after.decidedAt}`,
    title: active ? 'Coach request approved' : 'Coach connection ended',
    body: active ? 'Your coach approved your request. Open Coach to see your team.'
      : 'This coach connection is closed. You can choose a coach in the Coach tab.',
    createdAt: after.decidedAt, referenceId: coachUid,
  };
}
