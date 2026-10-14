import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { getAuth } from 'firebase-admin/auth';
import { removeAccountData, validateDeletion } from './account-cleanup.mjs';

export const deleteAccount = onCall({ region: 'asia-southeast1', enforceAppCheck: true,
  maxInstances: 2, timeoutSeconds: 540, memory: '256MiB' }, async request => {
  const complaint = validateDeletion(request);
  if (complaint) throw new HttpsError(complaint, 'Confirm your account again before deleting it.');
  try {
    return await removeAccountData({ db: getFirestore(), bucket: getStorage().bucket(),
      auth: getAuth(), fieldValue: FieldValue }, request.auth.uid);
  } catch (error) {
    console.error('Account deletion incomplete', { code: error.code ?? 'cleanup_failed' });
    throw new HttpsError('unavailable', 'Deletion did not finish. Retry account deletion to resume safely.');
  }
});

// A trusted job creation also schedules cleanup independently of the phone's connection.
// Retried delivery is safe because every cleanup phase is idempotent.
export const resumeAccountDeletion = onDocumentCreated({ document: 'accountDeletions/{uid}',
  region: 'asia-southeast1', retry: true, maxInstances: 2, timeoutSeconds: 540, memory: '256MiB' }, async event => {
  await removeAccountData({ db: getFirestore(), bucket: getStorage().bucket(),
    auth: getAuth(), fieldValue: FieldValue }, event.params.uid);
});
