# Volleyball Lab — player app + coach portal

An AI-powered volleyball training and form-detection app. BSIT capstone,
University of Cebu, College of Computer Studies.

This repository holds **both halves of the product**, on one shared Firebase
backend (`volleyball-lab`):

- `app/` — the **Android player app**, including the on-phone coach shell.
- `coach-web/` — the **coach web portal** (Vite + TypeScript + Three.js):
  registration, roster, review queue, drill assignments and written feedback.
- `firestore.rules` + `docs/coach-cloud-contract.md` — the shared schema and
  its enforcement, which is what makes the two clients work together.

The module implemented in depth here is **Training & Pose Capture Management**:
skill selection → drill selection → camera session → full-body pose capture →
pose-quality validation → AI feedback → session results. The surrounding app
shell exists so that flow can be demonstrated end to end.

---

## Build and run

Requires Android Studio 2026.1 or newer and an Android 8.0 (API 26) device.

1. Open the project in Android Studio and let it sync. The Gradle wrapper is
   configured for **Gradle 9.7.1** and will be fetched automatically.
2. **Install the pose model.** It is ~9 MB of binary weights and is not committed:

   ```bash
   curl -L -o "app/src/main/assets/pose_landmarker_full.task" https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task
   ```

   Without it the app still builds and runs; the capture screen reports the
   missing model instead of crashing. See
   [`app/src/main/assets/README.md`](app/src/main/assets/README.md).
3. Run on a **physical device**. The emulator has no usable camera feed and no
   GPU delegate. In debug builds, if the detector fails to start, the capture
   screen offers a scripted synthetic detector so the flow is still walkable —
   it is never substituted silently, and its scores mean nothing.

Verified locally with:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

297 unit tests pass as of 24 October 2026. Lint has no errors; warnings remain.
See [current verification and release gates](docs/app-completion-status.md).

### Updated manuscript delivery

The app now includes five skill learning guides and common mistakes, guided
camera angles with reactive TTS, skeleton loading states, seven-day progress
and weekly rhythm, timed daily plans, editable goals, local profile photos,
CSV/JSON export, email verification/password changes, and a notification inbox
with optional Android delivery. Free detailed reports default to five per
Monday–Sunday week; saved reports can be reopened without another credit.

Students select a coach and request approval. No invite code is used.
Owner Pro belongs to the confirmed Firebase UID for `kirt@gmail.com`; other
accounts read server entitlements. GCash checkout for APK distribution is
prepared and disabled pending merchant activation, price/access period,
deployment and live payment verification.

See [the manuscript checklist](docs/manuscript-gap-audit.md) for PDF pages,
code evidence and remaining validation, and [GCash setup](docs/gcash-checkout-setup.md).

The portal builds separately:

```bash
cd coach-web
cp .env.example .env   # Firebase web config, same project
npm install && npm run build
```

### How an account gets into it

There are now two real doors, both leading to the same backend:

1. **The portal** (`coach-web/#/register`) — a coach registers with their team
   details. This writes the Firebase Auth account, `users/{uid}`
   (`role: "COACH"`), the profile and the directory listing. Players find them
   in the app's Coach tab immediately, and the coach account lands in the coach
   shell on any install — the role comes from the account, not the device.
2. **The app login** — unchanged for players. There is still no coach option on
   the mobile sign-up form and there should not be one: coaches register on the
   portal, the app only ever *receives* an account that already exists.

`VolleyballLabApp` reads the account's role once and builds one of two shells.

---

## Toolchain

Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml);
no build file contains a version literal.

| | |
|---|---|
| AGP | 9.3.1 |
| Gradle | 9.7.1 |
| Kotlin | 2.4.10 |
| Compose BOM | 2026.08.00 (Compose 1.12.0, Material3 1.4.0) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| CameraX | 1.6.1 |
| MediaPipe Tasks Vision | 1.0.0 |

**AGP 9 note.** From AGP 9.0 the Android plugin has built-in Kotlin support.
Applying `org.jetbrains.kotlin.android` alongside it is now a hard error, not a
warning, so it is deliberately absent from both build files.

**No annotation processors.** No Hilt, no Room, therefore no KSP. Rationale is in
[`di/AppContainer.kt`](app/src/main/java/com/volleyballlab/player/di/AppContainer.kt)
— briefly: the graph is a handful of objects, and on a student laptop an extra processor
costs time on every edit. Everything is constructor-injected against interfaces,
so moving to Hilt later is mechanical.

---

## Architecture

Single `:app` module, layered by responsibility:

```
com.volleyballlab.player
├── ui/theme        Colour, type, shape and motion tokens
├── ui/component    Reusable animated primitives (ScoreArc, AnimatedCounter, …)
├── ui/icon         Hand-drawn skill pictograms, one pose per skill
├── domain/model    Skill, Drill, FormCue, TrainingSession, AthleteProfile,
│                   UserRole, UserPlan, CoachProfile, TeamRoster, SessionReview
├── domain/repository  Interfaces only
├── data            Current implementations (static catalogue, DataStore-backed
│                   sessions, profile, role, plan and coach data)
├── pose            The engine: detector, quality gate, analysis, scoring
├── feature         One package per screen, each with its ViewModel
│   └── coachmode   The coach shell: dashboard, roster, player, review, assign
├── navigation      Type-safe routes and the two NavHosts
└── di              AppContainer
```

### The pose pipeline

```
CameraX frame → Bitmap (upright) → PoseDetector → PoseFrame
                                                     │
                            ┌────────────────────────┴───────────────────┐
                            │ live                              post-hoc │
                     PoseQualityValidator                   RepSegmenter
                     LiveRepCounter                         SkillAnalyzer
                     PoseOverlay                            SessionScorer
```

Two details worth knowing before changing anything here:

**Two coordinate spaces, not interchangeable.** MediaPipe returns image-space
landmarks (normalised, perspective-projected) and world-space landmarks (metres,
hip-centred). Angles must come from world space or the same elbow bend measures
differently depending on how far the athlete stands from the lens. Drawing must
come from image space. Both live on `PoseFrame`, named for their purpose.

**Jump height cannot come from world space.** World landmarks are hip-centred by
definition, so the hips sit at the origin in every frame, standing or airborne.
`Angles.hipRiseMetres` instead measures hip displacement in image space and
converts it to metres using the athlete's own torso length as a scale reference —
recalibrated every frame, so it survives the athlete drifting nearer the camera.

**Unmeasurable is not zero.** A cue that could not be read — an occluded joint, a
side-on camera angle — scores `null`, and the remaining cue weights are
renormalised around it. Scoring it zero would blame the athlete for the camera
placement.

**Multiple people in view.** MediaPipe detects up to three poses. The player
starts in the centre for the framing gate; after that, a continuity tracker keeps
the same person when pose result order changes. A bystander does not silently
take over when the player briefly leaves the frame.

**Video review.** CameraX records a silent MP4 alongside pose analysis when the
device supports the preview, analysis and video use cases together. The result
screen plays it from app-private storage. If video binding fails, the camera
falls back to pose-only capture and still saves the scored session. Deleting a
session removes its private video.

**MPJPE evaluation.** The revised manuscript sets a research target below 50 mm
for each of the five skills. `tools/pose-pipeline/evaluate_mpjpe.py` compares the
app model's metric 3D joints with independently measured 3D ground truth and
reports overall and per-skill error plus annotation coverage. There is no
measured MPJPE claim yet; the evaluator needs calibrated reference data. Its
input contract and limitations are in `tools/pose-pipeline/README.md`.

---

## Coach mode: one app, two shells — plus the portal

The capstone document draws the coach side as a desktop portal (Figures 23–29).
That portal now exists (`coach-web/`), and the phone keeps its coach shell too:
a coach at a desk reviews in the browser, a coach on court reviews on the phone,
both against the same Firestore rows. Nothing about the player app changed to
make room for either.

### How an account gets into it

`VolleyballLabApp` reads the account's role once and builds one of two shells.
They share the splash and the two auth screens and nothing after them — different
tabs, different graphs, different back stacks. A role change rebuilds the graph
rather than navigating inside it, so a coach who switches to the player app
cannot press back into a review queue that is no longer theirs.

**Coaches register on the web portal, not here.** There is no coach option
anywhere on the mobile sign-up form and there should not be one — this app only
ever *receives* an account that already exists. The login form likewise asks
nothing about roles: `User_Role` is a property of the account (Table 7), so at
login the app reads `users/{uid}.role` — what the portal wrote.

Two stand-ins remain for the offline and legacy paths:

- **An address convention** — `coach@…`, or the plus-tagged `you+coach@gmail.com`.
  Exact, not a prefix match: `coaching@` and `headcoach@` are ordinary addresses
  and do not qualify. See `CoachAccounts`. Still applied at sign-in, promotion
  only.
- **A switch in Profile**, and one back the other way in the coach's Account tab.

Precedence for one account on one phone: an explicit choice recorded on the
device (either of the above) wins; otherwise the cloud row — set when a coach
registers on the portal — decides; otherwise Player. So a portal-registered
coach lands in the coach shell on a fresh install, and a deliberate in-app
switch is never silently undone by either fallback.

The address rule only ever *promotes* — it can grant the coach role, never take it
away — because a rule that could demote would silently undo that switch on the next
launch.

### Signing in

Use your own registered account. Coaches register at `coach-web/#/register`.
Students choose a coach from the directory and send a request for approval.
There are no built-in demo accounts or account switching shortcuts.

**The limitation this has, stated plainly:** the address rule and the Profile
switch are per-device records, so an account that only ever used them still
lands in the player shell on a *fresh* install. A coach who registered on the
portal does not — their role comes from `users/{uid}` and follows them.

### Screens, against the figures

| Screen | Document |
|---|---|
| `CoachDashboardScreen` | Fig. 25 — review queue and team figures |
| `CoachRosterScreen` | Fig. 26 — roster, plus Fig. 27's join queue |
| `CoachPlayerScreen` | Storyboard — 30-day trend, consistency, skill breakdown, history |
| `CoachReviewScreen` | Fig. 25's detail panel — scorecard, analysis, comment box |
| `CoachAssignListScreen` / `CoachAssignEditScreen` | Fig. 28 |
| `CoachAccountScreen` | Fig. 29 |
| `CoachLinkSection` (player side) | Figs. 17, 20, 21 and 22 |

Figure 25's two-pane layout becomes a list that opens a detail screen — nothing is
dropped, but a coach taps once more than a coach at a desk would. Everything else
in the figure set has a counterpart.

### How a coach and a player get linked

**The player chooses, and the coach answers.** This is the direction the updated
document specifies (Fig. 6.2) and it is worth stating plainly, because earlier
builds tried both alternatives:

1. A **Pro** athlete opens their Coach tab and browses a directory of coaches —
   name, specialisation, and how many athletes are already on the team (Fig. 20).
2. They open one profile, read who they are about to ask and the rules of the
   arrangement, and send a connection request (Fig. 21). Nothing is written until
   they send it, so backing out is free.
3. The coach approves or declines from the join queue on their Players tab
   (Fig. 27). Only the coach can move a request past `Pending`.

**There are no invite codes.** An earlier build had the coach publish a
six-character code for the player to type in. That was dropped and the type
deleted: a code makes the player prove they already know which coach they want,
which is backwards for a feature sold as *find a coach*. A briefly-tried
coach-adds-directly version was dropped too, for the consent reason below.

**Roster size is the only number on a listing the coach did not write about
themselves**, which is why it is the one worth showing. A listing deliberately
carries no contact address, no licence number and nothing about the coach's *other*
players — a directory that leaked squad names would hand out other athletes'
details to anyone who opened the tab.

**New coach connection requests require Pro.** The ViewModel reads owner/server
entitlement and Firestore rules independently check status and expiry. An athlete whose
plan drops back to Free **keeps a link they already have**. Locking an existing
relationship behind a paywall would be taking something away rather than gating
something new. GCash setup is documented separately.

**Consent runs the right way now.** Being on a roster is something the athlete
asked for; the coach could only accept an offer that was made. The athlete's
ongoing control is that their Coach tab names the team with **Leave team** right
there, and that their sessions reach the review queue only when they submit one.
`SessionDirectory` — the single narrow seam through which one account's training
reaches another's screen — documents that its only legitimate caller is the coach
shell, and only for a uid that came out of the roster flow.

**Coach connections use the shared Firestore backend.** The directory, pending
requests, approved roster, assignments, submissions and notes work across the
app and portal. Students read the public directory; licence/preferences stay
in the coach's private profile.

### What the "AI-Assisted Analysis" panel actually is

`SessionReview` pools the per-rep cue scores already stored with a session, sorts
them, and takes the top and bottom few. There is no model. The screen says so, in
those terms, under the panel — along with the fact that every threshold behind it
is a first-pass coaching value rather than a measured one, because Experiment 2
has not run. The same goes for the safety panel: a flag there means a joint angle
sat outside a band we guessed at and the clip is worth watching. It is not an
injury assessment and the copy must not drift toward implying that it is.

The player-detail screen's "AI-recommended corrective drill" is likewise shown as
what it is — the lowest-scoring cue — computed by the same rule the athlete's own
Coach tab uses, so the two screens cannot tell the same person different things.

### What the notification switches do

Persist a preference, and nothing else. Delivery needs the Notification entity in
Table 18 and a scheduler, neither of which exists here. A line under the switches
says so.

---

## Design system

Three colours are fixed anchors; everything else is a tonal ramp built around
them.

| Role | Hex |
|---|---|
| Primary — burnt orange | `#CC5500` |
| Page background (`background` / `surface`) | `#F0F8FF` Alice Blue |
| Ink (`onSurface`) | `#141C33` |

Because the page is tinted rather than white, the container ramp steps *down*
in lightness from it — a card at `surfaceContainer` is a shade deeper than the
page it rests on, and only `surfaceContainerLowest` goes the other way to pure
white. Cards lighter than a tinted page read as holes cut in it.

Full derivation is documented in
[`ui/theme/Color.kt`](app/src/main/java/com/volleyballlab/player/ui/theme/Color.kt).
Material You dynamic colour is deliberately **not** enabled: the rust primary
carries meaning (it marks the record control and the active training state) and
letting the wallpaper repaint it would break both the brand and the affordance.

**Typeface.** The reference specifies **Hanken Grotesk**. It is not bundled yet,
so the app currently resolves to the platform sans. Swapping it in is a one-line
change at `VbFontFamily` in
[`ui/theme/Type.kt`](app/src/main/java/com/volleyballlab/player/ui/theme/Type.kt),
which documents the exact steps.

### The coach shell's dialect

The coach half uses the same palette and the same tokens, laid out differently,
in
[`feature/coachmode/CoachModeUi.kt`](app/src/main/java/com/volleyballlab/player/feature/coachmode/CoachModeUi.kt).
Two deliberate departures, both about what the two halves of the app are *for*:

- **Cards sit at `surfaceContainerLowest` (white) with a 2dp shadow**, where the
  athlete's screens use `surfaceContainerHigh` and read as recessed. A training
  screen is one thing at a time; a coach page is a long scroll of unrelated
  blocks — a queue, a stat strip, a roster — and lifting each off the tinted page
  is what keeps that legible.
- **Four categorical accent tones** (`CoachAccent`), each a pale tint behind a
  saturated glyph, assigned by *what a thing is* and then never moved: rust for
  people, indigo for review and queues, green for sessions, violet for written
  material. This exists precisely so the rust primary can stay an affordance —
  the thing to tap, the thing that needs attention — instead of being spent on
  decorative fills.

Everything else follows from those: 24dp card radii, icon chips leading every
section, ExtraBold figures over small grey labels, dashed wells for empty lists,
and exactly **one** dark navy `CoachFeatureCard` per screen carrying the single
thing the coach is being asked to act on. One per screen is the whole mechanism —
on a page of white cards a second navy card costs the first all of its emphasis.

### Motion

Every animation pulls its duration and easing from `VbMotion`. Nothing calls
`tween(300)` with a literal.

All motion helpers collapse to zero duration when the user has disabled system
animations — `LocalReduceMotion` watches `ANIMATOR_DURATION_SCALE` with a
`ContentObserver`, so toggling the setting and returning to the app takes effect
immediately rather than at next process death.

The splash implements the state machine specified in
[`docs/rive/splash-contract.md`](docs/rive/splash-contract.md) — the same Intro /
Idle / Outro timings and the same `isReady` boolean semantics, including the
`Intro → Outro` transition that contract calls out as the one people forget.
Dropping in a real `splash.riv` later replaces one composable body and nothing
else. Section 7 of that contract left the splash background colour blank; it is
now decided: **`#F0F8FF`**, the same Alice Blue the app's pages use, and it must
stay identical in `Color.kt` (`VbSplashBackground`) and `res/values/colors.xml`
or there is a visible flash at the handoff.

---

## Capstone document conflicts

Five were resolved outright to make this build possible. Three remain open and
still need the team and adviser.

The numbering below is stable — other sections cross-reference it — so a conflict
that has since moved keeps its number and says so in place.

**Resolved**

1. **Real-time vs. post-hoc analysis** (Objective 3 vs. Fig. 14). Implemented as
   *both, at two fidelities*: a cheap live overlay, rep count and framing check
   during capture, then a full scoring pass over the buffered frames when
   recording stops. The results screen always reports the post-hoc numbers. The
   two rep counts can differ by one; that is expected, and documented in
   `LiveRepCounter`.
2. **Views vs. Compose** (Table 20 vs. Table 21). **Jetpack Compose**, matching
   the Table 21 narrative.
3. **Bottom navigation** (Fig. 11 vs. storyboard). **Fig. 11** — Home / Train /
   Progress / Coach / Profile. The storyboard version splits skill selection and
   capture across two tabs, breaking one task into two entry points and leaving
   the results screen homeless.
4. **Firestore vs. the relational data dictionary — Firestore, for the coach
   graph.** The network model specifies Cloud Firestore and the tables specify
   MySQL; the coach side (roster, assignments, submissions, notes, sessions
   shared for review, account roles) now lives in Firestore under
   `docs/coach-cloud-contract.md`, read and written by both the app and the
   `coach-web/` portal, enforced by `firestore.rules`. Player-private data
   (profile, plan, the athlete's own session history) stays in on-device
   DataStore blobs — nothing there says how anything should be *modelled*, only
   that it survives the process dying.
5. **MoveNet** appears in the tech-stack prose but in no table and no other
   section. The app uses MediaPipe/BlazePose.
6. **Coach accounts have their home: the portal.** Registration (Fig. 24's
   fields) lives at `coach-web/#/register` and writes Firebase Auth plus
   `users/{uid}` — which is Table 7's `User_Role` at last — plus the profile and
   directory rows. `CoachProfile` still combines Table 14 with the storyboard's
   team-setup fields, and the address convention plus Profile switch remain as
    device-level fallbacks with documented precedence.

**Still open — three need the team and adviser**

7. **Free/Pro tier uses owner/server entitlements.** The local demonstration
   toggle is removed from the production flow. The confirmed Kirt UID has owner
   Pro; other accounts require a trusted active entitlement. Pro includes coach
   connection and unlimited detailed reports. The default Free allowance is five
   detailed reports per week, tracked locally. GCash hosted checkout and signed
   webhook code are prepared for APK distribution; merchant activation and live
   payment tests remain pending. Advanced biomechanical claims still require
   independent validation; they are not sold as a measured result.
8. **Coach Mode drifts** between "AI personalisation" (Objective 4) and "a human
   coach reviewing remotely" (everywhere else). Coach mode now builds the second
   reading in full — a real person, a real roster, real notes written back — and
   the first stays exactly where it was: the athlete's Coach tab still pools their
   own cue scores across sessions and still says on screen that it was worked out
   on the device. The two sit on that one tab, in that order, without pretending
   to be each other. The drift in the document is still unresolved; what has
   changed is that neither reading is now blocked on it.
9. **Experiment 2 is still pending.** All twenty cue thresholds in
   `FormCueCatalog` are first-pass values from general coaching guidance, not
   measured data. They are gathered in one file specifically so the ten coach
   interviews can replace them as a contained edit. **Do not present the current
   numbers as validated.**

---

## What is real and what is not

**Real** — the design system; the splash; navigation; the whole pose pipeline
(detector, quality gate, segmentation, five skill analyzers, scoring); the
capture screen including permissions, framing gate, hands-free start, countdown,
live overlay and rep counting; the results screen; and all five tabs.

Progress, Coach and Profile were placeholders until the tabs were built out.
They are now real screens, and every number on them is **derived from sessions
the user actually recorded**. In the player app there is no sample data at all:

- **Progress** aggregates stored sessions: seven calendar days of scores,
  measurable valid reps, time and rhythm, plus latest-session trends,
  per-skill breakdown and history.
- **Coach** has two halves. At the top, the link to a real coach: the directory
  they pick from, the request they send, whose team they end up on, sessions they
  choose to submit, the notes that come back, the drills they were set, and the
  way out. Below it, the on-device advice —
  every rep of every cue pooled across sessions, the weakest surfaced with the
  coaching tip already written for it in `FormCueCatalog`, and a note when too
  many cues came back unmeasurable to trust it. The screen keeps the two apart
  and does not let the second claim to be the first — see conflict 8 below.
- **Profile** includes personal fields, goals, photo selection, CSV/JSON export,
  account security, coaching preferences, notification inbox and Pro status.

The coach shell is real on the same terms: every figure on it comes from sessions
its rostered players actually recorded and submitted. The portal shows the same
rows — one shared backend, two screens.

With nothing recorded, each screen says so and offers the way to fix it. That is
deliberate: during a defence, mock UI is indistinguishable from working UI, so a
chart with no data behind it is worse than an empty state.

**The one exception, and the condition it survives on.** A coach account with an
empty roster gets two stand-in athletes (`SampleTeam`) — Maya Reyes, improving at
spiking, and Andrei Cruz, whose block knee-load trips the safety watch. An
examiner opening a new coach account would otherwise see a roster of nobody, a
queue of nothing and four dashes where the team figures go, which demonstrates
nothing about a screen whose whole job is reading a squad.

This is allowed because it is **not indistinguishable from real data**, which is
what the rule above is actually against:

- every row they appear on carries a **Sample** chip, and the dashboard says in
  plain words that they are not real;
- the whole set **vanishes the instant a real player joins** — including while the
  request is still pending;
- their uids are prefixed `sample-`, so any screen holding a bare uid can tell;
- and **nothing about them is hardcoded**. Those sessions carry real per-rep cue
  scores, and `PlayerSnapshot`, `TeamSummary` and `SessionReview` derive the form
  scores, trend, consistency and safety flags from them by exactly the arithmetic a
  real athlete goes through. There is no written-down "78" anywhere; the 78 is
  computed. That is the part a panel is actually asking about.

Labelled scaffolding that removes itself is a different thing from UI that lies
about having data. If that distinction ever stops holding, delete it.

**Stand-in** — the drill catalogue is compiled into the app
(`StaticDrillRepository`) pending a drill-library backend. Notification delivery
uses optional Android alerts, periodic WorkManager refresh and prepared FCM
triggers. Live push requires deployment on an enabled Firebase project.

---

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

297 tests cover the pose/coaching engine, compatibility, seven-day aggregation,
report reset/reuse, goals, entitlement logic, notifications and progress export:

- `AnglesTest` — joint geometry, including that degenerate input returns `null`
  rather than zero, and that the image-space jump calibration is correct.
- `CaptureHoldGateTest` — the hands-free start: that holding still begins a
  session with nobody near the phone, that walking out of shot empties the gate
  instead of recording an empty gym, and that a cancelled countdown stays
  cancelled.
- `FormCueTest` — cue scoring, the graded tolerance band, and catalogue
  invariants (weights sum to 1.0 per skill, no duplicate or orphaned cue ids).
- `PoseQualityValidatorTest` — every framing rejection path and the stability
  window.
- `RepSegmenterTest` — drives the whole pipeline with `SyntheticPoseDetector` and
  asserts that a known number of movement cycles produces exactly that many reps.
- `TabDerivationsTest` — what Progress, Coach and Profile claim about training as
  a whole: that the trend runs forwards in time, that unscored sessions are
  excluded from averages rather than counted as zero, that advice is withheld
  until a cue has enough measured reps, and that nothing mediocre gets called a
  strength.
- `CoachAccountsTest` — exact coach address conventions and near misses.
- `CoachDerivationsTest` — what the coach screens claim about an athlete: that a
  trend needs enough scored sessions on both sides before it is printed, that one
  session is not "perfectly consistent", that a cue measured once is not a
  correction, that an unmeasurable session reads as a camera problem rather than
  a safety flag, and that the team average weights players equally instead of
  letting whoever films most decide the squad's number.
- `CloudSchemaTest` — the contract both clients share, pinned from the app side:
  every collection round-trips, unknown enum names degrade instead of throwing,
  session documents carry scores only (no landmark arrays), and a missing role
  row is distinguishable from an explicit one.

`SyntheticPoseDetector` is what makes `RepSegmenterTest` possible: it emits a
scripted articulated figure, so "did it count the reps" is an assertion rather
than someone watching a badge on a phone and deciding it looked about right.

---

## Known issues and limitations

- **Debug APK is ~144 MiB.** MediaPipe and LiteRT ship native libraries for multiple ABIs. A
  release App Bundle splits per-ABI and is far smaller; if install time during
  development becomes painful, add an `abiFilters` block for debug builds.
- **Portrait only.** The framing gate assumes a standing athlete filling 45–95%
  of frame height, which is a portrait composition. Recent Android versions
  ignore the orientation lock on large screens, so on a tablet or unfolded
  foldable the capture screen will be poorly composed. Supporting landscape means
  redesigning the framing rules, not deleting a manifest line.
- **One lint warning**, `ObsoleteSdkInt` on `res/mipmap-anydpi-v26`. Lint is
  correct that the `-v26` qualifier is redundant at minSdk 26, but removing it
  makes aapt2 fail to find the launcher icon. The warning is left in place rather
  than suppressed globally.
- Rep segmentation for **Receiving** assumes the athlete returns to a taller
  stance between reps. A drill performed entirely in a low crouch will
  under-count.
- **Block penetration** is only measurable from a side-on camera and reports
  itself as unmeasurable from a front view, rather than returning a confident
  zero.
- **Coach mode needs the network for first contact.** Roster, queue, assignments
  and shared sessions live in Firestore, cached offline after first sync. A
  signing-in on a fresh install with no signal sees empty coach screens until it
  connects — the same as auth itself, which already needs the network for the
  first sign-in. Player training capture and scoring are fully offline regardless.
- **Background alerts depend on Android and deployment.** Approval and ended
  coach connections now appear in the account-scoped inbox. Optional system
  alerts honor permission/preferences; periodic refresh may be delayed. Immediate
  FCM delivery requires deploying the prepared notification triggers.
- **Merchant activation is pending.** GCash checkout remains disabled until
  verified merchant configuration and live payment testing are complete.
- **A directory listing is whatever the coach typed.** Name, team, organisation,
  specialisation and years of experience are all self-reported and nothing
  verifies them, including the certification licence number the registration
  figure collects. Roster size is the one figure on a listing the app computes.
