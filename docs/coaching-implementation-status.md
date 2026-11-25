# Coaching implementation status

15 November 2026. Work is based on the current working tree, including the user's
pre-existing result-screen and model-audit changes. No scoring thresholds, model
weights, measurement geometry or cloud sharing policy were changed.

## Implemented in source

- Local skill setup instruction before countdown; first two completed reps per
  stage are observation only, as required by the newer improvement plan.
- CoachDirector, local phrasebook, weighted single-cue selection, one quiet retry,
  fifteen-second same-cue cooldown, four corrections per ten reps, sentence ledger
  and measured improvement acknowledgment. Speech is gated away from Preparation
  and Contact. Existing visual geometry and haptic events remain available.
- VoiceEngine adapter, bounded initialization, default voice fallback, one-error
  recovery, generation/attempt-scoped watchdogs, pending cue preservation on focus
  denial, spoken-word countdown and visual/haptic fallback. Muting drops stale
  cues. Started rotation guidance does not replay on resume.
- Local angle-break speech is independent of the optional AI card request. AI
  arrival cannot replay a turn or hold the countdown.
- Required Front/Side policy, optional Rear, blocked premature Finish, confirmed
  early exit and bounded recording-limit escape. Guided completeness is retained
  in backward-compatible local sidecars, including partial-side progress.
- Rep windows and camera angles retained through recorder-clock calibration;
  sidecar schema two reads schema one. Legacy video analysis also returns windows.
- Coach Reel source-video selection, at most six measured clips with Front/Side
  coverage, shared player and skeleton renderer, brief fault pause, clip replay,
  next clip, timeline markers grouped by rep, and full recording access.
- Grounded what/why/practice notes and measured/target values. The comparison card
  uses local Front/Side stills from separate reps and the existing phase reference.
  Missing views, poses and measurements remain explicit. One decoder is used.
- Simple next-practice progress card comparing measured counts only for the same
  drill and proven view coverage, with recurring cue focus and provisional-range
  wording. No invented mastery or improvement percentage.

## Verification

The cached Kotlin compiler and Compose/serialization compiler plugins compile the
current main/debug Kotlin sources against the installed Android SDK and cached
dependencies. Unit-test sources are compiled separately. This is a source-level
check, not an APK build or substitute for Android lint/device verification.

The final focused and integration direct JUnit run passed all 55 tests, including
voice recovery, director observation/pacing, required-view ViewModel gates,
recording handoff, review timing, legacy data, clip selection and progress counts.
Main/debug Kotlin compilation and all unit-test source compilation both exited
successfully. Worker tests: 37 passed. Logs: outputs/coaching-compile/compile.log,
test-compile.log, integration-tests.log and outputs/live-coaching-worker-tests.txt.
The broader direct run also passed all 394 tests in 58 test classes. Its harness
includes app/src/test/resources, AGP's generated default-returning Android mock
jar and a writable workspace temp directory for DataStore atomic writes. The
first broad attempt omitted resources and used a sandbox temp directory that
rejected atomic moves; those harness errors were corrected before the passing
run. Log: outputs/coaching-compile/all-tests.log. The final reel UI adjustment
also compiled successfully (ui-final-compile.log).
These are direct compiler/JUnit checks; the Gradle tasks remain blocked.

Logs and reproducible local verification scripts are under outputs/coaching-compile
and outputs/compile-coaching*.py. Gradle wrapper first failed because JAVA_HOME was
unset and the default Gradle user directory was not writable. Using Android
Studio's JDK and an existing workspace Gradle home reaches Gradle, but its daemon
cannot connect over loopback. ADB similarly cannot connect to its local daemon.
No successful assembleDebug, lintDebug, installation or physical-phone run is
claimed for these changes.

## Pending acceptance and remaining scope

- Full Gradle unit suite, Android lint and APK assembly in a working build host.
- Physical framing/introduction/countdown, no-network sessions, voice-data removal,
  Bluetooth/focus interruption, screen background/resume and repeated sessions.
- Precise video seeks, paused skeleton alignment, portrait/landscape still rotation,
  360 dp/RTL comparison layout and cache/decode latency on the target phone.
- MP4 export is intentionally pending the plan's in-app playback acceptance gate.
  No export renderer, gallery publication or export/share action has shipped.
- Director milestone, richer break/finish scripts and adaptive drill sequencing
  need additional implementation and acceptance coverage. Current recurring-focus
  tips do not represent a complete adaptive curriculum.
- Independent five-skill ML assessment validation and reference-range coach review
  remain outstanding. Instructional coverage is not validated five-skill accuracy.

No phase is declared fully accepted while its required device checks are pending.

## Posture wording follow-up - 15 November 2026

Renamed all fifteen drill labels around body positions. The five starter labels
are Serving Stance, Passing Platform, Setting Stance, Standing Spike, and
Blocking Stance. Drill setup, skill learning, posture captions, spoken cues,
assessment recommendations, and mirrored coach-portal cues now teach body
alignment and controlled rehearsal without equipment tasks.

Live AI instructions and response validation reject ball, wall, toss, feed, and
rebound advice. Android checks returned text before accepting cloud audio, so an
older server cannot reintroduce that wording into a live session. Stable drill
IDs retain saved-session and assignment links. Repetition detection, model
weights, measurement ranges, and entitlement rules are unchanged; this is not a
new static-hold detector or evidence of injury prevention.

Verification for this follow-up: main/debug Kotlin and unit-test source compile
successfully; direct JUnit passes 395 tests across 58 classes; Worker passes all
38 tests; shared session explanation policy passes 16 tests; coach-web production
build passes. Android strings parse successfully and the posture-copy scan finds
no equipment instructions in the updated guides or local coaching cues. APK,
Android lint, and physical playback/voice acceptance remain pending. No server
deployment or phone installation was performed for this follow-up.

## Side Setting capture follow-up — 15 November 2026

A deterministic `GuidedCaptureIntegrationTest` reproduced the reported symptom:
after Front-to-Side rotation, a completed Setting movement with the far elbow,
wrist, knee and ankle occluded produced zero stage reps instead of one. Side
framing accepted the visible torso and leg, but the subsequent average across all
limbs fell below 0.6 and repeatedly reset tracking. High-confidence Side views
now use the existing full-body framing evidence; Front and uncertain views retain
the average visibility requirement. Cue scoring and hidden-joint masks are unchanged.

A second regression reproduced no spoken rep event after three observed Side reps
when bilateral cue measurements were unavailable. The director now emits paced,
non-repeating recording guidance in that case. These messages do not assert good
form. Measured corrections retain the existing pacing and confidence checks.

Removed the Compare Views button and automatic posture-comparison card from the
Coach Reel UI. Source recording, correction notes, replay and next-clip controls
remain available. Historical comparison data and helpers remain readable.

The focused reproduction command is:
`gradlew.bat :app:testDebugUnitTest --tests '*GuidedCaptureIntegrationTest.side*'`.
The counting test failed with expected 1 / actual 0 before the fix; the speech test
then failed independently before recording guidance was added. Both now pass.
Regression coverage also rejects missing feet, resumes after framing recovery,
and retains Front visibility requirements. Physical-phone counting and audible
TTS remain acceptance checks; JVM speech-event assertions do not verify a speaker.

Build verification for this follow-up: Gradle full debug suite passed 399 tests;
`lintDebug` completed with zero errors; `assemblePreview` completed successfully.
The signed 0.1.30 preview APK is copied to
`outputs/volleyball-lab-0.1.30-side-setting-fix.apk`; `apksigner verify` passes.
No phone was connected through ADB, so microphone/speaker, physical movement and
end-to-end device acceptance are not claimed.

## Front speech and historical sharing — 15 November 2026

The real CoachingAudio controller failed an on-device regression on the Infinix:
a cue submitted while its correction window was closed never reached the voice
engine when the window reopened. `setCorrectionWindow` now wakes the pending cue
on open, retains its existing expiry, and lets an already-started utterance finish.
Capture permits new speech during the resting Preparation phase; active movement
and Contact defer new cues. Rotation, lost tracking, mute and background still
cancel obsolete speech. No generated correction or pose threshold was changed.

Training Plan renders all saved sessions with their dates and coach-specific
submission status. Each button submits that row's session ID. Assignment details
also expose every saved attempt for review instead of only the latest one.

Verification: 399 JVM tests pass. Three instrumented tests pass on Infinix X6833B
(Android 14): queued cue resumption, actual Google TTS completion callbacks for
Front and Side sentences, and choosing an older session's share button. The
speech regression was observed failing on the phone before the fix. TTS engine
completion is verified; hearing the speaker during real camera movement remains
a user acceptance check. No real session was shared by the UI test.

Device-testing incident: connectedDebugAndroidTest removed the target package
after the test run. The updated debug APK was reinstalled through ADB successfully.
Local-only app data may have been cleared; the user was informed. Future tests
on a personal phone must preserve the target installation or use a separate test
application ID/device. Do not run the default cleanup on the personal package.

## Persistent live coaching focus — 16 November 2026

CoachDirector now retains observation and quiet-retry measurements. A correction
requires two same-direction misses within three consecutive measurable reps of
the same cue, phase, side and view, with the latest rep still needing correction.
It keeps that focus through competing errors and progresses from direct advice
to slower rehearsal and a reset sequence, while preserving the existing pacing.

Praise needs two consecutive comparable retries. Partial progress uses measured
distance toward the reference, is acknowledged once, and keeps the focus. A
reference-range acknowledgment requires both actual measurements inside the
target plus the existing confirmation score. Missing evidence cannot earn
praise; guidance names the unavailable cue and confirms recording. Three missing
focus measurements release it. Stage/view changes, tracking gaps and backgrounding
clear comparison evidence. Wrong-view speech now asks for the required angle.

No model weights, score ranges, measurement geometry, saved-session fields or
cloud endpoints changed in this follow-up. The policy and independent-coach
review procedure are documented in `live-coaching.md`. This is implemented
feedback behavior, not validated coaching accuracy.

Verification: all 421 Gradle debug unit tests pass across 58 test classes,
including 23 director tests and 18 guided-capture integration tests. Coverage
includes outliers, opposite-direction errors, competing cues, sustained/partial
progress, invalid and missing measurements, view/side/phase changes, repeat
budgets, phrase exhaustion, background recovery and wrong-view instructions.
Incomplete body visibility takes priority over a wrong-view instruction in both
speech and the suspension hint.

Final `:app:testDebugUnitTest :app:lintDebug :app:assemblePreview` completed
successfully. Android lint reports zero errors (219 warnings and eight hints).
The signed preview is `outputs/volleyball-lab-0.1.30-coaching-focus.apk`;
`apksigner verify --verbose` passes. The build log is
`outputs/live-coaching-focus-final-verification.log`. This follow-up did not
install on a phone or run physical camera/speaker acceptance or independent
coach validation.


## Combined survey and coaching installation — 16 November 2026

The main workspace now combines the persistent coaching changes with the
personal training setup work from commit 7a98b52 and the latest AssessmentScreen
and AccountMenu edits from C:/vl-personal-setup. The five-card survey, refresh-plan
entry point, private setup sync, personalized Progress view, Coach Reel,
earlier-session sharing and Front/Side speech fixes are included together.
Version 0.1.31-debug (code 32) was built and its signature verified, then installed
successfully on Infinix X6833B using adb install -r. The installed package version
was checked. All 447 Android unit tests and 52 AI Worker tests passed. The deployed
personalizeTrainingSetup endpoint returns the expected unauthenticated rejection.
The APK is outputs/volleyball-lab-0.1.31-combined-latest.apk. Integration backups
and a source/hash record are in outputs/combined-survey-integration.

## Live coach realism — 16 November 2026

Implemented from `docs/live-coach-realism-plan.md`, Phases 1 to 6. Wording,
timing and delivery only; measurement and the saved session contract are
unchanged.

- Words: short spoken counts, rotating reactions, a five-rung correction ladder
  that says what was seen, side-aware wording, cooldown-based sentence reuse.
- Session arc: watch window, earned streak, regroup, budget spent, carried focus,
  stage notes, and dropped-speech recovery in `CoachDirector`.
- Sound: speaking styles, multi-sentence turns with pauses, a `Coaching` speech
  priority, softer stops, a short audio-focus linger, stable voice selection.
- Turns: opening brief with local history, start/resume/idle/recovery lines,
  spoken setup guidance, break debrief, closing on every ordinary finish, and a
  touch-only "Say again".
- Cloud: direction-aware tips, closed `context`, praise turns, late-join speech,
  revised spacing. Dead `ReactiveCoachingEngine` use was removed from the capture
  view model; the class and its tests remain.

Tests that pinned strings changed on purpose were updated: the spoken
`Rep N of T` and "Form target met" assertions, the opening assertions, the
three-sentence ladder length, direction-first correction prefixes, and the fixed
ten-second final-speech fallback.

Verification: Android unit tests 513 tests, 0 failures, 0 skipped; androidTest sources compile;
Worker tests 65 tests, 65 passed. Nothing was installed on or driven on a device.

Still to check on a real phone: rate and pitch by ear, speech timing at real rep
tempo, audibility on court, audio focus with music playing, and the "Say again"
and closing card layouts. The Worker changes are not deployed.
