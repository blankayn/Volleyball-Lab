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
