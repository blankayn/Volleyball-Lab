# Live coaching

Profile → Live coaching has independent Pose Guide and Detailed AI Coaching
switches, both initially off. Voice and haptics retain their existing defaults.
Preferences belong to the signed-in account on that phone.

Pose Guide draws a translucent gold reference limb with the athlete's observed
segment lengths. Measured bones are 2 dp with smaller joints; guide bones are
1.25 dp. The reference uses the current skill's catalog angle at the detected
movement phase. It supports elbow-angle cues across the five skills and knee
angle cues in a side view. Other cues continue to use their text guidance.
Uncertain tracking, hidden joints, unknown views, phase mismatches, ambiguous
bend direction, and depth-dominated projections suppress the guide. It does not
create a full-body target pose, alter measurements, or imply validated ideal form.
The existing reference ranges still require coach validation.

Detailed AI Coaching summarizes completed reps at a guided angle transition.
At least three measured repetitions and two misses on one cue are required;
only one cue is selected. Up to ten reps with four finite cue measurements each
are sent to `/coachLiveStage`. No video, images, raw landmarks, names, coach
notes, or account identifiers are sent to Groq. The Worker verifies the Firebase
token with an owner-only Firestore read, bounds and validates the local summary,
recomputes its focus using catalog ranges, and sends only the selected cue,
position bucket, and approved tip to the existing Groq text model.

These are client-computed live measurements, not independently verified saved
session evidence. The endpoint cannot save scores, change assignments, or share
recordings. AI prose is limited to 200 characters, checked against the selected
cue, and never used for scoring or reference geometry. Provider failure, invalid
prose, quota limits, or an eight-second client deadline preserve a local tip.
No repeated finding means ordinary measured guidance remains available.

At an angle break, pending short cues are cleared. The app waits for the complete
coaching turn and next-view instruction, confirms a stable view, then runs a
spoken and visual three-second countdown. Mute, unavailable speech, playback
errors, audio-focus loss, or a bounded playback watchdog release the speech gate.
Backgrounding suspends countdowns; resume and Retry assign a new guidance ID.
Old network results and speech callbacks cannot advance a different angle.
Skip and Finish cancel the old coaching work. The camera remains mounted.

Test coach voice is available in camera setup. Short reactive cues and countdowns
use installed English phone TTS. Optional Groq speech is fully downloaded before
playback; phrases are never played as separate streaming fragments. Both sources
share one audio lane and audio-focus request, respect mute/system volume, stop
when backgrounded, and delete temporary WAVs after playback. A real-device check
is still needed for voice quality, distance, speaker loudness, and court noise.

## Backend activation

Apply `ai-worker/migrations/0002_live_coaching.sql` to the existing D1 database
before deploying the Worker. Both Android routes are derived from the configured
`SESSION_AI_URL`; no new credential is placed in the APK.

Online speech is disabled unless `GROQ_VOICE_FREE_TIER_CONFIRMED=true` is explicitly
set on the Worker after confirming that the connected Groq organization is on
the Free plan. The owner confirmed Groq Free on 2026-11-08, so this deployment
sets the flag. A Groq API key alone never enables speech. No billing upgrade or
paid fallback is performed. Without confirmation, the feature uses phone TTS.
If the organization is later upgraded, remove the confirmation flag.

The Groq organization must also accept the Orpheus English model terms. A
`provider_400_model_terms_required` voice status means the provider is still
denying that key; phone TTS remains available. Accepting the terms must apply to
the organization that owns the stored key. Once Groq authorizes the model,
online speech can work without another APK or credential embedded in the app.

Speech uses `canopylabs/orpheus-v1-english`, voice `troy`, WAV, at most 200
characters. The Worker caps voice at ten attempts per user per UTC day and
ninety attempts for the app organization, with cooldowns. Groq's additional token
limits still apply and may be hit earlier. Text is capped at twenty attempts per
user and five hundred per app per day. All counters are atomic D1 upserts;
failed provider attempts count. No live advice or audio is retained in D1.
The scheduled purge removes counters older than forty-eight hours.

Provider references checked 2026-11-08:
[Orpheus speech API](https://console.groq.com/docs/text-to-speech/orpheus),
[organization rate limits](https://console.groq.com/docs/rate-limits), and
[structured output](https://console.groq.com/docs/structured-outputs).

## Verification record

On 2026-11-08, all 374 Android unit tests, 37 Worker tests, and five emulator UI
checks passed. The UI checks cover long coaching text, compact screens, action
visibility, the voice test, and keeping the camera mounted through state changes.
The live Worker returned authenticated Groq advice in roughly one to two seconds
with synthetic measurements, and rejected an anonymous request with HTTP 401.
The voice adapter and its fallback passed tests with a buffered WAV fixture;
the connected Groq account initially returned `model_terms_required` for actual
Orpheus speech. Voice quality and court-distance audibility require a real phone.
The final preview lint check passed with zero errors (214 warnings and eight
hints across the current workspace).


## Coaching flow update (15 November 2026)

Capture speaks a local setup instruction before the countdown. Every completed
rep receives a count and a measured verdict, including the first two reps used
to establish repeated correction evidence. CoachDirector owns spoken
corrections at completed-rep boundaries; frame-level geometry remains available
for the overlay. It prioritizes the athlete's confirmed focus among eligible
measured faults, then weighted deficit, leaves a retry without another instruction,
limits corrections to four in ten reps, waits fifteen seconds before
revisiting the same cue, and uses a sentence ledger. Improvement acknowledgments
name the previously corrected cue. The evidence and acknowledgment rules below
extend this flow. Provisional scoring ranges are unchanged.

Angle breaks speak the local outcome and turn instruction immediately. Optional
AI advice updates only the card and never reopens the speech gate. Front and Side
are required for guided capture; Rear can be skipped. Finish from Front requests
Side, and Finish before the Side target explains the remaining reps. An explicit
confirmed early exit and the recording limit retain incomplete sessions. The
ten-rep option has a six-minute limit. Review metadata records stage targets and
completeness without changing saved scoring fields.

The live view label and rep counters refer to the same angle. During a turn and
its countdown, the upcoming view shows zero completed and good reps; the previous
view's reps remain in the session totals and saved stage summary. After the
countdown, the new view's counters advance independently. Free practice continues
to show the session totals.

Phone speech uses a platform adapter and a testable recovery controller. Language
selection precedes optional offline voice selection; an empty voice list does not
reject a working default. Two initialization attempts are bounded to six seconds.
Utterance errors retry once, then expose degraded voice while visual feedback
continues. Callbacks and watchdogs are scoped to generations and utterance attempts.
Audio focus is requested before dequeueing a cue. Countdown uses spoken words,
visual numbers and haptics. Muting drops stale speech. Started angle guidance is
consumed on resume; Retry explicitly creates a new turn. New rep feedback starts
in the rep counter's rest/recovery window, not while a movement is in progress.
The audio gate reads the counter directly instead of the slower visual posture state.

Validation is recorded in docs/coaching-implementation-status.md. Physical voice
quality, Bluetooth interruption behavior and full-session timing remain device
acceptance checks. The changes do not establish validated five-skill ML accuracy.

## Persistent coaching focus (16 November 2026)

The director retains the latest three completed reps, including observation and
quiet-retry reps. A spoken correction needs three consecutive measurable samples
of the same cue, phase, observed side and capture view. At least two must miss in
the same direction, and the latest must still need that correction. Missing,
non-finite or invalid scores and unknown views never become form evidence.

The selected cue stays the focus even when another cue becomes worse. Follow-ups
progress through a direct action, a slower rehearsal, and a reset sequence for
that same action. A repeated miss on the opposite side of the range starts the
appropriate direction's instructions. The existing quiet retry, speech timing,
fifteen-second cooldown, four-corrections-per-ten-reps budget and sentence ledger
remain in force. Exhausted instructions do not block a different eligible focus
when a new stage starts.

Two consecutive measurable retries after the correction are required for praise.
Both must actually be inside the catalog target and pass the existing 0.92
confirmation score before the director says they matched the reference and
releases the focus. Partial progress is acknowledged once per focus: both retries
must reduce distance from the target by at least the larger of 20% of the cue's
warning tolerance or 25% of its initial median distance. This says "closer to the
reference" and retains the focus; it does not label the rep correct or change
scores. These are provisional feedback policies requiring coach review, not
calibrated accuracy claims.

A missing focus measurement cannot earn praise. Paced guidance names the cue
that is not clear enough to assess and confirms that repetitions still record.
It does not invent which joint is hidden. Three consecutive unavailable focus
measurements release that focus. Tracking interruption, backgrounding and camera
view/stage changes clear comparison evidence and focus; sentence history and
session-wide pacing remain. A wrong-view prompt asks for the required view rather
than giving a generic step-back instruction.

## Personalized rep coaching (16 November 2026)

The live session reads the athlete's first name, confirmed training-focus choice,
and experience from their existing profile. The opening and coaching card use
that context locally. Closed focus choices map only to observable cues for the
current skill; timing, ball accuracy, unknown choices and other-skill preferences
never become invented measurements. The plan is frozen at the countdown, and an
empty profile gets a complete generic session. Names, free-text goals and coach
notes are never added to the cloud evidence request. Since the 16 November
realism update, two closed profile-derived values are: the `experience` enum and
an `isAthleteFocus` flag (see "Coach realism" below).

Each completed movement publishes one combined count and feedback event. Guided
speech uses the current view's count and target; free practice uses the session
total. The card retains the latest completed-rep verdict: Form target met, Needs
work, Partly assessed, or Not assessed. Missing joints, unknown views and invalid
measurements cannot earn a positive judgment. A correction or measured improvement
replaces the short verdict in that turn, so the voice gives only one instruction.
The next retry still gets a count without another correction.

Counts bypass the four-second correction cooldown but share the same single voice
lane. New counts replace unspoken older counts at very fast movement rates rather
than building an outdated backlog. Mute and backgrounding clear pending speech.
The target rep is included in the angle-change turn so the last count is not cut
off. At the final view, capture stops accepting reps while the final count plays;
completion has a bounded fallback if audio is unavailable or its callback is lost.
Tracking interruptions clear the last verdict without adding repetitions.

Coverage includes local personalization and verdict policy tests, camera-feed
integration for counting and final-turn completion, speech pacing tests, and phone
layout/audio tests. These verify behavior, not athletic scoring accuracy.

### Coach and phone acceptance

Review recordings from different players separately from any threshold-tuning
clips. Have coaches label the visible cue, phase, side, correction direction and
whether it was assessable before viewing the app's advice. Then match each spoken
event to its completed rep, recording the proposed/actual speech time, coaching
focus, coach agreement, missed recurring issues, incorrect corrections, false
praise and unavailable-view behavior. Report results per skill and view; do not
turn synthetic test pass rates into a coaching accuracy percentage.

On a phone, include isolated poor reps, a recurring fault, a worse competing cue,
partial progress, sustained in-range retries, opposite-direction overshoot,
occlusion, view rotation and background/resume. Listen for intelligible speech
between attempts and check that old cues do not play after interruption. Use a
separate test installation or preserve the personal app's data. Coach-labeled
accuracy and physical speaker/camera acceptance remain external validation work.

## Coach realism (16 November 2026)

This update changes what the coach says, when, and how it sounds. It does not
change scoring, cue geometry, catalog ranges, `LivePostureCoach` thresholds, rep
counting, stage gating or the saved session contract. Plan:
`docs/live-coach-realism-plan.md`.

### Spoken count and reactions

The card keeps `Rep N of T` and the verdict label. Speech uses a short form:

| Rep in a guided view | Spoken |
|---|---|
| first | "One." |
| half of an even target | "Halfway." |
| one before the target | "One to go." |
| any other | the number word |

Free practice speaks the session total as a number word, falling back to digits
above thirty. After the count comes one rotating reaction for the verdict
(`CoachPhrasebook.repAck`), chosen by rep index and never repeated back to back.
Good-rep reactions never say "better" or imply improvement. Partial and unrated
reps keep "Only part" and "couldn't judge". The rep after a director turn is a
quiet retry: the count alone.

### Corrections, praise and missing evidence

The correction ladder for a focus has five rungs: what was seen plus the direct
action, a slow rehearsal, a reset sequence, a struggle line, and a feel cue. The
first rung states the direction only, with no number, and names the left or right
arm for side-resolved cues. `ExperienceLevel.New` starts at the slow rehearsal.
A sentence may return after eight reps and forty-five seconds, so a stubborn
fault no longer silences the coach after three sentences. The evidence rules are
unchanged: three consecutive comparable reps, two misses in one direction and the
latest still missing for a correction; two retries inside `cue.target` with score
at least 0.92 for "in range" praise, otherwise only "closer".

### Turn types

All of these are `RepMilestone` turns, below corrections and improvements, and
share the one-turn-then-quiet-rep rule.

| Turn | Fires when |
|---|---|
| Watch window | first rep of a view with no measured fault; makes no claim |
| Earned streak | assessable good reps at a run of three, five or eight, no open focus; names a cue only if it was inside target with score at least 0.92 on every rep of the run |
| Regroup | the last three assessable scores are at least fifteen points below the view's first three after a good rep, or a cue confirmed in range has missed twice in one direction; once per view |
| Budget spent | the four-in-ten correction budget is exhausted and a fault persists; once per view |
| Carried focus | the open cue from the last view independently meets the repeated-fault rule in the new view; prefixed "Same thing from this side:" |
| Tracking restored | once, within twenty seconds of a spoken tracking prompt |
| Start, resume, idle | after each countdown, and once per idle spell of twelve seconds |

View changes, tracking gaps, backgrounding and `beginStage()` still never bridge
evidence. Only the name of the open cue crosses a view change.

### Opening, debrief and closing

The opening is spoken as separate sentences with pauses: greeting, last
session's weakest cue when one is stored on this device for the same skill and
drill, the linked coach's drill title, today's focus, the setup cue, the guided
plan, and "I'll watch the first couple before I say anything." A coach's
free-text note is never read aloud.

At a view change the break is a debrief: the final rep's turn, one line with the
view's counted reps and one direction-correct local takeaway (shown and spoken
whether or not Detailed AI Coaching is on), then the turn instruction.

Every ordinary finish (guided completion, Finish, Skip Rear, confirmed End
Early) ends on a closing turn built by `SessionWrapUp.closing`: the rep count, the
in-range count and best run, a win only for a cue a view recorded as resolved,
the open cue to carry forward, and a hand-off to the review. An early end states
that the session is saved as incomplete and gives no praise. The recording limit
and forced stops stay immediate. The closing has a bounded fallback so the
session is saved even when speech never completes.

### How it sounds

`SpeechStyle` sets rate and pitch per utterance: Count 1.08/1.00, Praise
1.02/1.06, Correction 0.92/0.97, Guidance 0.96/1.00, Urgent 1.05/1.00. These are
starting values to tune by ear. A rep turn carrying a correction or improvement
uses `SpeechPriority.Coaching`, which a plain count cannot replace and which
expires after six seconds. A line that is never spoken is reported back so the
director can offer its sentence again. Speech watchdogs scale with the text
(`max(6 s, 90 ms per character + 3 s)`; the engine-level retry watchdog keeps an
eight-second floor). Audio focus is held for two seconds after an utterance, and
the phone voice is chosen by US locale, then quality, then name. "Say again"
repeats the last correction or guidance turn by touch. There is no microphone
input.

### Cloud `context` contract

`coachLiveStage` accepts exactly two shapes: the legacy four keys, or five keys
with:

```
context: { v: 1, turn: "break" | "closing", breakIndex: 0..2,
           experience: "New" | "Developing" | "Experienced" | null,
           isAthleteFocus: boolean, corrected: CueId[] }
```

`corrected` holds at most four unique cue ids for the request's skill. Unknown
keys are rejected. The worker derives `missShare`, `trend`, a strength noun and
`lateDrop` from the reps it validates; raw measurements, names and free text never
reach the provider. Tips are direction-aware (`LIVE_DIRECTION`), a clean view can
return `kind: "praise"` to a client that sent context, and "closer" wording is
allowed only when the server-derived trend is closer. AI advice is spoken only
if it arrives while its turn is still speaking, with the turn instruction held for
at most 1.5 seconds; otherwise it stays on the card. A session makes at most four
text calls. Per-user spacing is eight seconds and organisation spacing 250 ms,
within the unchanged 500-per-day organisation cap. Voice quotas are unchanged.

### Verification (16 November 2026)

- `.\gradlew.bat :app:testDebugUnitTest`: 513 tests, 0 failures, 0 skipped.
- `.\gradlew.bat :app:compileDebugAndroidTestKotlin`: compiled. Instrumented
  tests were updated to compile and were not run.
- `cd ai-worker; npm test`: 65 tests, 65 passed.

Not verified, and needing the owner's phone: voice rate and pitch by ear, speech
timing at real rep tempo, audibility on court, audio-focus behaviour with music
playing, and the new on-screen "Say again" and closing card layouts. Coach-labelled
validation above is still outstanding and unaffected.
