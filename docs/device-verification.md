# Physical-phone verification

Status: startup and Login checked on 2026-09-28 on an Infinix X6833B,
Android 14. The new APK was installed without clearing app data. Cold launch
reported 2,794 ms; the first launch square is absent, the second splash logo is restored, the purple waveform
animates, and Login has no demo account buttons. Recording:
`outputs/splash-device-startup.mp4`.

Camera, spoken coaching and vibration checks below remain pending. Startup
verification does not establish their behavior or tracking accuracy.

Install the latest APK from `outputs/VolleyballLab-manuscript-debug.apk` on an
Android 8.0+ phone. Use the real pose detector and a single athlete in a well-lit
space with their full body visible. Debug synthetic poses are unsuitable for
these checks. Record device model, Android version, app build, pass/fail and
observed behavior for every row.

| Area | Check | Expected behavior |
|---|---|---|
| Startup and loading | Cold start and revisit Home/Progress/Profile/Coach | Logo/title appear promptly; data loading uses skeleton placeholders; no fixed five-second blank wait. Measure startup on the phone. |
| Five skills | Serving, receiving, setting, spiking and blocking, front/side/rear | Only supported, visible cues are assessed. Rear receiving-model and face-dependent verdicts remain unassessed. Do not infer full posture correctness from level shoulders. |
| Guided stages | Separate sessions with 5 and 10 reps per angle | Reliably completed reps advance front → side → rear; bad-form reps count toward the stage while good reps are tracked separately. |
| Turning | Rotate, walk, adjust camera and complete the stable-framing countdown | No reps/posture scores during the turn or countdown; previous correction disappears; rep segmentation restarts at the next stage. |
| Uncertain angle | Hold an ambiguous angle for ten seconds | Adjustment instruction, then Retry/Skip; no automatic acceptance. |
| Partial sessions | Retry, skip each angle and finish before completing all stages | Saved summaries match completed/scored intervals; skipped/turning frames do not contribute; final reps never cross stage boundaries. |
| Tracking | Occlude limbs, leave frame, add a bystander, return | No stale correction after 500 ms; incomplete rep abandoned; one second of reliable tracking before resuming; bystander does not silently replace the athlete. |
| Mirroring | Observe anatomical left/right limb cues on front camera | Highlight/speech refer to the athlete's actual side through mirroring. |
| Voice/haptics | Turn preferences on/off, mute, background, exit, interrupt audio focus | No overlapping or stale speech; 4-second cue spacing and 12-second same-correction spacing; rotation/tracking take priority; speech stops on exit/background/audio-focus loss; valid-rep vibration respects preference. |
| Missing TTS voice | Disable or remove a usable English TTS voice | Visual coaching still works and missing speech does not block capture. |
| Learning/UI | Open all guides; use a small screen and large font | Text, phase illustrations, mistakes and controls remain readable and scrollable. |
| Account tools | Pick/rotate/remove photo; export JSON/CSV; edit goals; switch accounts | Correct private photo orientation; readable export without private video paths; goals affect recommendations; another account's local data does not appear. |
| Account security | Send verification, refresh status, change password, test wrong current password | Correct status/errors; reauthentication required; provider-managed accounts show appropriate guidance. Do not record passwords in the test log. |
| Notifications | Allow/deny Android permission, create events, mark read, tap alert, background app | Inbox remains usable without system alerts; correct account/read state; tap opens inbox; document actual background delay. |
| Plans and reports | Create/edit/delete/complete schedule; open assignments; generate and reopen reports | Today/upcoming/due rows are consistent; Monday reset; 5 Free reports; saved report reuse is free; failed generation uses no credit. |
| Access | Sign in as Kirt and another account | Kirt has owner Pro; other account cannot toggle itself to Pro. GCash stays unavailable until merchant setup is complete. |

GCash merchant/live-payment testing is a separate pending gate described in
`gcash-checkout-setup.md`. MPJPE requires independent calibrated 3D metric
ground truth; this device checklist cannot establish a millimeter benchmark.
