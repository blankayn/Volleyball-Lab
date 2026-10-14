# Drill module

Added 29 September 2026 from the approved Drill Module with Limited Free Access plan.

## Access

Free accounts can repeat these starters without a weekly workout limit:

| Skill | Drill ID |
|---|---|
| Serving | `serve-standing-float` |
| Receiving | `receive-wall-platform` |
| Setting | `set-wall-reps` |
| Spiking | `spike-standing-swing` |
| Blocking | `block-ready-jumps` |

Pro unlocks all 15 existing drills. `DrillAccessPolicy` is shared by the library,
existing skill lists, manual starts/resumes, and camera entry. Starts use
`DrillAccessController.requireAccess`, which reads the existing trusted plan.
Owner Pro remains tied to the confirmed owner UID; DataStore cannot grant Pro.
Unknown drill IDs are rejected. Starters remain usable while entitlement loads;
premium starts require resolved access. GCash uses the existing readiness checks.

A workout started with valid access may finish after expiry. Reopening a paused
premium draft needs current access. Saved history stays readable after downgrade.
Camera grants survive configuration changes within the same navigation entry;
they are not persisted across process restart or reused for new camera entries.

## Practice and storage

Train → Drill library offers search, skill/difficulty filters, guides, and manual
History. Guides include equipment, setup, numbered steps, common mistakes, and
the existing skill illustrations. Defaults: 3 sets, the drill's target reps,
30 seconds rest. Tap a number to enter any permitted value: 1–5 sets, 1–100
target reps, 0–120 rest seconds.

Only confirmed sets create reps. Actual reps may be 0–100 and can differ from
the target. Rest ends at Ready; the next set starts explicitly. Pause, background,
Leave paused, and process recovery preserve confirmed sets and do not invent
unconfirmed reps. Finish early saves a partial workout. Discard asks for
confirmation. A workout with no confirmed sets produces no history record.

`DrillPracticeRepository` stores account-scoped JSON in the singleton
`drill_practice` Preferences DataStore. Checkpoints are saved on set confirmation,
pause/background, and leaving. IDs are stable. Final history insertion and
matching-draft removal are atomic and idempotent. A failed save retains the
workout and exposes Retry save. A final checkpoint recovered after an interrupted
save is finalized once.

Manual records contain target configuration, confirmed sets/reps, timestamps,
practice/rest duration, and completed/partial status. They are not consumed by
dashboard totals, Progress scores/statistics, or coach submissions. Camera checks
use ordinary camera sessions and return to the originating drill detail on Done;
they neither complete manual sets nor verify ball-target outcomes.

## Verification

- 27 focused policy, transition, repository, and ViewModel tests passed.
- Coverage: exactly five Free/all 15 Pro, owner access, unknown IDs, unresolved
  plans, downgrade/new starts/draft resumes, readable history, account changes,
  all guides/defaults, actual reps, rest/skip, pause/background/recovery, partial
  and empty workouts, failure/retry, atomic/idempotent saves, final recovery,
  and navigation-entry camera grant lifetime.
- Real file-backed DataStore reopening is tested using its cross-platform Okio
  storage and Preferences serializer on the Windows host. Android's default file
  rename implementation cannot replace an existing file in this host test.
- `assembleDebug` and `lintDebug` passed: zero lint errors, 99 warnings and
  three hints. APK SHA-256 and check scope are in `outputs/build-verification.json`.
- The final APK was installed without clearing data on an Infinix X6833B,
  Android 14. Observed on-device: Pro library/guide layouts, default 3 sets and
  30-second rest, direct target-rep editing, actual-rep confirmation and a running
  rest countdown. A partial manual workout persisted with 2/3 confirmed sets
  and 3 actual reps; its draft was removed and the existing four camera sessions
  remained separate. Evidence: `outputs/drill-train-device.png`,
  `drill-rest-device.png`, and `drill-device-storage-verification.json`.
- The phone changed screens/apps between some actions, limiting repeatable UI
  verification. Physical checks still needed: Free account locks/downgrade,
  camera-result return, rotation, background/interrupted recovery, and save-error
  UI. These cases have software coverage where listed above; they are not marked
  as physically passed. No camera session or form score was fabricated.
