# Blocking model packaging and phone checks

The Android `BlockingArmFeatures` and `LiteRtBlockingArmModel` adapter supports
only `block_visible_arm_reach_hold`, not a whole-skill Blocking assessment. No
experimental classifier is enabled in capture or shipped as main app weights.
The adapter returns unavailable without an approved manifest; accepted labels
also require a complete observed arm cycle and calibrated confidence.

Run the packaging audit against exact model/report files:

```powershell
tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/deployment_readiness.py `
  --model-dir outputs/ai-coach-block-arm-time-v2/block/tcn-0.001 `
  --report outputs/ai-coach-block-arm-time-v2/report.json `
  --out outputs/blocking-v2-deployment-readiness.json
```

This development candidate is expected to fail, returning exit code 2. Selecting
its directory does not select a production winner. Add `--review` and `--device`
only for authentic JSON evidence bound to the exact weights, dataset and report.
`--package-out` writes a hash-addressed immutable asset bundle only after gates
pass. It does not activate or copy the bundle into the app automatically.

Required evidence includes player/source-separated locked evaluation, reviewed
labels, both classes, sufficient held-out per-class precision/recall, calibrated
selective confidence, matching feature/normalization/pose configuration, native
export fidelity and complete physical-phone processing/parity results. A review
record must identify qualified reviewers. Schema assertions cannot authenticate
reviewers or establish that a person actually reviewed a video.

`prepare_coach_review.py` can prepare the public development examples as a blank
review packet. These examples have already been exposed during development and
cannot become an untouched test set by adding reviewer names. Student recordings
likewise require actual review; automated app scores are not ground-truth labels.
Keep personal recordings and review artifacts in ignored local outputs.

`BlockingArmRuntimeDeviceTest` uses test-only assets and checks classifier
compatibility on a physical device. Its record explicitly excludes decoding,
pose estimation and feature extraction. It therefore cannot satisfy the full
pipeline gate. It is also bound to its specific candidate hash, not later fits.
The earlier expanded TCN passed 100 inferences on an Infinix X6833B with zero
invalid outputs, p95 0.14 ms and maximum probability difference 5.96e-8.

The adapter now requires `blocking_arm_time_v2`. Its timestamp-grid sampling and
50 ms nearest-pose limit replace v1's index-based sampling. Presence, missing
emissions, visibility, arm choice, ties and masks have shared Python/Android
fixtures. V1 weights remain historical research artifacts and are rejected by
the current adapter and packaging audit.

## Complete phone replay workflow

Generate synthetic fixtures, then stage the newly fitted research candidate and
one reviewed **public** clip using `generate_blocking_parity.py` and
`prepare_blocking_device_test.py`. Never stage private student media in app
assets. The latter script requires `--model-dir`, `--data` and `--public-clip` and
writes only instrumentation assets. The TCN is an arbitrary runtime test candidate,
not a production winner.

Build `:app:assembleDebug` and `:app:assembleDebugAndroidTest` with
`--offline --no-configuration-cache`. Install both APKs with `adb install -r`;
do not uninstall/reset the student's app. Run the SDK instrumentation runner
with classes `com.volleyballlab.player.pose.BlockingArmRuntimeDeviceTest` and
`com.volleyballlab.player.pose.BlockingArmPipelineDeviceTest`. The direct SDK
runner avoids the unavailable offline UTP dependency. Check `OK (2 tests)`, not
just the shell exit status.

The full test decodes a repeatedly replayed public video through the **actual
LIVE_STREAM app detector**, production feature builder and native candidate. It
measures every submitted frame, including empty detections and tracker
confirmation; detector construction times are recorded separately. It requires
100 successful sliding-window inferences, not 100 independent volleyball actions.
No model predictions become technique labels. No camera, account or student video
is read by this test. Input is scaled within 480×640 while preserving aspect ratio.

The test writes `files/blocking-arm-pipeline.json` under the debug app. Copy that
specific file locally using `adb shell run-as com.volleyballlab.player cat ...`.
Run `verify_blocking_device.py --device <copied-file> --model-dir <same-candidate>
--out <verified-record>`. It recomputes the phone-emitted landmark window in
Python and compares fixed arm, time-sampled masks, measured features,
normalization and native probabilities. Only an authentic passing run can set
preprocessing parity verified. It cannot grant deployment approval or establish
accuracy. Pass the verified record to the packaging audit with `--device`.

Before activation, independent expert/player validation and held-out confidence
calibration are still required. Shared fixtures establish feature conventions;
the replay test establishes compatibility/performance on its measured inputs,
not generalization to every camera, lighting condition or athlete.

The app's partial-repetition fix rejects signal peaks at a window boundary.
It does not establish action identity, correct all segmentation failures or
retroactively alter saved sessions. Whole-block coaching still requires its own
data, scope and independently tested model.
