# Coach Cloud Contract (v1)

The single source of truth shared by the **Android player app** (`app/`) and the
**coach web portal** (`coach-web/`). Both clients read and write these
collections; neither keeps a second copy of the same fact anywhere else.

This settles capstone conflicts **4** (Firestore vs. the relational data
dictionary — Firestore wins for the coach graph) and **6** (coach accounts get
their home: Firebase Authentication plus `users/{uid}` and `coaches/{uid}`).
The on-device DataStore coach store remains in the tree for unit tests and as
documented fallback, but production traffic goes here.

## Conventions

- Document ids are client-generated where the writer owns the row (`assignments`,
  `notes`, `sessions` use UUIDs), and derived where the row is unique by
  relation (`roster/{coachUid}/members/{playerUid}`,
  `submissions/{coachUid}_{sessionId}`).
- Enums are stored by their Kotlin `name`: `Pending`/`Active`/`Ended`,
  `Serving`/`Receiving`/`Setting`/`Spiking`/`Blocking`,
  `Beginner`/`Intermediate`/`Advanced`, skill names as in `Skill.name`.
  Unknown values degrade to the same defaults the app uses (never a throw).
- Account/activity timestamps are epoch millis (`number`). Rep and angle-stage
  `startMs`/`contactMs`/`endMs` instead use monotonic camera-frame timestamps;
  they identify capture intervals and must not be displayed as calendar dates.
  `null` means absent, never `0`.
- Rules live in `firestore.rules` at the repo root and are the enforcement
  point. The prose below explains intent; the rules file decides.

## Collections

### `users/{uid}` — account home (conflict 6)

Written by the **web portal at registration**; read by the **app at login** to
resolve which shell to build.

| Field | Type | Notes |
|---|---|---|
| `role` | `"PLAYER"` / `"COACH"` | The `User_Role` the document always wanted. |
| `displayName` | string | From Auth profile at registration. |
| `email` | string | |
| `createdAt` / `updatedAt` | number | |

Rules: owner read/write. (Self-promotion is possible and accepted for this
build — the app already trusts a Profile switch the same way. A privileged
"make coach" path is future work, not a silent assumption.)

### `coaches/{coachUid}` — full profile (Table 14 + Fig. 24)

| Field | Type | Notes |
|---|---|---|
| `name`, `teamName`, `organisation` | string | |
| `specialisation` | string \| null | `CoachSpecialisation.name` |
| `yearsExperience` | number \| null | |
| `licenceNumber` | string | **Coach-private.** Never copied to the directory. |
| `notifySubmissions`, `notifyWeeklyDigest`, `notifySafetyAlerts` | boolean | Submission/weekly preferences control the inbox; validated safety-risk alerts remain unavailable. |
| `updatedAt` | number | |

### `coachDirectory/{coachUid}` — what players browse (Fig. 20)

A projection of `coaches/{uid}` minus everything private, plus the one computed
figure. Written by the coach client (app `publishListing`, web profile save).

| Field | Type | Notes |
|---|---|---|
| `name`, `teamName`, `organisation` | string | Public display fields. |
| `specialisation` | string \| null | |
| `yearsExperience` | number \| null | |
| `rosterSize` | number | Active members, maintained by the coach client. |
| `updatedAt` | number | |

### `roster/{coachUid}/members/{playerUid}` — membership (Table 15)

| Field | Type | Notes |
|---|---|---|
| `playerUid`, `playerName` | string | Name copied at request time (narrowest readable row). |
| `position` | string \| null | `CourtPosition.name` |
| `status` | `"Pending"` / `"Active"` / `"Ended"` | Only the coach moves it, except leave. |
| `requestedAt` | number | |
| `decidedAt` | number \| null | |

Flow: player creates their own doc with `status: "Pending"` (request); coach
sets `Active`/`Ended` (answer); player sets their own doc to `Ended` (leave) —
the only player-side status write the rules allow.

New requests require owner Pro or an active server entitlement. Students select
the coach from the directory; no invite code is used, per the user's instruction.

### `assignments/{assignmentId}` — set drills (Table 16 + Fig. 28)

| Field | Type |
|---|---|
| `id`, `coachUid`, `playerUid`, `title`, `skill`, `level` | string |
| `summary`, `instructions` | string |
| `targetSets`, `targetReps` | number |
| `dueAt` | number \| null |
| `status` | `"Pending"` / `"InProgress"` / `"Completed"` |
| `createdAt` | number |
| `libraryDrillId` | string \| null |

Coach writes everything; the player may update `status` only (mark done).

### `submissions/{coachUid}_{sessionId}` — review queue

| Field | Type | Notes |
|---|---|---|
| `sessionId`, `coachUid`, `playerUid` | string | |
| `submittedAt` | number | |
| `reviewedAt` | number \| null | Set by the coach when a note is posted. |

Player creates (must hold an `Active` roster row — enforced in rules); the
coach stamps `reviewedAt` together with the note. Re-submitting a reviewed
session re-queues it (`reviewedAt: null`), asking for a second look.

### `notes/{noteId}` — coach feedback (Table 17)

| Field | Type |
|---|---|
| `id`, `coachUid`, `playerUid` | string |
| `sessionId` | string \| null |
| `text` | string (≤ 800 chars) |
| `createdAt` | number |

Coach writes; player reads their own. Posting a session note and stamping the
submission reviewed is one user action on both clients.

### `sessions/{sessionId}` — shared training record

Written by the **player app** on every save (measured summaries). Read by the **coach portal** for
athletes who submitted, and by the app's coach shell via `SessionDirectory`.

| Field | Type | Notes |
|---|---|---|
| `id`, `ownerUid`, `skill`, `drillId` | string | |
| `startedAt`, `durationMs`, `repCount` | number | |
| `formScore` | number \| null | Mean of scored reps, 0–100. Computed by the app. |
| `weakestCue` | string \| null | `CueId.name`, min 3 measured reps. |
| `reps` | array | `{index,startMs,contactMs,endMs,cueScores:[{cueId,measured,score}]}` |
| `angleStages` | optional array | `{stageIndex,angle,startMs,endMs,liveRepCount,validRepCount,bestStreak,estimatedFormScore}`. `angle` is `Front`/`Side`/`Rear`/`Unknown`; estimate is nullable, 0–100. Older sessions omit it. Live estimates are distinct from final `reps` and `formScore`. |
| `sharedWith` | string[] | Coach uids. Appended on submit, never removed by submit. |
| `createdAt` / `updatedAt` | number | |

Rules: owner full access; a coach reads a session only when their uid is in
`sharedWith`. Submission is explicit and this array is the receipt — nothing is
shared automatically, on either client.

Angle stages describe scored capture intervals only. Turning and countdown
frames are excluded from these intervals. Final reps must not cross stage
boundaries. The existing web portal may ignore this optional metadata while
continuing to read the same final scores. Explicitly submitted recordings and pose feedback use separate private Storage objects; see `account-and-recording-services.md`.

## Notifications and paid access

The Android app derives an account-scoped local inbox from sessions, notes,
assignments and submissions. Read status and the delivery preference persist
on the device. Optional Android notifications honor runtime permission and
coach alert preferences. WorkManager refreshes cloud events periodically;
background delivery can be delayed by Android. There is no FCM server push.

`entitlements/{uid}` is readable by that account and writable only by trusted
server administration/payment handling. Fields are `ownerUid`, `status`,
`expiresAtEpochMs` (0 permanent), `source` and `updatedAt`. Owner Pro is bound
to the confirmed Firebase UID for `kirt@gmail.com`; production reads ignore
legacy local demo flags.

`billingConfig/pro` contains public purchase settings and is signed-in read
only. `billingOrders/{id}` is read only to its owner. Internal checkout and
payment mappings deny client access. Merchant secrets belong in Secret Manager.
The prepared GCash flow grants access only after a verified live payment
webhook; browser return is not payment proof. Merchant activation remains
pending; see `docs/gcash-checkout-setup.md`.

Daily plans, photos, report history and the five-per-week Free report allowance
are account-scoped local app data. They are not a server API spending limit.

## Data retained on the phone

- **Pose landmarks / video.** Analysis stays on-device. Explicit coach submission uploads the recorded video and saved pose feedback privately for the player and shared coach. See `account-and-recording-services.md` for activation status and account deletion.

## Bring-up (both clients)

1. Firebase console → use project `volleyball-lab` (the one
   `app/google-services.json` already points at).
2. **Authentication** → Sign-in method → enable **Email/Password**.
3. Use the existing Standard `(default)` Firestore database in
   `asia-southeast1`; deploy `firestore.rules`:
   `firebase deploy --only firestore:rules,firestore:indexes`.
   `firestore.indexes.json` includes the student membership collection-group lookup.
4. Web: copy `coach-web/.env.example` → `coach-web/.env` with the project's web
   config, then `npm install && npm run dev`.
5. App: no extra step — the bundled `google-services.json` already configures
   the same project; `firebase-firestore` ships in the app build.
