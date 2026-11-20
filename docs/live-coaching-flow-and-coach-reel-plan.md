# Live coaching flow, voice reliability, and Coach Reel

**Implementation plan for Astra** · 14 November 2026

## Outcome

A live session should feel like a calm coach watching a drill, not a device
reading alerts:

- It speaks one short **setup cue** before the first rep, then coaches the first
  completed rep. There is no two-rep silent window.
- It gives **one** short, specific cue, then stays quiet for one rep while the
  athlete tries it.
- It never talks over Preparation or Contact; it speaks at the rep boundary.
- It never repeats the same sentence or nags the same cue.
- It never makes the athlete wait for a network request at a camera-angle break.
- If phone speech fails, the visual cue and haptic still work, and the session
  keeps moving.

The saved recording should become a **Coach Reel**: the failed reps are
automatically selected, played back with the skeleton and highlighted joints,
and each one gets one grounded coaching comment — the state sketched in the
reference image (paused frame, skeleton, callout bubble, marked timeline).
The reel plays in the app first; MP4 export follows.

The front and side views are both **required** in a guided session. Finishing
the front stage moves into the side stage; the athlete cannot skip it or end a
guided session as complete without it. Review then shows the athlete's front and
side posture **side by side** at the measured phase (contact for contact cues),
with the reference posture available in the same card. The views are recorded in
separate stages, so they are separate reps; the card compares the shape, not a
single instant, and says so. The side view is not an optional extra: cues such
as knee load and trunk angle are only trustworthy from that angle.

The sketch is already close to the existing review player. `AnnotatedSessionVideo`
draws the skeleton, affected joints, a small label bubble and timeline dots.
The missing pieces are auto-selection of failed reps, a real per-rep comment,
and an edit/export path. Do **not** rebuild the overlay from scratch.

## Handoff prompt

> Read this file end to end. Start with Phase 0 and record the baseline. Do
> Phase 1 before changing the coaching script; voice reliability is the
> foundation. Then implement Phase 2 in one PR, Phase 2.5 in one PR (required
> Front + Side capture stages), Phase 3 in two PRs (data, then reel UI), Phase
> 3.5 in one PR (front/side posture comparison), and Phase 4 only after the
> in-app reel is good. Change the opening: speak one setup cue at the
> countdown, coach the first completed rep, and keep only one quiet rep after a
> cue. Preserve uncommitted user changes, do not change scoring, and run the
> tests named in each phase.

## Fast prompt (copy-paste for Astra)

> Read `docs/live-coaching-flow-and-coach-reel-plan.md` end to end and implement
> it in order: Phase 1 voice reliability → Phase 2 Coach Director → Phase 2.5
> mandatory Front + Side → Phase 3 Coach Reel → Phase 3.5 front/side comparison
> → Phase 4 export → Phase 5 verification.
>
> The session must engage immediately, not stay silent for two reps. At the
> countdown, speak one short setup cue (for example, "Arms ready — platform
> together", max 10 words). Coach the first completed rep at its boundary, then
> keep one quiet rep after each cue. Never speak during Preparation or Contact.
> Keep the anti-repeat rules: no sentence twice, same cue not again for 15 s or
> 2 reps, max four cues per ten reps.
>
> Preserve the uncommitted working tree. Do not change scoring, cue geometry,
> reference ranges, or saved session fields. Build and run the tests named in
> each phase.

---

## Read first — guardrails for Astra

1. Read `docs/live-coaching.md`, `docs/annotated-video-review.md` and
   `app/src/main/java/com/volleyballlab/player/pose/analysis/ReactiveCoachingEngine.kt`
   before editing.
2. The working tree contains uncommitted user changes. Run `git status` first.
   Do not revert, reformat or overwrite unrelated modified files. Build on the
   current working tree, not a clean reset of `HEAD`.
3. Do not change scoring thresholds, cue geometry, reference ranges, or the
   saved session contract. This plan changes **when and how coaching is
   spoken**, and **how review is presented** — not what is measured. The only
   new persisted data is backward-compatible review-sidecar metadata (rep
   windows, view angles, `GuidedReview`) with defaults for old records; no
   existing `SessionResult` field changes meaning.
4. Do not upload video, images, raw landmarks, or account identifiers. The
   existing privacy rules in `docs/live-coaching.md` and
   `docs/annotated-video-review.md` still apply. Reel comments may send only a
   bounded cue summary if the optional AI step is enabled.
5. Keep the camera mounted through every state change. Do not add a new
   navigation destination for coaching; extend the existing capture and review
   screens.
6. Build and test after each phase. A phase is finished only when its
   **Done when** criteria pass; do not move to the next phase on a partial
   implementation.
7. Prefer small, reviewable commits. One phase = one PR or one clearly labelled
   set of commits.

---

## What is causing the awkward feel today

| Symptom | Cause in the current code | Fix direction |
|---|---|---|
| Long silence at every angle break | `CaptureViewModel.requestRotation()` sets `loadingCoaching = true` and blocks `speechPending` / countdown for up to 8 s while Groq answers | Speak the local advice immediately; let the AI card upgrade later; never gate speech or countdown on the network |
| Coach says nothing until two reps are done | The flow starts with `Observe(2 reps)`; the athlete gets no setup cue and no first-rep correction | Open with one setup cue; coach the first completed rep; then one quiet rep |
| The same correction is said two or three times | `lastRepSummary` uses the same `shortCueText()` already spoken live, then the stage summary repeats it, then AI advice repeats the same cue | One cue per window; anti-echo at the break; skip the focus beat if it was just spoken |
| Coaching sounds repetitive | `ReactiveCoachingEngine.shortCueText()` has two variants, selected by `correctionVariant % 2`; the alternate is only "Next rep: …" | A phrasebook with several variants per cue, direction and severity, with a no-repeat ledger |
| Generic filler every five reps | `onRep()` emits `"$totalReps reps completed. Keep your next movement controlled."` and a streak line that can collide with it | Milestones only at halfway and target; specific counts and the current focus |
| Cues can land mid-swing | `SpeechPacer` uses wall-clock gaps only; `submit()` can fire while the athlete is in Preparation or Contact | Gate speech on completed-rep boundaries and phases; speak between reps |
| "Preparing your coaching…" then nothing | `CoachingAudio` drops cues while `Initializing`; one TTS `onError` marks the engine `Unavailable` until the screen resumes | Queue during initialization; recover from one error; expose a degraded state; never dead-end |
| Countdown is silent or late | `announceCountdown()` requires a ready engine and uses `QUEUE_FLUSH` with a numeric string; the flow can wait for guidance speech first | Countdown has its own lane, spoken words and a visual/haptic fallback; it never waits for guidance |
| Guidance replays after resume | `setCaptureForeground(true)` sets `speechPending = true` and `CaptureScreen` replays the whole guidance turn | Track the spoken guidance id; resume from the next unspoken segment, not from the top |
| Side view can be skipped or bypassed | `GuidedCaptureProgress.angles` is Front/Side/Rear, but `onSkipAngle()` and `onStopRequested()` have no required-stage gate | Front and Side are required; Side is non-skippable; Finish is gated until Side reaches its target, with an explicit confirm that saves an incomplete session |
| No front/side posture comparison | Review shows one view at a time; the manuscript's side-by-side camera/reference pane is not implemented | Add a front/side still comparison at the measured phase plus the existing reference posture sheet (Phase 3.5) |
| Review shows every issue, not the failed reps | `AnnotatedSessionVideo` lists all `ReviewIssue`s and draws a dot for each | Group by rep, select failed reps, build a reel; keep the full list as a secondary view |

---

## Phase 0 — Reproduce and baseline (half a day)

**Do**

1. Record a 20-rep session on the physical phone with voice enabled, at least
   two angle breaks and one deliberately bad cue (for example, receiving
   platform).
2. Note every spoken phrase, the time to the first spoken cue, the time between
   rep end and speech, the gap at each angle break, and every silent/failed
   utterance. Save the observations in
   `outputs/live-coaching-baseline-2026-11-14.txt` (ignored artifact).
3. Run the existing unit tests once and record the count:
   `./gradlew :app:testDebugUnitTest`.
4. Take a screenshot of the current review screen for the same session.
5. Record a second short session, stop during Front, and confirm it currently
   saves as a complete result with no Side stage. That is the bug Phase 2.5
   fixes.

**Done when** the baseline file contains: spoken turn count, approximate silent
reps, break delays, failures, the current test count, and proof that a
Front-only stop currently saves as complete.

---

## Phase 1 — Make voice reliable first (2–3 days)

Voice is the foundation. Do this before changing the coaching script.

### 1.1 Extract a testable voice engine

Create a small interface around the platform engine, for example:

```kotlin
interface VoiceEngine {
    fun initialize(onReady: (Boolean) -> Unit)
    fun speak(text: String, utteranceId: String, flush: Boolean): Boolean
    fun stop()
    fun shutdown()
}
```

`CoachingAudio` keeps ownership of audio focus, the queue and lifecycle.
`AndroidVoiceEngine` wraps `TextToSpeech`. Tests use a fake engine. Do not
change the public API used by `CaptureScreen` unless necessary.

**Files**: new `app/src/main/java/com/volleyballlab/player/ui/coaching/VoiceEngine.kt`;
update `CoachingAudio.kt`.

### 1.2 Fix the initialization dead ends

In `CoachingAudio.initializeEngine()` / `configureVoice()`:

- Set the language first (`Locale.US` or the installed English locale), then
  choose a voice. `tts.voices` can be empty or null right after initialization;
  that must not mean "unavailable".
- Prefer an offline English voice, but accept the default voice if no offline
  voice is listed. A `setVoice()` failure is not fatal.
- Keep `Unavailable` for a real failure: engine construction failed, the
  language is unsupported, or voice data is missing and `setLanguage` failed.
- Add a 6-second init watchdog. If the init callback never arrives, shut down
  and retry once. After the retry, expose `Degraded` / `Unavailable`, not a
  permanent `Initializing`.
- Keep the existing test-voice path working; do not block it on a new state.

**Done when** a fake engine that returns no voices still becomes `Ready`, and a
fake engine that never calls back becomes `Unavailable` within 7 seconds.

### 1.3 Recover from one utterance error

Replace the permanent failure in `utteranceFailed()`:

- First `onError` for an utterance: mark the utterance failed, reinitialize the
  engine once after ~300 ms, and requeue the text (same priority, one attempt).
- Second consecutive error: set `Degraded` and continue with visual + haptic.
  Do not kill voice for the rest of the session.
- Reset the error counter after a successful `onStart`/`onDone`.
- Log a small status transition (`Initializing → Ready → Degraded`) under one
  tag, e.g. `CoachingAudio`.

**Done when** a fake engine that throws one `onError` speaks the retried text,
and two consecutive errors leave the session usable with visual coaching.

### 1.4 Make the watchdog utterance-scoped

Today `speechWatchdog` calls `currentUtterance?.let(::utteranceFinished)`. A
watchdog left over from an old utterance can terminate the new one after 15 s.
Replace it with a runnable that captures its own `utteranceId` and attempt:

```kotlin
val watchdog = Runnable { if (currentUtterance == id) utteranceTimedOut(id) }
main.postDelayed(watchdog, timeoutMs)
```

Remove the previous callback before scheduling a new one. On a timeout with no
`onStart`, retry once with the default voice; then give up on that utterance and
drain the next pending cue.

**Done when** a unit test starts utterance A, flushes it with B, lets A's old
watchdog fire, and B is still marked speaking.

### 1.5 Never drop a cue because audio focus was denied

In `pump()` the pacer message is taken before `requestFocus()`. If focus is
denied, the message is lost. Change the order:

1. Check focus.
2. If denied, keep the pending message, register for the focus-gain callback,
   and retry after 250 ms for up to 3 s.
3. If focus is still denied, keep the message for the next rep boundary or
   expire it by its own deadline.

Also handle transient focus loss (`AUDIOFOCUS_LOSS_TRANSIENT`,
`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`) by pausing and resuming, not by ending the
turn permanently.

**Done when** `SpeechPacerTest` or a new `CoachingAudio` test verifies that a
denied focus request leaves the cue pending and it is spoken after focus gain.

### 1.6 Countdown gets its own lane and a fallback

- Speak `"three"`, `"two"`, `"one"` (words), not bare digits.
- Use a separate utterance id and do not let guidance speech block it.
- If voice is not ready or muted, still animate the countdown and fire the
  haptic; the session must start on time.
- Show the voice state in the framing card: `Voice ready`, `Restarting voice…`,
  or `Voice unavailable — visual coaching only` with a settings action. Do not
  show this during a rep.

**Done when** the countdown works with TTS disabled and does not wait for a
pending guidance turn.

### 1.7 Do not replay guidance after resume

- Add `spokenGuidanceId` (or a segment index) to the rotation state or the
  screen model.
- On `setCaptureForeground(true)`, only set `speechPending` if the current
  guidance has not already been spoken.
- If a multi-segment guidance was interrupted, resume at the next unspoken
  segment, not the first one.

**Done when** backgrounding during a break and returning does not repeat the
already-heard sentence.

### 1.8 Tests and device matrix

Unit tests (new or extended):

- `VoiceEngineTest`: init success with empty voice list; init timeout; one-error
  recovery; watchdog isolation; focus denial requeues.
- `SpeechPacerTest`: pending survives focus denial; repeat key is cue-based, not
  text-based.
- `CoachingAudioTest`: mute stops speech; unmute does not replay; `close()`
  releases the engine.

Device checks:

- Airplane mode, English voice data installed.
- English voice data removed (expect `Degraded`, visual cues still work).
- Bluetooth headset connected/disconnected mid-session.
- Notification during a cue; screen off/on; mute/unmute.
- Three sessions back-to-back without reopening the app.

**Done when** 50 consecutive cues produce no silent drop in the fake test and
the device matrix shows no stuck `Initializing` / permanent `Unavailable` state.

---

## Phase 2 — Replace reactive chatter with a Coach Director (3–4 days)

### 2.1 Add the Coach Director

New pure-Kotlin class: `app/src/main/java/com/volleyballlab/player/pose/analysis/CoachDirector.kt`.

It consumes completed reps and stage events, and returns at most one
`CoachTurn`:

```kotlin
data class CoachTurn(
    val text: String,
    val kind: CoachTurnKind,       // Open, Cue, Confirm, Milestone, Break, Finish, Tracking
    val cueId: CueId?,
    val priority: SpeechPriority,
    val visual: CoachCard?,         // optional card content
    val maxWords: Int,
)
```

The director owns:

- the opening turn and the first-rep window,
- cue selection and persistence,
- the silence budget,
- anti-repetition,
- stage break and finish scripts.

`ReactiveCoachingEngine` may remain as the low-level cue text source, but the
director decides **whether and when** anything is said. Do not let both classes
emit independently.

### 2.2 The live flow

Use these states. The names are the contract; the UI can keep its existing
`Recording` state and rotate through them internally.

```
Setup
  ↓ (countdown, recording starts)
Open(1 setup cue)
  ↓
FirstRep ──► Coach (cue or specific confirm)
  ↓
Quiet(1 rep) ──► Coach
  │
  ├─ good rep ──► Confirm (max 1/3 good reps)
  ├─ halfway / target ──► Milestone
  └─ angle target reached ──► Break ──► Countdown ──► FirstRep ──► Coach
Finish
```

Rules:

1. **Open** every session with one short setup cue at the countdown, before the
   first rep. It is a setup instruction, not a diagnosis: use the drill's Ready
   step or the skill's primary setup cue, 4–10 words. Examples: `"Arms ready —
   platform together."` / `"Hands up — soft elbows."` / `"Balanced feet — toss
   in front."` Do not repeat the framing card.
2. **Coach the first completed rep.** Do not wait for two reps. At the rep
   boundary, if any measurable cue is below `0.80`, speak the highest
   `weight × (1 - score)` cue. If the first rep is clean, speak one specific
   confirmation instead of staying silent (subject to the confirmation cap).
   The first rep is silent only when no cue is measurable.
3. **Trigger a cue only after a completed rep**:
   - after the first rep, any measurable cue below `0.80` can trigger;
   - after that, select the highest `weight × (1 - score)` from the current rep;
   - ignore jump height for live correction.
4. **Speak between reps**, at FollowThrough or after the rep boundary. Never
   during Preparation or Contact. "Engage first" does not mean talking over the
   movement.
5. **Silence budget**: after a cue, the next completed rep is quiet. Max one new
   cue per two completed reps; max four cues per ten reps.
6. **Same cue**: do not speak it again for 15 s or two reps. If it persists, use
   a *different action* from the phrasebook, or say one "same focus" line, not
   the original sentence.
7. **Confirm**: when the corrected cue scores `>= 0.92`, emit one specific
   confirmation after the rep. Haptic is immediate and always first. Max one
   spoken confirmation per three good reps.
8. **Milestones**: only at halfway and at the rep target. Include the clean-rep
   count and the current focus; no generic "keep it controlled" line.
9. **Tracking lost**: one line, then silence for at least 10 s. Do not repeat it
   every few seconds.
10. **Mute**: visual cards and haptics continue. Do not queue speech for later
    unmute; drop stale cues.

### 2.3 The phrasebook

New `CoachPhrasebook.kt`. Each key has four to six variants:

```kotlin
data class PhraseKey(
    val cueId: CueId,
    val kind: Kind,          // Action, Confirm, Repeat, Summary
    val direction: Direction, // Below, Above, Neutral
    val severity: CueSeverity,   // Minor, Major
)
```

Opening setup cues are a **separate small table keyed by `skill`/`drillId`**,
not by cue. Prefer the drill's `DrillPostureSamples.steps[0].cue`, shortened to
4–10 words; keep one fallback per skill. They are setup-only, never a
diagnosis, and never claim a measurement.

Requirements:

- No identical **correction** sentence twice in a session. The setup cue may
  repeat at a new stage when it is the same setup focus; it is not a correction.
- No cue-specific line reused until all its variants have been used.
- Opening setup cue: **4–10 words**, one instruction, no diagnosis.
- Correction cues: **8–12 words**, one observation and one action, or one
  action only.
- Break/finish: **at most 25 words** for the coaching content, plus one short
  movement instruction.
- No digits, no "always/every rep", no diagnosis, no injury/medical claims, no
  "I saw the video", no other cue names.
- Prefer positive external focus: "Set your platform early", not "Don't let it
  split".
- Vary the sentence opening: "Your…", "Keep…", "Set…", "Same focus —…", "That's
  it." Do not start every cue with "Next rep".

### 2.4 The angle break

Replace the current `summary + lastRepSummary + direction` message in
`CaptureViewModel.requestRotation()` with a three-beat script:

1. **Outcome**: `"Five reps. Three clean."`
2. **Focus**: only if there is a repeated cue that was not just spoken; otherwise
   omit the beat. This is the anti-echo rule.
3. **Move**: one short instruction, e.g. `"Turn sideways. Hitting arm to the
   camera."`

Rules:

- Compute and speak the local script immediately. Do **not** wait for
  `liveCoachingRepository.advise()`.
- The AI card may replace the on-screen advice when it returns within the
  timeout. It must never delay speech, the view confirmation, or the countdown.
- When the AI card arrives, update only `advice`. Do not flip `speechPending`
  back to `true`, do not allocate a new `guidanceId`, and do not replay a turn
  that was already spoken. A late AI reply is a card upgrade, not a second
  coaching turn.
- Show `"Preparing your coaching…"` only while the local script is being built;
  it should be a sub-second state, not an 8-second one.
- If voice is unavailable, show all three beats as a card and start the
  countdown after a short fixed hold (~2.5 s) or on tap.
- Keep the `"listen"` label only while audio is actually playing.
- The break's **Move** beat is the setup cue for the new stage. Do not repeat it
  after the countdown; coach the first completed rep of the new stage at its
  boundary.

### 2.5 The finish

Add a finish turn when the session stops normally:

1. Best thing: one specific cue that held.
2. One focus for the next session.
3. One next action: a view, a drill, or "same cue next time".

Example: `"Ten reps. Six clean. Your platform held in the last four. Keep your
elbows set early. Next session, we'll check the same cue from the side."`

No score, no percentage, no "great job". The result screen already carries the
numbers.

### 2.6 Tests

- `CoachDirectorTest`: opening setup cue; first completed rep coached; no
  two-rep silent window; one quiet rep after a cue; one cue per two reps; 15 s /
  two-rep repeat gap; confirmation after improvement; milestones only at
  halfway/target; break anti-echo; finish.
- `CoachPhrasebookTest`: four variants rotate; no duplicate correction text in
  30 turns; opening cue word limit; no digits; banned-word check.
- `LiveCoachingFlowTest`: a scripted 12-rep session opens with one setup cue,
  coaches the first rep, and leaves at least 40% of reps without voice.

**Done when** the scripted session never repeats a correction sentence, never
speaks the same cue twice inside 15 s, produces no more than four cues in ten
reps, and the first completed rep is coached.

---

## Phase 2.5 — Make the side stage mandatory (1–2 days)

Front and Side are both required. Rear is optional. A guided session that never
reaches Side is not a completed assessment and must not be presented as one.
This phase changes capture gating; the comparison UI is Phase 3.5.

### 2.5.1 Put the policy in one testable place

Extend `GuidedCaptureProgress` instead of scattering booleans through the
screen:

```kotlin
object GuidedCaptureProgress {
    val angles = listOf(CameraAngle.Front, CameraAngle.Side, CameraAngle.Rear)
    val requiredAngles = setOf(CameraAngle.Front, CameraAngle.Side)

    fun isRequired(index: Int): Boolean = angles.getOrNull(index) in requiredAngles
    fun canSkip(index: Int): Boolean = !isRequired(index)

    sealed interface StopDecision {
        data object Finish : StopDecision
        data class ContinueToSide(val stageIndex: Int) : StopDecision
        data class Blocked(val repsRemaining: Int) : StopDecision
    }

    fun stopDecision(
        stageIndex: Int,
        stageReps: Int,
        options: GuidedCaptureOptions,
    ): StopDecision
}
```

`stopDecision` is pure and table-driven:

| Current stage | Reps | Result |
|---|---|---|
| Front | any | `ContinueToSide(sideIndex)` |
| Side | `< repsPerAngle` | `Blocked(repsPerAngle - reps)` |
| Side | `>= repsPerAngle` | `Finish` |
| Rear | any | `Finish` |
| Guided disabled | any | `Finish` |

`stopDecision` is evaluated for the stage currently being recorded. A rotation
card is handled separately in `onStopRequested`: while the requested stage is
Side, keep the card up and do not call `stopDecision`; while it is Rear, Finish
is allowed. A Finish while being asked to turn to Side is a request to continue
to Side, not a request to save.

Add the `stopDecision` and `canSkip` tests before wiring the UI. They are the
contract the rest of this phase depends on.

The resulting flow:

```
Framing → Countdown → Front (target)
  ├─ target reached ──► Break → Countdown → Side (required)
  └─ Finish ─────────► Break → Countdown → Side (required)

Side (required)
  ├─ target reached ──► Rear prompt (optional) or Finish
  ├─ Finish before target ──► blocking card, keep recording
  └─ limit / End early ──► save incomplete

Rear (optional)
  ├─ target reached ──► Finish
  └─ Skip ───────────► Finish
```

### 2.5.2 Gate the controls

In `CaptureViewModel`:

- `onSkipAngle()`: if `!GuidedCaptureProgress.canSkip(rotation.stageIndex)`, do
  not advance. Set `RotationUi.blockedReason = "Side view is required"`, fire
  the error haptic once, and leave the rotation card up. Do not scold.
- `onStopRequested()`: call `stopDecision` before any teardown.
  - `ContinueToSide` → `finishStage(state)`, then `requestRotation(sideIndex)`.
    Do not enter `Analysing`.
  - `Blocked` → keep the timer and camera alive; set the blocking state with
    `"N more reps from the side"`; speak one short line at most once per 10 s
    through the Coach Director, not a second speech path.
  - `Finish` → existing stop path.
- The recording-limit stop must bypass the gate:
  `onStopRequested(force = true)` when `elapsed >= limit`. Save the session with
  the incomplete marker from 2.5.4. Never trap the athlete in an unsaveable
  session.
- `onRetryAngle()` stays available for both required and optional stages.
- Keep the existing `orientationGate`; do not start the Side countdown until the
  athlete is actually sideways.
- `RotationUi.timedOut` on Side must not auto-advance. Show Retry and the
  `End session early` escape; silently skipping would recreate the bug this
  phase fixes.
- Back/navigation must follow the same rule: leaving a guided session may
  abandon it or save it as incomplete, but it must never produce a complete
  result with no Side stage.

`RotationUi` gains:

```kotlin
val required: Boolean = false,
val blockedReason: String? = null,
val repsRemaining: Int? = null,
```

Clear `blockedReason` on a successful orientation or retry.

### 2.5.3 Make the UI say what is required

`LiveSessionControls`:

- Stage strip: `Front ✓ · Side (required) · Rear (optional)`.
- During rotation to Side, hide **Skip angle**. Show `Side view required` as a
  disabled outlined button with one line underneath: `Turn sideways so knee
  load and trunk angle can be checked.` Keep **Retry**, plus the `End session
  early` escape for an athlete who cannot turn.
- During rotation to Rear, keep **Skip**; it finishes the session.
- The primary button during Front says `Continue to side`, not `Finish session`.
- During Side below target the primary button stays `Finish session`; tapping
  opens the blocking card `N reps left on side — keep going` instead of
  stopping. Do not disable the button without an explanation.
- During Side at/after target, Finish is available; Rear remains an optional
  extra.
- Add one escape hatch, `End session early`, as a secondary text button in the
  blocking and Side rotation cards. It requires confirmation, calls
  `onStopRequested(force = true)`, and saves an incomplete session (2.5.4).
  Never make it the default action.

Copy rules: no "must", no "you failed", no "can't finish". Use outcome language:
`Two more from the side, then we're done.` / `Side view keeps the review
honest.` / `Turn sideways — this view is required for the review.`

### 2.5.4 Record whether the session is complete

Do not change scoring. Add review metadata only:

```kotlin
@Serializable
data class GuidedStageReview(
    val angle: CameraAngle,
    val reps: Int,
    val validReps: Int,
    val reachedTarget: Boolean,
)

@Serializable
data class GuidedReview(
    val requiredAngles: List<CameraAngle> = listOf(CameraAngle.Front, CameraAngle.Side),
    val targetReps: Int,
    val stages: List<GuidedStageReview>,
    val complete: Boolean,
    val endedEarly: Boolean = false,
)
```

- `VideoReview` gains `guided: GuidedReview? = null`; `CapturedReviewInput`
  carries it from `CaptureCompletionInput` (`guidedOptions`, `angleStages`,
  `endedEarly`).
- `complete = stages.any { it.angle == CameraAngle.Front && it.reps > 0 } &&
  stages.any { it.angle == CameraAngle.Side && it.reachedTarget }`. Side must
  reach its target; Front must at least exist so the comparison has two views.
- `endedEarly = true` only for the forced recording-limit stop or the explicit
  `End session early` action. It is informational; only `complete == false`
  triggers the incomplete banner.
- Legacy sessions without Side data get `complete = false`; `endedEarly` is
  unknown and shown as just `Side view not captured`.
- If you prefer not to put this in `VideoReview`, derive the same fields from
  `SessionResult.angleStages`; never add flags to scoring.
- `SessionResultScreen` can say `Side view not captured` from `angleStages`
  alone. Load the review sidecar only when it needs the detailed `2 of 5` copy.

Review and result copy:

- Complete: normal Coach Reel.
- Incomplete: `Session incomplete — side view not captured`, `Front view not
  captured`, or `Side view stopped early (2 of 5 reps)`. The reel still plays.
  If only one view exists, label it `Front view only` / `Side view only`; if
  both exist but Side is short, keep both and label `Side view partial`. Never
  present an incomplete session as a complete assessment.
- Free practice (`GuidedCaptureOptions.enabled == false`): `Free practice — no
  angle review`. Do not show a required-side warning; the athlete opted out.

### 2.5.5 Recording limit and 10-rep sessions

`MAX_GUIDED_RECORDING_MS` is five minutes. Three stages at ten reps can exceed
it, which would now force an incomplete Side stage. Before shipping, either:

- scale the limit from `repsPerAngle`, for example
  `max(300_000, repsPerAngle * 3 * 12_000)`, or
- keep five minutes for the five-rep option and extend the ten-rep option.

The limit must be long enough that a normal Front + Side session cannot hit it.
The forced stop remains as a safety valve.

### 2.5.6 Tests

- `GuidedCaptureProgressTest`: required set; `canSkip(Side) == false`;
  `canSkip(Rear) == true`; stop table for Front/Side/Rear and guided-off.
- `CaptureViewModelStageGateTest`: Finish during Front requests Side and does
  not enter `Analysing`; Skip during Side stays on Side; Finish during Side at
  3/5 is blocked with 2 remaining; Finish at 5/5 stops; Finish during Rear
  stops; the recording limit forces a save with `endedEarly = true`.
- UI/instrumented test: the Side rotation card has no Skip action; Side progress
  shows the remaining count; the blocking card appears on Finish.

**Done when** a scripted session that tries to Finish during Front, Skip Side,
and Finish Side at 2/5 still ends with five Side reps, or is explicitly saved
as incomplete by the limit/confirm path. No path saves a guided session as
complete with zero Side reps or zero Front reps.

---

## Phase 3 — Coach Reel playback in the review screen (3–5 days)

### 3.1 Add rep windows to the review data

`VideoReview` currently stores poses and issues, but not rep boundaries.
`SessionResult.reps` already has `startMs`, `contactMs`, `endMs`.

Add:

```kotlin
@Serializable
data class ReviewRep(
    val repNumber: Int,
    val startMs: Long,
    val contactMs: Long,
    val endMs: Long,
    val formScore: Int?,
    val angle: CameraAngle = CameraAngle.Unknown,
)

@Serializable
data class ReviewIssue(
    // existing fields…
    val angle: CameraAngle = CameraAngle.Unknown,
)

@Serializable
data class VideoReview(
    // existing fields…
    val reps: List<ReviewRep> = emptyList(),
    val guided: GuidedReview? = null,
)
```

- `CapturedReviewInput` gains the rep list and guided metadata, mapped through
  `videoOriginCameraMs` like the issues.
- `CaptureCompletion` already scores each chunk with its `CameraAngle`; carry
  that angle into every issue and rep it produces. Give `RepResult` windows a
  global `repNumber` (`offset + index + 1`) and compute `formScore` with
  `rep.formScore(skill)`.
- `VideoReviewFeedback.analyse()` should return `Feedback(issues, reps,
  assessedCueCount)` so legacy analysis also produces angle-tagged windows from
  the segmenter. Update `RecordedVideoAnalyzer` to store them.
- `VideoReviewCache` bumps `VERSION` to 2. Read versions `1..VERSION`; a v1
  record decodes with `reps = emptyList()`, `guided = null`, and
  `angle = Unknown`. `ignoreUnknownKeys` already helps forward compatibility.
- If `reps` is empty, infer windows from issue clusters: start = earliest issue
  timestamp − 1.2 s, end = latest issue timestamp + 1.2 s. Never infer a rep
  from a single issue without a visible skeleton. Inferred reps inherit the
  issue angle when all issues in the cluster agree, otherwise `Unknown`.

**Done when** a new recording saves rep windows with angles and guided
completeness, an old v1 sidecar still reads, and a legacy recording produces a
usable reel plan with an explicit `Unknown` angle where the view cannot be
proven.

### 3.2 Build the reel plan

New `CoachReel.kt` in `pose/review`:

```kotlin
data class CoachReel(val sessionId: String, val clips: List<CoachReelClip>)

data class CoachReelClip(
    val repNumber: Int,
    val angle: CameraAngle,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val freezeAtMs: Long,
    val cueId: CueId,
    val severity: ReelSeverity,
    val headline: String,
    val comment: String,
    val measured: Float,
    val target: ClosedFloatingPointRange<Float>,
    val points: List<Int>,
)
```

Selection rules:

- A rep is **failed** if any measurable issue score is below `0.80`.
- Failed reps from **both** Front and Side are eligible. Rear clips are eligible
  only when the session recorded Rear.
- Priority order: red (`< 0.35`) first, then lowest score, then highest cue
  weight. One clip per rep.
- Cap at six clips. If more than six failed reps, keep the six most severe and
  add "Showing the six biggest corrections" on the reel intro. If both views
  have failures, reserve at least one clip per view before filling by severity,
  so the reel is not all Front.
- Clip window: prep + contact + follow-through. Use the rep window when
  available; otherwise `max(0, fault − 1.5 s)` to `min(duration, fault + 1.5 s)`.
  Cap each clip at 8 s.
- Merge overlapping windows; do not show the same fault twice.
- Directly reuse `ReviewIssue.points` and `ReviewSkeleton` drawing.
- Each clip carries its `angle`; the UI labels it `Front · Rep 3` or
  `Side · Rep 9`. Never label a clip with just `Rep 3` when both views exist.
  If the angle is `Unknown` (legacy data), label it `Rep 3` and do not invent a
  view.

**Done when** `CoachReelPlanTest` selects the correct reps for a fixture with
five failed reps across both views and three clean reps, orders red before
amber, reserves a clip for each view when both failed, and caps at six.

### 3.3 Add the reel UX

In `AnnotatedSessionVideo`:

- Extract `ReviewPlayback` and `ReviewSkeleton` from private file-local classes
  into a shared `ReviewPlayer.kt` (or make them `internal` there) before
  building the reel. Keep `CoachReelPlayer.kt` for reel orchestration. Do not
  duplicate the skeleton drawing.
- Add a primary action: **Play Coach Reel**.
- Reel mode uses the existing `VideoView` / `ReviewPlayback`.
- For each clip:
  1. Seek to `sourceStartMs`, play.
  2. At `freezeAtMs`, pause and show the skeleton with the affected joints
     (amber/red) and a callout bubble anchored to the joint.
  3. Keep the freeze for 1.5 s or until tap; show the comment, the angle, and
     the measured value vs target.
  4. If both views have this cue, show a `Compare front + side` action on the
     freeze card. It pauses the reel and opens the Phase 3.5 card.
  5. Advance to the next clip automatically.
- The timeline shows one marker per failed rep, not one per issue. Use red for
  severe, amber for the rest. Markers from both views share the timeline;
  angle is shown in the callout and chip, not by colour alone.
- Add clip chips: `Front · Rep 3`, `Side · Rep 9`, … so the athlete can jump.
- "Full video" toggles back to the existing free playback and the full issue
  list.
- If the session has no Side view, do not present the combined reel. Show
  `Front view only` and keep the single-view reel. A legacy session is
  reviewable, but it is not a complete Coach Reel.
- If both views exist but Side is short, keep both views and label
  `Side view partial`. The comparison is still valid; only the "complete
  assessment" claim is withheld.
- Reprint the privacy line: this is a practice reference, not a validated
  coaching grade.

New strings in `video_review_strings.xml`; do not hard-code them.

**Done when** the reel plays failed reps from both views in order, each with one
callout and an angle label, the compare action opens the split card, a
missing-side session is labelled `Front view only`, and the full-video path
still works.

### 3.4 Grounded per-rep comments

The comment must be specific enough to coach, not a copy of the cue label.

Local builder (`ReelComment.kt`):

```
Observation: "Your platform split before contact."
Action:      "Set your elbows before the ball arrives."
Evidence:    "Measured 24°, target 0–15°."   // card only, not spoken
```

- Use `FormCue` label, target, unit, measured value and the existing
  `coachingTip` as approved source material.
- One observation + one action, max 120 characters for the coaching text.
- No digits in the spoken form; digits are fine on the card.
- If the rep has multiple issues, one primary and at most one secondary:
  `"Also: knees not loaded."` Do not stack three callouts.
- If the cue has no approved tip, fall back to the label and measured value.

Optional AI polish (Phase 4, feature-flagged):

- Add a `coachReelComment` endpoint in `ai-worker/src/live-coaching.mjs` or a
  sibling module.
- Input: `skill`, `cueId`, `position` (`below`/`above`), `severity`
  (`minor`/`major`). No video, no landmarks, no account identifiers beyond the
  existing auth check.
- Output: `{ cueId, text }`, max 120 characters, same validation style as
  `validateLiveAdvice`: exact cue, no digits, no diagnosis/medical/video claims,
  no other cue names.
- Rate limit conservatively and cache per rep/cue/severity. Timeout 3 s; always
  fall back to the local comment. Never block playback.
- Label `"AI coaching"` only when the AI text was accepted; otherwise
  `"Coach note"`.

**Done when** every selected clip has a grounded comment, the card shows the
measured value and target, and no comment contains a digit, a diagnosis, or an
unapproved cue.

---

## Phase 3.5 — Front + side posture comparison (1–2 days)

This is the side-by-side view the sketch and the manuscript gap call for. It
uses **stills at the measured phase** (contact for contact cues), not two
simultaneous video players, to avoid a second decoder and a synchronisation
problem that cannot be solved (the front and side stages are recorded one after
the other).

### 3.5.1 Build the comparison model

New `PostureComparison.kt` in `pose/review`:

```kotlin
data class PosturePane(
    val angle: CameraAngle,
    val repNumber: Int?,
    val timestampMs: Long?,
    val pose: ReviewPose?,
    val issue: ReviewIssue?,
    val available: Boolean,
)

data class PostureComparison(
    val cueId: CueId?,
    val front: PosturePane,
    val side: PosturePane,
    val highlightedPoints: List<Int>,
    val referencePhase: Int,   // cue.phase.ordinal, mapped to DrillPostureSamples
    val comment: String,
)
```

Builder entry point:

```kotlin
fun forCue(
    review: VideoReview,
    drillId: String,
    cueId: CueId,
    selectedRep: Int?,
): PostureComparison
```

Builder rules:

1. Select the failed rep the athlete opened (from the reel or an issue card).
2. For the selected cue, find the **worst** issue in each view: lowest score,
   then earliest timestamp. Use the issue's `timestampMs` for the still, not
   the rep's contact time; that is the frame where the cue was actually
   measured. Fall back to `contactMs` only when the issue has no timestamp.
3. If the other view has no issue for that cue, use its rep whose contact is
   nearest the selected rep, but do **not** highlight joints and do **not**
   claim the cue was measured there.
4. `highlightedPoints` is the union of the points actually visible in each
   pane; filter each pane against `ReviewPose` visibility before drawing.
5. `comment` is the same grounded `ReelComment` for the selected cue. One
   comment for the pair, not two competing diagnoses.
6. `referencePhase = cue.phase.ordinal.coerceIn(0, 3)`, mapped to the matching
   `DrillPostureSamples` step. It is contact for contact cues, not contact for
   every cue.

Honesty rules:

- The two stills are **separate reps**, because front and side are recorded in
  separate stages. The card says so: `Recorded in separate stages — compare the
  shape, not the instant.` Do not imply it is one movement.
- A missing issue means `Not measured from this view`, never "good".
- Do not compare raw values across angles as if they were the same
  measurement. Show each pane's value only when that angle produced a measured
  issue.
- Respect `AngleCuePolicy`: if the cue is not supported from an angle, that pane
  is a posture reference, not a scored comparison.
- If either pane's `angle` is `Unknown` (legacy sidecar), do not guess which
  view it is. Show `View angle not recorded` and the single-view still; the
  comparison requires both views to be proven.

### 3.5.2 The card

New `PostureComparisonCard.kt` in `feature/train/result`, used inside
`AnnotatedSessionVideo`:

```
┌ Same cue, two views ─────────────────────────┐
│ ┌──────────────┐  ┌──────────────┐          │
│ │ FRONT · Rep 4│  │ SIDE · Rep 9 │          │
│ │  still +     │  │  still +     │          │
│ │  skeleton    │  │  skeleton    │          │
│ │  highlighted │  │  highlighted │          │
│ │  24° / 0–15° │  │  Not measured│          │
│ │  Contact     │  │  Contact     │          │
│ └──────────────┘  └──────────────┘          │
│  [reference posture at the cue phase]        │
│  "Set your elbows before the ball arrives."  │
└──────────────────────────────────────────────┘
```

- Two equal-weight panes in one `Row`; keep them side by side down to 360 dp.
  Do not stack them on a narrow phone. Force LTR layout direction for the pair
  so Front is always on the left and Side on the right, even in an RTL locale;
  the labels carry the meaning.
- Use a compact pane ratio and small labels; the user asked for side by side,
  not a vertical list.
- Each pane: still bitmap, `ReviewSkeleton` overlay, highlighted joints,
  angle badge, rep label, measured/target line. Tap seeks the full video to
  that timestamp and pauses.
- Draw the still and the skeleton with the same `ReviewFit` inside the same
  pane bounds. Do not crop the bitmap and fit the skeleton separately; a
  half-frame misalignment makes the joint highlight look wrong.
- Missing pane: dashed border, camera icon, `Side view not captured`, and the
  reason. Never draw a placeholder skeleton as if it were the athlete.
- Reference strip: `DrillPostureFrame(drillId, referencePhase, …)` with
  `Reference posture (illustration)` and the sample's cue text. Reuse the
  existing renderer; do not add new art. Hide the strip when
  `DrillPostureSamples.forDrill(drillId)` is null. The illustration is not a
  camera-angle-specific reference; do not overlay it on the athlete's still or
  imply it is a side-view photograph.
- Shared comment below the panes, with the `Coach note` / `AI coaching` label
  from Phase 3.4.
- Content descriptions: `Front view, rep 4, platform angle 24 degrees, target 0
  to 15 degrees`. Do not rely on colour alone.
- The card is available from: the review header (`Compare views`), a reel
  freeze (`Compare front + side`), and an issue card for a cue measured in both
  views.
- Placement: below the player and above the full issue list. When both views
  exist and at least one rep failed, show the card for the first failed rep by
  default; the header and reel actions reopen it for another cue. Do not hide
  the comparison behind a menu.

### 3.5.3 Load the stills safely

New `ReviewStillLoader.kt` in `pose/review`:

- Use `MediaMetadataRetriever.getFrameAtTime(timestampUs, OPTION_CLOSEST)` on
  `Dispatchers.IO`; two stills per card, capped at 720p.
- Apply the same rotation/mirroring as the review data; `ReviewPose` coordinates
  are in decoded-video space. Verify on a portrait fixture instead of assuming
  the retriever returns the same orientation as the player.
- Load both stills concurrently and show a neutral skeleton/placeholder while
  they decode. Never block the main thread or leave the card blank with no
  progress state.
- Cache by `(path, timestampMs)` in an `LruCache` of roughly 8 MB; close the
  retriever after each load or keep one per path and close it with the screen.
- Do not create a second `VideoView` / ExoPlayer. Pause the reel or full-video
  player while the card is open.
- If a frame cannot be decoded, show the skeleton on a neutral background with
  the timestamp and `Frame unavailable`. Never show an empty black pane.
- If `review.frameAt(pane.timestampMs)` returns null, show the still without a
  skeleton and label `Skeleton unavailable`. Do not draw a skeleton from a
  distant frame just to fill the pane.
- The stills and the comparison card stay on the device. Do not upload them to
  the AI worker or any other service; the optional AI comment receives cue
  metadata only.

### 3.5.4 Tests

- `PostureComparisonBuilderTest`: worst issue per view; no issue in the other
  view → no highlight and `Not measured`; missing Side → unavailable pane;
  both angles `Unknown` → no comparison and `View angle not recorded`; inferred
  legacy reps; selected cue; shared comment; separate-rep copy.
- `PostureComparisonUiTest` (Compose): two panes exist at 360 dp; front is on
  the left and side on the right in LTR and RTL; missing-side placeholder;
  content descriptions; tap seeks.
- Instrumented `ReviewStillLoaderTest`: extract two fixture frames, verify
  non-null bitmaps, cache hit, retriever closed.
- Device: open the card during reel playback; confirm the reel pauses, the card
  opens under ~500 ms, no second decoder is created, and rotation/background
  restores it.

**Done when** a complete guided session shows both stills side by side with
skeletons and highlighted joints, a missing side shows an honest placeholder,
the shared comment matches the selected cue, and no second video decoder is
started.

---

## Phase 4 — Export the Coach Reel (3–5 days, spike first)

Do not start this before Phase 3 plays well in the app.

### 4.1 Renderer spike (1 day)

Goal: one 10-second MP4 with two trimmed clips and one static callout overlay,
playing in the phone gallery.

Try in this order:

1. **Media3 Transformer** with `ClippingConfiguration` per clip and an
   `OverlayEffect` / bitmap overlay for the freeze-frame callout. This is the
   least custom code if the dependency works with the current AGP/compileSdk.
2. **Custom pipeline**: reuse `SequentialVideoDecoder`, draw the freeze frame
   and callout to a bitmap, encode with `MediaCodec` surface input, mux with
   `MediaMuxer`, and copy the source audio track. More code, but it reuses the
   existing decoder and keeps dependencies small.

Decision rule: choose the option that produces a correct file in the spike with
the least new code. Record the decision in the PR, not in a new ADR unless it
changes a public contract.

### 4.2 Export implementation

- Build a composition: optional 1.5 s title card, each reel clip, optional 2 s
  outro. If title/outro cards are expensive, ship clips only in v1.
- Format: 720p (or source resolution capped at 720p), 30 fps, H.264, AAC
  128 kbps. Preserve the source orientation and aspect ratio.
- Freeze overlay: pre-render the skeleton at `freezeAtMs` plus the callout as a
  bitmap. The sketch's overlay is a still callout, not a live skeleton, so a
  static bitmap is sufficient.
- Optional comparison card: when both Front and Side exist, add a 2–3 s split
  still (front | side) before the related clip. Reuse the Phase 3.5 composition;
  do not render two videos.
- Progress UI with cancel; write to a temp file, then atomically publish.
- Save through `MediaStore` (`Movies/VolleyballLab`) on Android 10+; use the
  existing FileProvider pattern for share. Never upload to cloud.
- Maximum reel length 90 s; if exceeded, drop the lowest-severity clips.
- Share text: skill, date, rep count. No raw landmarks, no sidecar, no account
  identifiers.

**Done when** a 5-clip reel exports, plays in Gallery, has correct duration and
both video/audio tracks, and survives rotation/backgrounding during export.

---

## Phase 5 — Verification and documentation (1–2 days)

### 5.1 Automated

Run:

```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
cd ai-worker && npm test
```

Expected new coverage:

- `VoiceEngineTest`
- `CoachingAudioTest`
- `CoachDirectorTest`
- `CoachPhrasebookTest`
- `GuidedCaptureProgressTest`
- `CaptureViewModelStageGateTest`
- `CoachReelPlanTest`
- `ReelCommentTest`
- `PostureComparisonBuilderTest`
- `PostureComparisonUiTest`
- `VideoReviewCacheTest` (v1 → v2)
- an instrumented `CoachReelDeviceTest` that exports a fixture and verifies
  duration/tracks with `MediaMetadataRetriever`
- an instrumented `ReviewStillLoaderTest` that extracts front and side stills
  from a fixture

### 5.2 Device

Use the physical Infinix (or the current test phone):

- 10-minute session, 3 angle breaks, voice on.
- Guided session: try to Finish during Front, Skip Side, and Finish Side before
  the target. Confirm the app insists on Side without trapping the athlete.
- Guided session at the recording limit: confirm it saves as incomplete and
  says so.
- Free-practice session: confirm it is labelled `no angle review`.
- Open the front/side comparison card from the header, a reel freeze, and an
  issue card. Confirm both panes are visible at 360 dp and the reel pauses.
- Airplane mode and no voice data.
- Bluetooth headset connect/disconnect.
- Notification during speech; screen off/on; mute/unmute.
- Save, reopen, play the reel, export, share. Confirm the app still opens the
  original full video.

### 5.3 Docs

Update:

- `docs/live-coaching.md`: the director flow, silence budget, phrasebook, voice
  recovery, no-network break rule, and the required Front + Side stage policy.
- `docs/annotated-video-review.md`: rep windows and angles, Coach Reel, comment
  grounding, front/side posture comparison, incomplete-session labels, export
  and privacy.
- `docs/manuscript-gap-audit.md`: change the side-by-side camera/reference pane
  from "not implemented" to implemented with the still-frame comparison and
  its limitations.
- `GLOSSARY.md`: add Coach Reel, Coach Director, required view, and posture
  comparison once the terms are in the UI.
- `docs/ai-coach-system-status.md` only if the optional AI endpoint ships.

Record the baseline and final observations in `outputs/` (ignored, not
versioned).

---

## Reference A — exact live script deck

Use this as the copy source. Variants are examples, not the only allowed text;
the phrasebook must still rotate them.

### Start

One setup cue at the countdown, before the first rep. It is not a diagnosis.
The framing card can say: `One cue at a time. Let's start with the setup.`

Opening examples:

- Receiving: `"Arms ready — platform together."`
- Setting: `"Hands up — soft elbows."`
- Serving: `"Balanced feet — toss in front."`
- Spiking: `"Ball in front — arm ready."`
- Blocking: `"Hands up — feet set."`

Then coach the **first completed rep** at its boundary. Do not wait two reps.
If the first rep is clean, say one specific confirmation.

### Cue shapes

- Minor, below: `"Your platform is drifting apart. Set it earlier."`
- Major, below: `"Your platform is splitting before contact. Lock your elbows
  first."`
- Above: `"You're reaching too far. Let the ball come to you."`
- Confirmation: `"That's it. Same platform again."`
- Same focus: `"Same focus — set the platform before the ball."`
- Tracking: `"Step back into frame. I'll pick up when you're ready."`

### Angle break

- `"Five reps. Three clean."`
- `"One focus: set your platform before contact."` (only if not just spoken)
- `"Turn sideways. Hitting arm to the camera."`

If the last cue was less than 15 s ago, omit the middle beat. The athlete has
already heard it; repeating it is the awkwardness. The Move beat is the setup
cue for the new stage; do not repeat it after the countdown. Coach the first
completed rep of the new stage.

### Finish

`"Ten reps. Six clean. Your platform held in the last four. Keep your elbows
set early. Next session, we'll check the same cue from the side."`

### Never say

- `"Good job"` / `"Great work"` with no specific evidence.
- `"Keep it controlled"` / `"Keep your next movement controlled"`.
- `"Always"` / `"every rep"` / `"you're doing it wrong"`.
- A numeric score or percentage in live speech.
- A cue that was not measured on the completed rep.
- A claim that the app watched the video or knows the ball flight.

---

## Reference B — voice failure matrix

| Failure | Current behavior | Required behavior |
|---|---|---|
| `tts.voices` empty right after init | `Unavailable` | Set language, accept default, `Ready` |
| No offline English voice | `Unavailable` | Use default/network voice if available; otherwise `Degraded` with settings action |
| Init callback never fires | Stuck `Initializing` | 6 s timeout → retry once → `Unavailable` |
| One `onError` | Permanent `Unavailable` | Retry once; then `Degraded`, visual continues |
| Audio focus denied | Cue dropped | Keep pending; retry on gain |
| Notification ducks audio | `stopNow()` | Pause/resume; only stop on permanent loss |
| Old watchdog after flush | Can end new utterance | Id-scoped watchdog |
| Countdown with voice off | Silent countdown | Visual + haptic countdown |
| Resume after background | Replays full guidance | Resume next unspoken segment |
| Mute then unmute | Old cue may replay | Drop stale cue; resume from next rep |

---

## Reference C — data contracts

### `CoachTurn` (new, pure Kotlin)

```kotlin
enum class CoachTurnKind { Cue, Confirm, Milestone, Break, Finish, Tracking }

data class CoachTurn(
    val text: String,
    val kind: CoachTurnKind,
    val cueId: CueId?,
    val priority: SpeechPriority,
    val maxWords: Int,
)
```

### Phrasebook types (new, pure Kotlin)

```kotlin
enum class PhraseKind { Observe, Action, Confirm, Repeat, Summary }
enum class CueDirection { Below, Above, Neutral }
enum class CueSeverity { Minor, Major }

data class PhraseKey(
    val cueId: CueId,
    val kind: PhraseKind,
    val direction: CueDirection,
    val severity: CueSeverity,
)
```

### `VideoReview` (additive, cache v2)

```kotlin
@Serializable data class ReviewRep(
    val repNumber: Int,
    val startMs: Long,
    val contactMs: Long,
    val endMs: Long,
    val formScore: Int?,
    val angle: CameraAngle = CameraAngle.Unknown,
)

@Serializable data class ReviewIssue(
    // existing fields…
    val angle: CameraAngle = CameraAngle.Unknown,
)

@Serializable data class GuidedStageReview(
    val angle: CameraAngle,
    val reps: Int,
    val validReps: Int,
    val reachedTarget: Boolean,
)

@Serializable data class GuidedReview(
    val requiredAngles: List<CameraAngle> = listOf(CameraAngle.Front, CameraAngle.Side),
    val targetReps: Int,
    val stages: List<GuidedStageReview>,
    val complete: Boolean,
    val endedEarly: Boolean = false,
)
```

`VideoReview` gains `reps: List<ReviewRep> = emptyList()` and
`guided: GuidedReview? = null`. Defaults let version 1 sidecars keep reading;
`angle = Unknown` is the safe value for data that never recorded a view.

### `CoachReelClip`

```kotlin
enum class ReelSeverity { Amber, Red }

data class CoachReelClip(
    val repNumber: Int,
    val angle: CameraAngle,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val freezeAtMs: Long,
    val cueId: CueId,
    val severity: ReelSeverity,
    val headline: String,
    val comment: String,
    val measured: Float,
    val target: ClosedFloatingPointRange<Float>,
    val points: List<Int>,
)
```

### `PostureComparison` (derived, not persisted)

```kotlin
data class PosturePane(
    val angle: CameraAngle,
    val repNumber: Int?,
    val timestampMs: Long?,
    val pose: ReviewPose?,
    val issue: ReviewIssue?,
    val available: Boolean,
)

data class PostureComparison(
    val cueId: CueId?,
    val front: PosturePane,
    val side: PosturePane,
    val highlightedPoints: List<Int>,
    val referencePhase: Int,
    val comment: String,
)
```

Build this from `VideoReview.reps`, `VideoReview.issues`, and
`VideoReview.frameAt(issue.timestampMs)` (falling back to the rep's
`contactMs`). Do not add it to the sidecar; it is cheap to derive and must
follow the latest review data.

### Optional `coachReelComment` request

```json
{ "data": { "skill": "Receiving", "cueId": "PassPlatformStraight",
  "position": "below", "severity": "major" } }
```

Response: `{ "result": { "cueId": "...", "text": "...", "source": "groq" } }`.
Reject anything that is not exactly this shape.

---

## Reference D — acceptance tests

### Live coaching

- 12-rep scripted session: no sentence repeated; no same cue within 20 s; at
  least 50% of reps have no voice; at most four cues in ten reps.
- First two reps are silent even if a cue is already failing.
- A severe single rep can trigger one cue, but only after the rep ends.
- A good rep fires the haptic immediately; spoken confirmation is at most one
  per three good reps.
- Halfway and target milestones fire once each.
- Angle break uses three beats and omits the focus beat if the cue was just
  spoken.
- With the network disabled, the break still speaks immediately and the
  countdown still starts.

### Voice

- 50 cues with a fake engine: zero silent drops.
- One `onError`: retried and spoken.
- Two `onError`: `Degraded`, visual cues continue.
- Missing voice data: `Degraded`, settings action shown, session usable.
- Focus denied then granted: cue spoken once.
- Old watchdog cannot end the new utterance.
- Background/resume does not replay a completed guidance turn.

### Guided capture

- Finish during Front requests Side and does not save.
- Skip during Side does nothing; the rotation card stays.
- Finish during Side at 3/5 is blocked with `2 remaining`.
- Finish during Side at 5/5 saves.
- Finish during the Rear prompt saves; Rear is optional.
- The recording limit saves an incomplete session and never loses it.
- Free practice saves normally and is labelled `no angle review`.
- No guided-session path produces a complete result with zero Side reps.

### Coach Reel

- 5 failed + 3 clean reps: 5 clips, red before amber, correct order, total
  ≤ 75 s.
- More than 6 failed: 6 clips, most severe, intro says "six biggest".
- With failures in both views, at least one clip from each view is kept.
- Clip chips read `Front · Rep 3` / `Side · Rep 9`; no bare `Rep 3` when both
  views exist.
- Callout text is one observation + one action, ≤ 120 characters, no digits in
  the spoken form.
- Skeleton and highlighted joints align with the video frame at the freeze.
- Timeline has one marker per failed rep, not per issue.
- A missing-side session is labelled `Front view only`, not a complete reel; a
  short-side session is labelled `Side view partial` but keeps the comparison.
- Full-video mode still works.
- Export plays in Gallery with video and audio; duration matches the plan.

### Posture comparison

- Complete session: card shows both panes side by side at 360 dp, with the
  correct angle and rep labels.
- Front pane is leading, side pane is trailing; they do not stack vertically.
- Missing side: placeholder with `Side view not captured`, no fake skeleton.
- Cue not measured in one view: `Not measured from this view`, not "good".
- Highlighted joints exist only where the pose is visible.
- Shared comment matches the selected cue and is not duplicated per pane.
- Opening the card pauses the reel; no second video decoder is created.
- The card says the stills are separate reps, not one instant.

---

## Reference E — file map and suggested tickets

### Files to add

- `app/src/main/java/com/volleyballlab/player/ui/coaching/VoiceEngine.kt`
- `app/src/main/java/com/volleyballlab/player/pose/analysis/CoachDirector.kt`
- `app/src/main/java/com/volleyballlab/player/pose/analysis/CoachPhrasebook.kt`
- `app/src/main/java/com/volleyballlab/player/pose/review/CoachReel.kt`
- `app/src/main/java/com/volleyballlab/player/pose/review/ReelComment.kt`
- `app/src/main/java/com/volleyballlab/player/pose/review/PostureComparison.kt`
- `app/src/main/java/com/volleyballlab/player/pose/review/ReviewStillLoader.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/result/ReviewPlayer.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/result/CoachReelPlayer.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/result/PostureComparisonCard.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/result/CoachReelExporter.kt` (Phase 4)

### Files to update

- `app/src/main/java/com/volleyballlab/player/ui/coaching/CoachingAudio.kt`
- `app/src/main/java/com/volleyballlab/player/ui/coaching/SpeechPacer.kt`
- `app/src/main/java/com/volleyballlab/player/pose/analysis/ReactiveCoachingEngine.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/capture/GuidedCaptureProgress.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/capture/CaptureViewModel.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/capture/CaptureCompletion.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/capture/CaptureScreen.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/capture/LiveSessionControls.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/result/AnnotatedSessionVideo.kt`
- `app/src/main/java/com/volleyballlab/player/feature/train/result/SessionResultScreen.kt` (incomplete-session label)
- `app/src/main/java/com/volleyballlab/player/pose/review/VideoReview.kt`
- `app/src/main/java/com/volleyballlab/player/pose/review/CapturedVideoReview.kt`
- `app/src/main/java/com/volleyballlab/player/pose/review/RecordedVideoAnalyzer.kt`
- `app/src/main/java/com/volleyballlab/player/data/session/VideoReviewCache.kt`
- `app/src/main/java/com/volleyballlab/player/pose/analysis/SessionScorer.kt` (attach angle to issues and rep windows)
- `app/src/main/res/values/live_coaching_strings.xml`
- `app/src/main/res/values/video_review_strings.xml`
- `docs/live-coaching.md`
- `docs/annotated-video-review.md`
- `docs/manuscript-gap-audit.md`
- `ai-worker/src/live-coaching.mjs` (only if AI comments ship)

### Suggested ticket order

1. **P0 — Harden live coaching voice and recovery.** Phase 1. No product copy
   changes. Blocks reliable Phase 2.
2. **P0 — Add quiet Coach Director and phrasebook.** Phase 2.1–2.3, 2.6.
3. **P0 — Remove network wait and echo from angle breaks.** Phase 2.4–2.6.
4. **P0 — Require Front + Side capture stages.** Phase 2.5. Gate Skip/Finish,
   add incomplete-session metadata, keep Rear optional.
5. **P1 — Retain rep windows and view angles in video review.** Phase 3.1.
6. **P1 — Add Coach Reel playback to session review.** Phase 3.2–3.3.
7. **P1 — Add grounded per-rep coach comments.** Phase 3.4 local; optional AI.
8. **P1 — Add front/side posture comparison.** Phase 3.5. Reuses `ReviewSkeleton`
   and `DrillPostureFrame`; no new art.
9. **P2 — Export Coach Reel MP4.** Phase 4.
10. **P1 — Device verification and docs.** Phase 5.

Labels: `ready-for-agent` on 1–8; `needs-triage` on 9 until the renderer spike
is done.

---

## Assumptions / decisions to confirm

These are the defaults this plan assumes; change them before Astra starts if
they are wrong:

1. **Tone**: calm, technical, short. Not hype, not a drill sergeant.
2. **Reel first**: in-app playback is the first deliverable. MP4 export is
   second.
3. **AI comments**: local grounded comments always ship; the optional LLM
   polish is behind a flag and never blocks review.
4. **No scoring changes**: the plan changes presentation and speech, not the
   measurement model.
5. **One cue**: the coach gives one correction at a time. Multiple simultaneous
   corrections are out of scope for live coaching.
6. **Side is mandatory**: Front and Side are required in a guided session;
   Rear is optional. Free practice is exempt but is labelled `no angle review`.
   An incomplete session is saved and labelled, never presented as complete.
7. **Comparison is stills, not split video**: the front/side card uses two
   measured-phase stills and the existing reference posture sheet. The two
   views are separate reps; the UI says so and never implies a single
   synchronous moment.
8. **One shared comment**: the comparison has one grounded comment for the cue,
   not two competing diagnoses.
