# Live coaching

Profile → Live coaching has independent Pose Guide and Detailed AI Coaching
switches. Detailed AI Coaching starts off. Pose Guide starts on since
17 November 2026, when it gained the reference figure described below; an
account that had already switched it off stays off. Voice and haptics retain
their existing defaults.
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
AI advice updates only the card and never reopens the speech gate. Guided capture
is Front then Side, both required; the Rear view was removed on 19 Nov 2026
(saved sessions with a rear stage still display it). Finish from Front requests
Side, and Finish before the Side target explains the remaining reps. An explicit
confirmed early exit and the recording limit retain incomplete sessions. Both
the five- and ten-rep options have a five-minute limit. Review metadata records stage targets and
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

## Movement check and facing (17 November 2026)

Reported from a live session: reps and good reps were awarded while standing
still and for plain knee bends.

Cause. Both counters looked only at the shape of one curve, rescaled to whatever
range it had covered, with a floor of 0.001. A still athlete's range decayed to
the tracker's jitter and the jitter then crossed the thresholds. A knee bend was
the whole definition of a pass, and was also what moved the hip-height signal the
spike was segmented on. The cue scores agreed with the count: arms hanging at the
sides are straight, so they earned full marks for a straight platform or arm.

A finished movement is now a rep only if it passes the movement check, applied
identically by `LiveRepCounter` and `RepSegmenter` through
`SkillAnalyzer.verdict`:

| Skill | Signal | Must also be true at contact |
| --- | --- | --- |
| Serving, Spiking | higher wrist above the shoulder line | hitting hand itself rose at least 0.35 torso lengths and finished 0.55 or more above the shoulders, with that arm in view |
| Setting | mean wrist height | both hands at least 0.30 above the shoulders and within 0.35 of each other; the elbows closed past 150 degrees on the way up |
| Blocking | mean wrist height | both hands at least 0.60 above the shoulders; elbows at least 120 degrees |
| Receiving | knee flexion | knees at least 15 degrees deeper than the start; wrists no further apart than 0.60 of shoulder width, below the shoulders, and ahead of the hips in a side view |

Heights are in torso lengths (shoulder line to hip line in the image), so they do
not change with distance from the camera. Neither counter scales its thresholds
below the skill's minimum travel. A movement smaller than that is dropped
silently, as is a hand that never rose above the shoulder line in a one-armed
skill. The one-armed travel was 0.60 at first and was lowered the same day: a
server who starts as taught, hitting hand already beside the head with the
guiding arm held up, has about half a torso length left to travel and was being
refused without a word. A larger one that fails is Not counted: it adds nothing to reps, good
reps, streak or score, the card shows "Not counted" with the one change needed,
and the coach says it (the same reason at most once in eight seconds). Only
observed joints are judged: a hidden far arm in a side view is skipped, a pass
seen from behind accepts a platform hidden by the body, and a hitting arm that
leaves the picture is reported as not visible rather than as a fault.

Spiking is now segmented on the hitting hand, like the serve, so the standing
arm-swing drill counts and a knee bend does not. The Loading phase for Setting,
Spiking and Blocking is the deepest observed knee bend before contact, not the
lowest point of the hand signal. Every saved-session window now opens up to
half a second before the first rise, as the live counter's always did, so both
measure a rep's travel from the same stance; the Preparation frame of a saved
rep is that much earlier than before. Cue targets, weights and tolerances are
unchanged.

Facing. `FacingGuide` gives each skill and view a stance in the athlete's own
left and right. Front is "Face forward". Side is "Turn to your left, so your
right shoulder points at the camera" or its mirror: for Serving and Spiking the
hitting arm observed in the front view must be nearest the camera and the other
profile is refused (before an arm has been observed the turn is only suggested;
the profile no longer stores a dominant hand); Receiving, Setting and Blocking are told a direction but accept either
profile. The opening brief, the turn instruction, the view chip and the
wrong-way prompt all use it. Facing the wrong way at rest suspends counting with
the direction to turn. Free practice accepts any readable view and suspends on a
body turned part-way between two.

After twelve idle seconds the coach says what a rep of the selected skill is.

## Reference figure (17 November 2026)

Pose Guide now also draws a whole-body reference figure: the athlete's own body
in the skill's start or finish position, in dashed translucent gold under the
tracked skeleton, with a label that reads "Reference: start position" or
"Reference: finish position".

When it is drawn:

- Opening brief and countdown: start and finish alternate every 1.4 seconds, so
  the athlete sees what one rep is before recording begins.
- A turn to a new view: the start position in the facing being asked for, then
  start and finish alternating during the turn's countdown.
- Facing the wrong way during a view: the start position, facing the right way.
- A movement that was Not counted: the finish position, for as long as the
  reason is on the card. Not for a fault of visibility, where the body's
  position was not the problem.
- The twelve-second idle prompt: start and finish alternating.

It is never drawn while a movement is under way, during setup framing, or once
the closing begins, and it replaces the single correction limb rather than
sharing the picture with it.

Where the positions come from. `ReferencePoses` reads each scored joint angle out
of that cue's target in `FormCueCatalog`: the finish position is the pose the
Contact-phase cues give full marks, and the start position satisfies the
Preparation and Loading cues. Retuning a target moves the figure with it. Angles
no cue scores (the idle arm, the knees at the finish) are plain choices and are
marked as such in the code. For Setting this means the finish position has the
hands above the forehead with bent elbows, because that is what scoring reads at
the highest point of the hands today; see "Known and left as found" below.

How it is placed. `ReferenceFigure` builds the pose from the athlete's own
segment lengths, stands it on their feet, and sizes it by their whole standing
chain (shin, thigh and trunk) so an error in any one tracked length cancels. A
limb the camera did not see takes a typical length for that trunk. Size and
position are averaged over recent frames so the figure does not shiver with the
tracker. It faces the way the stance asks; where either side profile is accepted
it follows the one the athlete took, and in free practice it follows their view.

What it is not. It is a flat drawing of a three-dimensional pose: facing the
camera, a knee bend shows only as lower hips and a forward lean only as a shorter
trunk, so it reads properly only side-on. It draws the current first-pass
targets exactly as they stand and makes no claim about ideal technique.

## Progressive instruction (17 November 2026)

Live lines are taught in full once, then spoken as a short cue: "Turn to your
left, so your right shoulder points at the camera" becomes "Turn left", and
"Not counted. Join your forearms out in front of you as you bend your knees"
becomes "Not counted. Forearms together." This applies to Not counted reasons,
wrong-facing prompts and the idle prompt. A new athlete (experience "New") hears
the full version twice; anyone who has not heard a line for two minutes hears it
in full again. Only speech is shortened: the card always shows the full
sentence and "Say again" repeats it. A line that was dropped before it was
heard, or any line in a muted session, does not count as taught.
`ProgressiveInstructions` keeps this per session.

### Verification (17 November 2026)

- `.\gradlew.bat :app:testDebugUnitTest`: 559 tests, 0 failures, 0 skipped.
  New: `MovementVerdictTest` (still athlete with tracker noise, knee bends, wrong
  and correct movements for all five skills, a cocked serve start, side and rear
  views, live and saved counts agreeing), `FacingGuideTest`, `ReferencePoseTest`
  (every start and finish position scored by the real analyzers and scorer and
  given full marks on the cues read there; start-to-finish counted as a rep by
  both counters from the front and the side; placement, facing and steadiness),
  and eight capture integration cases.
- `.\gradlew.bat :app:compileDebugAndroidTestKotlin`: compiled; not run.
- The ten positions were rendered from the app's geometry and looked at by eye
  as stick figures, front and side. That checks the shapes, not the app screen.

Not verified, and needing the owner's phone: every threshold in the table. They
are first-pass values from body proportions, tested on a posed figure with
synthetic noise, not on recorded athletes. Expect to retune them. Watch in
particular for correct reps reported as Not counted, the Receiving knee-dip
minimum for athletes who stay low, and free practice suspending on a view the
orientation detector cannot name. For the reference figure: how it looks and
reads on a real screen at three metres, whether its size and position sit on a
real athlete, how steady it is, whether the dashed gold is visible against a
bright court, and whether the label overlaps other controls on a small phone.
Lint was not run.

Known and left as found: for Setting, Contact is still the highest point of the
hands, where a full set has straight arms, yet `SetElbowFlexion` expects bent
elbows there and `SetExtensionFollowThrough` is read after the hands come down.
The debug-only demo detector's figure does not form a platform, so it no longer
counts in Receiving.

## Side-view detection (19 Nov 2026)

`CameraOrientationDetector` also accepts Side when shoulders and hips overlap on
screen (width at most 0.25 / 0.20 of torso height, measured in pixels) and the
depth estimate does not flatly say square-on. Side confidence is graded by how
far inside the side rules a frame sits. `OrientationStabilityGate` accepts a
view after a second of continuous tracking in which at least 90% of frames were
accepted, instead of requiring every frame. Replayed on 213 labelled side-view
clips this raised side frames passing the live check from 27% to 40%, and clips
where the gate opens from 21% to 39%, with 2 of 84 front-view clips wrongly
opened. The opt-in replays are `OrientationReplay` and `RepCountReplay`
(`VL_ORIENTATION_DIR=<dir with landmarks/>`); see
`outputs/orientation-eval-20261119/README.md` for the data.

## One decision per movement, and stricter reps (23 November 2026)

Reported from live sessions: extra movements added reps, a pass with one arm
counted, the voice called a movement wrong while the count went up, the basic
spike asked for a jump, and the setup card still mentioned a rear view.

**One decision.** `RepJudge` is the only answer to "was that a rep". It applies
the skill's movement check and then refuses a rep whose posture cue is clearly
out: beyond the cue's whole tolerance (score zero). Platform height loses the rep
earlier, at 0.35 (arms about 15 degrees below horizontal). `SpikeElbowHeight` is
coached only, because it is not validated on correct spikes. A cue the view cannot
read, or that the drill does not train, is never a reason. `LiveRepCounter`,
`RepSegmenter` through `SessionScorer`, and the spoken reaction all use it, so a
refused movement adds nothing and a counted one is never called wrong: the
reaction wording for a counted rep never says "Not quite" or "needs work". A refusal and a count share one speech lane: the
newest replaces the older, a refusal expires after 1.5 seconds, and one still
being spoken stops when a rep is counted.

**Movement checks added** (they come on top of the 17 November table):

| Skill | Must also be true |
| --- | --- |
| Serving, Spiking | not both hands level and peaking together; the hitting elbow drawn back (160 degrees or less) on the way up; the guiding hand up to about the chest when that arm is in view |
| Setting | both hands in view when not side-on, no more than 0.90 torso lengths apart, level at the top (within 0.35), and the elbows opening at least 8 degrees in a push |
| Blocking | both hands in view when not side-on; the knees bent at least 8 degrees or the hips rose 0.10 torso lengths |
| Receiving | facing the camera, both arms seen, wrists within 0.35 torso lengths on screen and level within 0.25; elbows at least 125 degrees; knees at least 20 degrees deeper; side-on, the near hand at least 0.40 torso lengths ahead of the hips and the far arm nowhere else if it was seen; two of the three frames around the deepest bend must show it |

Contacts closer than 0.9 seconds are one repetition, in both counters. A pass seen
from behind no longer counts.

**Spiking drill progression.** `DrillFocus.excluded` leaves the knee load and jump height out of
`spike-standing-swing` (now "Standing Spike"): not measured, scored, coached or a
reason to refuse, and the gold figure starts standing with the elbow drawn back.
Spike Load and Swing (`spike-three-step`) teaches a knee bend and overhead swing
with both feet planted. Spiking Body Alignment (`spike-four-step-lines`) uses the
same grounded load while focusing on trunk control and elbow position. A clearly
missing knee bend can prevent the rep from counting. Jump height is excluded
from all three starter spiking drills.

**Rear view.** No live text, stance or prompt mentions it, and free practice asks an
athlete seen from behind to turn around. Saved sessions that have a rear stage still
display it.

### Verification (23 November 2026)

Replayed over 336 labelled public clips, the 31 coaching clips and six groupmate
sessions, front and side only, before and after:

| | Before | After |
| --- | --- | --- |
| Movements counted as a skill that was not the one performed | 929 | 512 |
| Reps marked correct that count | 105 | 92 |
| Groupmate serves / spikes counted live (14 / 15 performed) | 14 / 15 | 14 / 15 |
| Groupmate sets counted live (8 performed) | 9 | 8 |
| Groupmate blocks counted live (about 14 performed) | 18 | 15 |
| Groupmate passes filmed as faults, platform at the shoulders (15) | 15 | 1 |

`UnrelatedMovementTest` runs eleven non-skill movements through all five skills from
the front and both sides and expects no reps; removing the swing, both-arm or posture
rules makes it fail.

Limits. Most of the remaining 512 are sets and blocks standing in for each other and
overhead skills filmed with the far arm hidden. Side-on the far arm is behind the body
(visibility 0.04 to 0.18 in the groupmate's passes), so a one-armed pass from the side
is refused only when that arm was seen elsewhere; the front view is what checks both.
About one in eight reps marked correct in public clips is now refused. No correct
receiving recordings with labels exist yet, and nothing here has been tried on the
phone.

## Dominant arm, side-view passes and quieter counts (23 November 2026, evening)

**Dominant arm.** The setup screen asks Left or Right (default Right, remembered on the
device per account) and the side view always turns that arm toward the camera. Serving and
spiking require it; the two-handed skills suggest it and accept either profile. Before, the
arm was guessed from whichever wrist was higher on the last rep, so the direction changed
between sessions. Reading which side is nearest was not the problem: on recorded side
views it agreed with joint visibility, the nose and the feet on 99 to 100% of frames.

**Side-view passes.** A straight platform read about 150 degrees side-on (149 to 152 on 15
recorded passes, 177 to 180 on screen), next to the 140-degree refusal line and well under
the 165-degree target. Side-on the straighter of the screen and depth angles is used. In a
guided side view, frames the view detector is unsure of no longer demand both arms. Rep
timing leans on the better-seen leg, the hidden far hand needs to be further away before it
counts as a second, separate arm (0.75 torso lengths; 0.70 when fully seen), and bending
deep never loses a rep. On the same recordings, movements of other skills counted as passes:
25 before, 25 after. The refusal for a platform held up at the shoulders is unchanged.

**Voice.** A counted rep is spoken as its number. "Counted" is never said. A cue scoring 0.5
or more is shown on the card as something to polish and praised aloud; a remark asking for a
change is not made on two reps in a row.

Not tried on a phone yet. No labelled recordings of correct side-view passes exist, so how
many more of those count has not been measured.
