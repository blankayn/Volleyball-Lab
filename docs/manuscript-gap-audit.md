# Updated manuscript implementation checklist

Audit date: 28 September 2026. Source: `Volleyball-Lab-3.pdf`, 161 PDF pages.
References below give the **PDF page** first and the manuscript's printed page
in parentheses. This is a code inventory, not a claim of validated coaching
accuracy or a percentage of completion.

The responsive-coaching change implements the live-coaching work separately
from the remaining manuscript checklist. Guided front/side/rear stages are a
user-requested addition; the manuscript does not explicitly require rotating
after 5 or 10 repetitions or a side-by-side camera/reference pane.

## Live-coaching delivery

| Requirement | Manuscript evidence | Code evidence | Status / verification |
|---|---|---|---|
| Live specific corrective feedback | PDF 83 (74), Figure 14; PDF 134 (125), Table 23.2 | `pose/analysis/LivePostureCoach.kt`, `feature/train/capture/CaptureViewModel.kt`, `CaptureScreen.kt` | Existing live cues; responsive phase-aware coaching and guided stages are delivered by this change. Physical-phone verification still required. |
| Valid reps, best streak, current form | PDF 83 (74), Figure 14 | `CaptureViewModel.kt`; `domain/model/TrainingSession.kt`; `feature/train/result/SessionResultScreen.kt` | Live estimates and saved per-angle summaries are added. Final analysis remains separately scored. Quality thresholds require coaching validation. |
| Voice coaching / TTS | PDF 87 (78), Figure 18; PDF 129 (120), Table 20 | `ui/coaching/CoachingAudio.kt`, `ui/coaching/SpeechPacer.kt`, `data/coaching/DataStoreCoachingPreferencesRepository.kt`, `feature/profile/ProfileScreen.kt` | Code implemented; verify with an installed Android English voice. No model-generated audio or network voice service required. |
| Haptics for valid reps | PDF 87 (78), Figure 18 mockup | `ui/coaching/CoachingAudio.kt`, `data/coaching/DataStoreCoachingPreferencesRepository.kt`, `ProfileScreen.kt` | Preference and valid-rep feedback added; confirm vibration behavior on phone hardware. |
| Guided camera turns and angle results | User-requested extension | `feature/train/capture/GuidedCaptureProgress.kt`, `CaptureViewModel.kt`, `domain/model/TrainingSession.kt`, `data/session/SessionRecords.kt`, `data/cloud/CloudSchema.kt`, `SessionResultScreen.kt` | Optional metadata preserves old session compatibility. Live counts are explicitly estimates. Additional views do not establish 3D accuracy. |

Paths in this table are under `app/src/main/java/com/volleyballlab/player/`
unless another root is stated. "Code implemented" does not mean physically
verified or research-validated.

## Manuscript delivery checklist

| Checklist | Manuscript evidence | Current code evidence | Status and concrete remaining work |
|---|---|---|---|
| [x] Correct-form learning and common mistakes | PDF 12 (3), Objective 3; PDF 16 (7), Reference Model; PDF 136 (127), Table 23.4 | `domain/model/SkillLearning.kt`, `feature/train/skill/SkillLearningDialog.kt`, `feature/train/drill/DrillSelectScreen.kt`, `domain/model/FormCue.kt` | **Code implemented.** Five skill guides include preparation/load/contact/finish, illustrations, common mistakes and camera tips. Remaining: coach review of demonstrations and biomechanical ranges. Educational illustrations and mascot animation are not scoring ground truth. |
| [x] Training-mode selection | PDF 136 (127), Table 23.4 | `feature/train/capture/CaptureScreen.kt`, `GuidedCaptureProgress.kt`, skill/drill selectors | **Implemented for the defined modes.** Ordinary capture or guided front/side/rear, with 5/10 reps per angle. The manuscript does not specify other named modes. |
| [x] Drill library and manual workouts | User-approved extension, 29 September 2026 | `domain/model/DrillGuide.kt`, `DrillPractice.kt`, `data/drill/DrillAccessController.kt`, `DataStoreDrillPracticeRepository.kt`, `feature/train/drill/DrillModuleScreens.kt`, `DrillModuleViewModels.kt` | **Code implemented.** Five fixed Free starters, all 15 for trusted Pro, guides/filters, configurable workouts/rest, account-scoped checkpoints/history, and separate camera form checks. Manual practice never creates form scores or coach submissions. See `docs/drill-module.md` and `outputs/build-verification.json` for current verification. |
| [x] Seven-day progress, training time, weekly rhythm | PDF 85 (76), Figure 16; PDF 106 (97), storyboard | `domain/model/WeeklyProgress.kt`, `feature/progress/WeeklyProgressCard.kt`, `ProgressScreen.kt`, `ui/component/CalendarDay.kt` | **Code implemented.** Seven calendar days, daily final score, measurable valid reps, duration and activity rhythm. Missing scores remain missing; latest-session history is separately labeled. Unit coverage includes day boundaries and insufficient cue coverage. |
| [x] Local progress export | PDF 87 (78), Figure 18, Data & privacy | `data/session/ProgressExport.kt`, `feature/profile/ProfileTrainingTools.kt`; tests in `app/src/test/java/com/volleyballlab/player/data/session/ProgressExportTest.kt` | **Code implemented.** User-selected CSV/JSON destination; final and live estimate sources labeled; private video paths excluded. Remaining: phone document-picker verification. |
| [x] Editable training goals | PDF 87 (78), Figure 18; PDF 106 (97), storyboard | `domain/model/AthleteProfile.kt`, `data/profile/DataStoreProfileRepository.kt`, `ProfileTrainingTools.kt`, `domain/model/SkillRecommendation.kt`, `HomeScreen.kt` | **Code implemented.** Saved focus skill, 1–14 weekly sessions and personal goal; Home recommendations use the selected focus. Goals are account-scoped on the device. |
| [x] Profile photo | PDF 106 (97), storyboard | `ProfileTrainingTools.kt`, `ProfileScreen.kt`, `DataStoreProfileRepository.kt`, `res/xml/backup_rules.xml` | **Code implemented.** Photo picker, bounded/downsampled image, EXIF orientation, private account-scoped storage, replacement/removal and display. Local only. Remaining: phone picker and rotation verification. |
| [x] Email/account verification | PDF 133 (124), Table 23.1 | `domain/repository/AuthRepository.kt`, `data/auth/FirebaseAuthRepository.kt`, `feature/profile/AccountSecurityCard.kt` | **Code implemented.** Signup verification email, resend and refreshed status; verified email required for paid checkout on client and server. Remaining: real mailbox/phone verification. |
| [x] In-app password change | PDF 133 (124), Table 23.1; PDF 104 (95), Figure 36 | `FirebaseAuthRepository.kt`, `AccountSecurityCard.kt`, `res/values/account_security_strings.xml` | **Code implemented.** Current-password reauthentication, new-password confirmation, recent-login errors and provider-aware controls. Forgot-password recovery retained. Remaining: phone account flow. |
| [x] Notification inbox/read status/delivery | PDF 125 (116), Table 18; PDF 135 (126), Table 23.3 | `domain/model/TrainingNotification.kt`, `data/notifications/TrainingNotificationRepository.kt`, `TrainingNotificationWorker.kt`, `feature/notifications/NotificationInbox.kt`, `MainActivity.kt` | **Code implemented.** Analysis, feedback, assignments, submissions, milestones and weekly coach overview; account-scoped inbox, read state, coach preferences and optional Android alerts. WorkManager polls cloud events; Android may delay background delivery. No FCM server push. Remaining: phone permission, tap and background verification. |
| [x] Scheduled “Today's plan” | PDF 80 (71), Figure 11 | `domain/model/TrainingAgenda.kt`, `data/training/TrainingWorkspaceRepository.kt`, `feature/home/TrainingAgendaCard.kt`, `HomeScreen.kt` | **Code implemented.** Add/edit/delete timed practices, mark done/undo, upcoming preview and due/overdue coach assignments. Local account schedule; due assignments open the Coach screen. |
| [x] Weekly detailed-report usage | PDF 80 (71), Figure 11; PDF 87 (78), Figure 18 | `domain/model/ReportAllowance.kt`, `TrainingWorkspaceRepository.kt`, `feature/train/result/DetailedReportCard.kt`, `SessionResultScreen.kt`, `feature/profile/ProPlanCard.kt` | **Code implemented.** Configurable default 5 Free reports per Monday–Sunday week; Pro unlimited. Generation succeeds before credit is stored; saved report reuse is free. Usage/reset shown in Home, Profile and Results. Ledger is device-local, not a server API spending limit. |
| [x] Student-to-coach connection | PDF 89 (80), Figure 20; PDF 135 (126), Table 23.3; latest user instruction | `feature/coach/CoachLinkSection.kt`, `data/coach/FirestoreCoachRepository.kt`, `firestore.rules` | **User-approved workflow.** Student chooses a coach, sends a request, and waits for approval; sharing remains explicit. No invite codes, as requested. Pro entitlement gates new coach requests. |
| [x] Owner Pro and prepared paid entitlement | PDF 88 (79), Figure 19; PDF 87 (78), Figure 18; user clarification | `domain/model/ProEntitlement.kt`, `data/plan/FirestorePlanRepository.kt`, `AppContainer.kt`, `ProPlanCard.kt`, `functions/index.mjs`, `functions/billing-policy.mjs`, `firestore.rules` | **Owner/entitlement code implemented; merchant activation pending.** Only the confirmed UID for `kirt@gmail.com` has owner Pro. Other accounts require server entitlement from verified GCash payment. Old local demo flags are ignored. APK checkout is prepared but disabled until merchant account, price, access period, secrets and live testing exist. See `docs/gcash-checkout-setup.md`. |
| [ ] Measured 3D MPJPE benchmark | PDF 13 (4), limitations; PDF 22 (13), literature/evaluation target under 50 mm | `tools/pose-pipeline/evaluate_mpjpe.py` and `test_evaluate_mpjpe.py` enforce metric 3D inputs and reject image-space-only predictions. | **Tooling implemented; data/validation pending.** Obtain independent calibrated 3D joint coordinates for all five skills and run/report the benchmark. Ordinary 2D video annotations cannot establish depth or millimeter scale; additional camera angles alone are not 3D ground truth. Do not report the target as a measured result. |

## Existing features confirmed

- Five skills and drill selection, camera checklist, full-body readiness,
  primary-athlete tracking, skeleton overlay, session capture, local video
  review, final scores, saved history and recommendations are present.
- Registration/login, Google login, password-reset email, editable personal
  fields and survey retake are present.
- Coach roster membership, training assignments, explicit session submission,
  shared score review and asynchronous coach comments are present. Human coach
  feedback is asynchronous, as Figure 20 describes.
- Shared Firestore sessions contain scores and optional angle-stage summaries,
  not raw landmarks or video. Cloud video review is a separate product/privacy
  decision, not implied by the current score-sharing implementation.

## Validation still needed

- Physical-phone testing for five skills, orientation recognition, mirroring,
  occlusion/bystanders, TTS availability/audio focus, and haptic feedback.
- Reviewed movement recordings and coach labels to validate form thresholds,
  phase selection, valid-rep coverage and rear-view restrictions.
- Independent 3D reference data to establish MPJPE. Code tests verify behavior
  and compatibility; they cannot validate pose accuracy or coaching quality.
- GCash merchant activation, price/access period, deployed billing functions,
  live payment and a reviewed refund/dispute revocation policy. No purchase is
  enabled by this delivery.
- Device checks for the photo/document pickers, verified-email and password
  flows, notification permission/taps/background delivery and the new layouts.
  Use `docs/device-verification.md` to record these checks without treating
  synthetic debug data as real camera validation.
- Review the prototype Firestore rules before broad distribution. Ownership,
  payment boundaries, access lists and angle summaries are checked; nested
  per-rep score payloads remain client estimates and need further server-side
  payload validation if they are to be treated as trusted records.

## Verification record

- Billing policy: 4 Node tests passed, including signature tampering/replay,
  sandbox rejection, renewal and revoked permanent-access handling; backend
  syntax check passed.
- Firestore emulator: 13 rule tests passed for entitlements/payment ownership,
  self-grant denial, private coach profiles, expired/Free request denial,
  owner/paid connection requests, declined-request retries, scoped queries,
  assignment/submission identities, explicit sharing and guided-angle uploads.
- Coach web production build passed; includes the payment return page.
- Firebase CLI dry run passed; prototype rules and the student membership
  collection-group index were deployed to the existing `volleyball-lab`
  Standard `(default)` database. Billing functions remain undeployed.
- Android full gate passed: 206 unit tests, zero failures, lint with zero
  errors (81 warnings), and `assembleDebug`. The test APK is
  `outputs/VolleyballLab-manuscript-debug.apk`; its SHA-256 and gate record are
  in `outputs/build-verification.json`. The real pose and receiving-model assets
  are packaged. Startup and Login were verified on an Infinix X6833B
  running Android 14: the first launch square is absent, the second splash logo is restored,
  the purple waveform animates, and
  no demo account buttons appear. Android reported a 2,794 ms cold launch.
  Camera, TTS and vibration scenarios still need physical athlete testing.
