# Account and recording services

Shared student/coach profile pictures, banner customization, visibility and legacy
photo migration are described in [profile-media.md](profile-media.md).

The Android account card shows the account name and country flag. The Edit account route holds separate first/last names and the searchable country/address selectors. Address details remain in owner-private `profiles/{uid}` and never enter coach directory or roster projections. Saved training preferences and photos survive identity edits. The profile status distinguishes checking, saving, server-confirmed saved, cached/waiting, and failed. Retry resends failed operations for the current UID.

## Private coach recording access

Analysis runs on the phone. Ordinary saves mirror only the measured session summary. When the player explicitly sends a recorded session to their coach, `SharedRecordingStore` uploads the MP4 and serialized `VideoReview` containing the captured skeleton and error markers. Older recordings are analyzed automatically if their feedback cache is missing. Both objects must finish uploading before the ready marker and coach receipt are written.

- R2 bucket: private `volleyball-lab-recordings`, bound as `RECORDINGS` on the existing Cloudflare Worker. Paths: `recordings/{ownerUid}/{sessionId}/video.mp4` and `review.json`.
- Limits: new video uploads 90 MiB (below Workers Free's 100 MB request ceiling), feedback 10 MiB; matching MIME types and owner identity required. Android can still download legacy Firebase recordings up to 100 MiB.
- `recordings/{sessionId}`: ownerUid, sessionId, version=1, videoBytes, reviewBytes, ready=true.
- Read authorization: the owner or an explicitly shared COACH with an Active roster connection, with both deletion fences checked through the deployed `aiSessionAccess` virtual Firestore gate. Every transfer uses the caller's Firebase ID token, verified by Google through Firestore REST. The Worker has no Firebase administrator credential. No public URLs or R2 client credentials are created.
- Uploads are streamed through authenticated PUT endpoints; R2 conditional creates prevent concurrent retries from replacing a completed object. POST `complete` checks both objects and creates a private R2 ready marker before the Android Firestore ready marker and coach receipt. The receipt and session sharing now commit together in one batch.
- The player's original private local video and review remain untouched by upload. Cloud recordings have no seven-day expiry or automatic lifecycle rule. Local video deletion does not delete the uploaded copy; deleting the cloud session removes access through the authorization gate, but physical R2 objects remain until separately removed by an administrator. R2 usage beyond the free allowance is billable.
- Coach downloads use account-scoped private cache files. Server permission is checked again before a cached copy is opened. Restored feedback feeds the same annotated playback component used by the player, without another analysis pass.
- Existing sessions without a ready marker display an explicit summary-only message. Failed uploads preserve the player's local recording for retry.
- The coach portal offers a Load video control on each expanded session. It fetches with a bearer header and plays a browser-local blob URL, cleared on sign-out, account switch, or dashboard disposal. Browser video playback loads the complete video before playing; annotated pose playback remains available in the Android coach screen.

## Account deletion

The confirmation requires typing DELETE and password/Google reauthentication. The callable `deleteAccount` enforces App Check and authentication within five minutes. It derives the cleanup UID only from the verified token. The confirmation's `expectedUid` must match that token, so switching accounts during confirmation is rejected; it never selects another account to delete. Extra target identifiers are rejected.

Cleanup first creates the administrator-only `accountDeletions/{uid}` fence. Rules block late writes from that account and new coach relationships targeting it. The server removes owned sessions/recording manifests, assignments, submissions, notes, roster links, private/public profile records, billing mappings, and recording objects. Deleted coaches are removed from surviving players' share lists. Other users' profiles and recordings remain intact. Authentication is deleted last. Repeated calls safely resume a partial cleanup. A trusted `resumeAccountDeletion` creation trigger retries interrupted cleanup independently of the phone. The retained fence holds status and a server timestamp, without profile/address fields.

The client clears only that UID's local preferences, private videos and feedback, photo, and downloaded playback cache after confirmed server deletion. A server-confirmed previous deletion can finish local cleanup after an interrupted response. Configuration changes do not cancel the confirmed server cleanup.

## Activation status

On November 3, 2026, R2 recording transfers were deployed to the existing `volleyball-lab-session-ai` Worker. They use the existing Firebase Auth and Firestore deployment and do not require Firebase Storage or Firebase Functions activation. Android selects the public Worker origin through `recordingStorageUrl` / `RECORDING_STORAGE_URL`; the portal uses `VITE_RECORDING_STORAGE_URL`, defaulting to that deployed origin. No expiry lifecycle was configured.

Firebase account deletion remains a separate, inactive deployment requiring Blaze. Its existing cleanup covers Firebase recording objects only: extend it to physically remove the owner's R2 objects before activation. The deletion fence already prevents R2 reads after deletion starts, but it does not remove bytes from R2. No Firebase billing upgrade or real account deletion was performed in this change. Keep billing/payment endpoints out of recording deployments.

## Verification

Android unit tests exercise restored edit selections, preservation of unrelated preferences, account-switch protection, and sync status/retry scoping. Firestore emulator tests cover private profiles, recording manifests, deletion fences and existing coach workflows. Administrator cleanup tests check ownership, retry behavior, and deletion order. Storage emulator tests cover private player/coach playback, unrelated/anonymous rejection, invalid types/size, missing or spoofed sessions, and revoked access. These tests use demo projects and synthetic data, never production users.

R2 verification on November 3, 2026: 26 Worker tests, 24 portal tests, and 357 Android unit tests pass. Android lint completed with zero errors (160 existing warnings and five hints), and the optimized 0.1.16 preview APK was built. Eighteen hosted checks with newly created synthetic accounts verified private R2 uploads, two-object completion, real Firestore manifest writes, atomic submission/sharing, byte-exact coach downloads, owner retries, revoked coach access and deletion-fence rejection. All synthetic accounts, documents and the three exact R2 test objects were removed. The deployed coach portal serves the new playback controls, and the R2 bucket's public r2.dev access is disabled. This is transfer verification, not a claim of physical player/coach device validation.
