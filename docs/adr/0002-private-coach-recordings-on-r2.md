# Store coach recordings privately in Cloudflare R2

Explicitly submitted training videos and pose feedback use private Cloudflare R2
objects through the existing Worker, while Firebase Auth and Firestore retain
identity, sharing, and coach receipts. Firebase Storage activation would require
a separate Firebase billing upgrade; the user has activated R2 instead. The Worker
reuses the deployed Firestore authorization gate so only the owner or an explicitly
shared coach with an Active roster connection can read recordings, and no R2 key
or public video URL enters either client.

The local recording remains on the player's phone. Cloud objects have no automatic
expiry; the proposed seven-day retention was withdrawn. Switching providers later
requires migrating those retained objects. Existing Firebase recording downloads
remain supported on Android; account cleanup must be extended to R2 before the
currently inactive Firebase account-deletion service is enabled.

## Automatic private backup, 23 November 2026

The owner requested cloud backups so recordings can survive reinstalling the app.
This revises the original restriction to explicitly submitted coach recordings.
Saving a signed-in training recording now schedules a persistent WorkManager
upload of the video and feedback to the same private R2 objects. Opening the app
also queues existing local recordings. A ready marker is acknowledged by the
server before the result screen confirms backup. Transient failures retry with
network connectivity; recordings larger than 90 MB remain local and are labelled.

After signing into the same account on another installation, the existing session
history restoration brings back the session summary. The result screen offers
an authenticated download of the video and retained feedback. Coach visibility
continues to require explicit session sharing. Removing a downloaded phone copy
keeps the cloud recording available. Uninstalling before backup completes can
still lose a local recording. Already deleted videos cannot be recovered by this
feature. Existing session history limits still apply.

### Automatic restoration

The owner requested that backed-up videos return automatically after reinstalling.
After the session history loads, persistent background work retrieves missing
recordings and feedback for that signed-in account. Owner downloads are kept in
private persistent storage. The result screen observes the restored file path
and shows restoration status while waiting for connectivity. Interrupted
downloads retry; a late result cannot recreate a deleted session or attach to a
different account. An account deletion also clears its restored private files.
