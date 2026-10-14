# Account and recording services

The Android account card shows the account name and country flag. The Edit account route holds separate first/last names and the searchable country/address selectors. Address details remain in owner-private `profiles/{uid}` and never enter coach directory or roster projections. Saved training preferences and photos survive identity edits. The profile status distinguishes checking, saving, server-confirmed saved, cached/waiting, and failed. Retry resends failed operations for the current UID.

## Private coach recording access

Analysis runs on the phone. Ordinary saves mirror only the measured session summary. When the player explicitly sends a recorded session to their coach, `SharedRecordingStore` uploads the MP4 and serialized `VideoReview` containing the captured skeleton and error markers. Older recordings are analyzed automatically if their feedback cache is missing. Both objects must finish uploading before the ready marker and coach receipt are written.

- Storage paths: `recordings/{ownerUid}/{sessionId}/video.mp4` and `review.json`.
- Limits: video 100 MiB, feedback 10 MiB; matching MIME types and owner identity required.
- `recordings/{sessionId}`: ownerUid, sessionId, version=1, videoBytes, reviewBytes, ready=true.
- Read authorization: session owner or UID explicitly present in the session's `sharedWith` list. No public URLs are created.
- Coach downloads use account-scoped private cache files. Server permission is checked again before a cached copy is opened. Restored feedback feeds the same annotated playback component used by the player, without another analysis pass.
- Existing sessions without a ready marker display an explicit summary-only message. Failed uploads preserve the player's local recording for retry.

## Account deletion

The confirmation requires typing DELETE and password/Google reauthentication. The callable `deleteAccount` enforces App Check and authentication within five minutes. It derives the cleanup UID only from the verified token. The confirmation's `expectedUid` must match that token, so switching accounts during confirmation is rejected; it never selects another account to delete. Extra target identifiers are rejected.

Cleanup first creates the administrator-only `accountDeletions/{uid}` fence. Rules block late writes from that account and new coach relationships targeting it. The server removes owned sessions/recording manifests, assignments, submissions, notes, roster links, private/public profile records, billing mappings, and recording objects. Deleted coaches are removed from surviving players' share lists. Other users' profiles and recordings remain intact. Authentication is deleted last. Repeated calls safely resume a partial cleanup. A trusted `resumeAccountDeletion` creation trigger retries interrupted cleanup independently of the phone. The retained fence holds status and a server timestamp, without profile/address fields.

The client clears only that UID's local preferences, private videos and feedback, photo, and downloaded playback cache after confirmed server deletion. A server-confirmed previous deletion can finish local cleanup after an interrupted response. Configuration changes do not cancel the confirmed server cleanup.

## Activation status

As checked October 5, 2026, `volleyball-lab` has billing disabled. Profile editing and Firestore sync use the existing database. Live recording transfers and the account-deletion callable require activating Storage and Functions on Blaze. No billing upgrade or real account deletion was performed during development. Firebase documents the Storage requirement at https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024.

After project-owner billing approval: provision the default Storage bucket; deploy `storage.rules`, `functions:deleteAccount`, and `functions:resumeAccountDeletion`; verify cross-service Storage rules permissions and the existing Android App Check registration; test on separate player/coach devices. Keep billing/payment endpoints out of that targeted deployment unless independently authorized.

## Verification

Android unit tests exercise restored edit selections, preservation of unrelated preferences, account-switch protection, and sync status/retry scoping. Firestore emulator tests cover private profiles, recording manifests, deletion fences and existing coach workflows. Administrator cleanup tests check ownership, retry behavior, and deletion order. Storage emulator tests cover private player/coach playback, unrelated/anonymous rejection, invalid types/size, missing or spoofed sessions, and revoked access. These tests use demo projects and synthetic data, never production users.
