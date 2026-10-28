# Coach web account settings

The dashboard Account page includes profile and team details, a profile
picture, certifications, appearance, accessibility, and notification controls.
Appearance settings affect the authenticated dashboard. The system theme
follows OS changes live, and system reduced-motion preferences are always
respected. Settings controls apply immediately and save after a short debounce.
**Save profile** saves the coach profile and directory together, then syncs the
private picture/certifications and account display name independently.

## Firebase persistence

Deploy the updated `firestore.rules` before enabling cloud sync for this
version. The new owner-only path is
`coaches/{uid}/webSettings/preferences`; profile pictures and certificates
remain private. Existing shared profile and directory field contracts are
preserved. Deploy the updated `deleteAccount` and `resumeAccountDeletion`
functions with this feature so account deletion also removes the new private
subcollection and mail receipts.

Until the rules are deployed, appearance changes still apply and cache in the
current browser. Existing profile fields continue to save to Firebase even if
the new private settings path is denied. The photo and certifications remain
cached on this browser, and the save result clearly reports their cloud-sync
limitation. A failure to save the core coach profile still displays an error
and preserves the form.

## Resend email setup

Email alert delivery lives in `functions/coach-email.mjs`. The browser stores
only the opt-in and category preferences. It never receives a Resend API key.

1. Verify a sending domain in Resend and choose its sender address.
2. Store a send-only API key in Firebase Secret Manager:
   `firebase functions:secrets:set RESEND_API_KEY`.
3. Set deployment parameters `COACH_EMAIL_FROM` to the verified sender, for
   example `Volleyball Lab <alerts@yourdomain.com>`, and `COACH_PORTAL_URL` to
   the deployed HTTPS website. Firebase prompts for these parameters during
   deployment; they can also be supplied in the functions deployment env file.
4. Deploy only the relevant functions after the source has been reviewed:
   `emailCoachOnSubmission` and `emailCoachDeadlineReminders` (codebase `billing`).
5. A coach verifies their account email and enables **Email notifications** in
   Account. The verification button appears for unverified accounts.

The submission trigger sends one email for a newly submitted or resubmitted
session. Submission-alert preference takes priority; otherwise an enabled
training-session preference sends a training alert. This avoids sending two
emails for the same shared session. The hourly deadline job covers incomplete
assignments due in the next 24 hours. Delivery checks that both accounts are
active and their roster connection still exists.

Hashed event receipts and Resend idempotency keys prevent repeated sends.
Provider failures remain retryable and are not recorded as successful. An
uncertain delivery older than Resend's idempotency window is expired rather
than sent again. Delivery logs contain identifiers and status only.

## Verification

- `npm run build` in `coach-web` checks TypeScript and builds Vite.
- `npm run test:profile` in `coach-web` reproduces older-rule denials at the real
  Firestore write boundary and verifies that optional settings/account-name
  writes cannot block core profile changes. Core write denials still fail.
- `node --test test/coach-email.test.mjs test/account-policy.test.mjs test/push-delivery.test.mjs`
  in `functions` checks opt-in, category selection, deadline boundaries, safe
  email rendering, provider failures, idempotency keys, and existing push logic.
- `functions/emulator-tests/web-settings.test.mjs` checks owner-only access,
  bounded settings/photo payloads, and the deletion fence. The account cleanup
  emulator test covers settings and receipt removal. These require a running
  local Firestore emulator.
- The isolated visual preview under `.codex-tmp/portal-check` uses fixture
  data and never writes to real Firebase accounts.

No production email is sent by these checks. Email delivery needs the secret,
sender parameters, and deployment described above.
