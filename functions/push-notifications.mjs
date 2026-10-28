import { onDocumentCreated, onDocumentUpdated } from 'firebase-functions/v2/firestore';
import { getFirestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';
import { createPushSender, connectionEvent } from './push-delivery.mjs';

const options = { region: 'asia-southeast1', maxInstances: 3, timeoutSeconds: 60, retry: true };
const send = (uid, event) => createPushSender({ db: getFirestore(), messaging: getMessaging() })(uid, event);

export const notifyPlayerOnCoachNote = onDocumentCreated(
  { ...options, document: 'notes/{noteId}' }, async event => {
    const note = event.data?.data();
    if (!note?.playerUid || typeof note.text !== 'string' || !note.text.trim()) return;
    await send(note.playerUid, { type: 'feedback', eventId: `feedback:${event.params.noteId}`,
      title: 'Coach feedback', body: note.text.trim().slice(0, 240),
      createdAt: note.createdAt, referenceId: note.sessionId });
  },
);

export const notifyPlayerOnAssignment = onDocumentCreated(
  { ...options, document: 'assignments/{assignmentId}' }, async event => {
    const assignment = event.data?.data();
    if (!assignment?.playerUid || typeof assignment.title !== 'string') return;
    await send(assignment.playerUid, { type: 'assignment', eventId: `assignment:${event.params.assignmentId}`,
      title: 'New training assignment', body: assignment.title.slice(0, 240),
      createdAt: assignment.createdAt, referenceId: event.params.assignmentId });
  },
);

export const notifyCoachOnSubmission = onDocumentCreated(
  { ...options, document: 'submissions/{submissionId}' }, async event => {
    const submission = event.data?.data();
    if (!submission?.coachUid || !submission?.playerUid) return;
    const coach = await getFirestore().doc(`coaches/${submission.coachUid}`).get();
    if (coach.data()?.notifySubmissions === false) return;
    await send(submission.coachUid, { type: 'submission',
      eventId: `submission:${submission.playerUid}:${submission.sessionId}:${submission.submittedAt}`,
      title: 'Session ready to review', body: 'A player submitted a training session.',
      createdAt: submission.submittedAt, referenceId: submission.sessionId });
  },
);

export const notifyPlayerOnCoachConnection = onDocumentUpdated(
  { ...options, document: 'roster/{coachUid}/members/{playerUid}' }, async event => {
    const update = connectionEvent(event.params.coachUid, event.data?.before.data(), event.data?.after.data());
    if (update) await send(event.params.playerUid, update);
  },
);
