# Mobile replay performance investigation — 3 November 2026

The v2 Blocking candidate has been tested on a physical Infinix X6833B running
Android 14. These are software compatibility/performance measurements on a
repeated public clip, not independent volleyball technique examples or accuracy.

## Completed baseline

The original full replay passed 100 native inferences across 177 submitted
frames, including 14 frames without an accepted primary pose. It used the app's
actual full-model LIVE_STREAM detector with GPU delegation. Full per-frame
p50 was 290.21 ms and p95 was **382.02 ms**, exceeding the 150 ms initial budget.
Detector construction was recorded separately, approximately 6.3–8.4 seconds
per fresh instance. Initialization delay is not included in per-frame latency.

Independent Python recomputation of the phone-emitted landmark window passed:

| Check | Maximum difference |
|---|---:|
| Raw measured features | 0.000015259 |
| Normalized input | 0.000000596 |
| Native model probabilities | 0.0000000596 |

The phone also passed synthetic cases covering both arms, occlusion, missing
detections, irregular dropped frames and low presence. These establish numerical
conventions, not model correctness under real-world occlusion.

Artifacts: `outputs/blocking-v2-phone-pipeline-raw.json`,
`outputs/blocking-v2-phone-pipeline-verified.json`,
`outputs/blocking-v2-phone-parity.log`, and
`outputs/blocking-v2-phone-instrumentation.log` (`OK (2 tests)`).

## Reproducible latency failure and diagnosis

The shorter instrumentation loop requests 12 successful windows and asserts
`pipelineBudgetMs=150`. It failed at **429.54 ms p95** in 17.43 seconds:

```text
adb shell am instrument -w -r -e benchmarkInferences 12 -e pipelineBudgetMs 150
  -e decoder retriever
  -e class com.volleyballlab.player.pose.BlockingArmPipelineDeviceTest
  com.volleyballlab.player.test/androidx.test.runner.AndroidJUnitRunner
```

| Stage | Original short-run p95 | Sequential candidate short-run p95 |
|---|---:|---:|
| Decode | 319.08 ms | 50.91 ms |
| Pose | 97.47 ms | 80.81 ms |
| Features/normalization | 11.33 ms | 6.04 ms |
| Native inference | 0.357 ms | 0.329 ms |
| Complete per-frame pipeline | 429.54 ms | **125.84 ms** |

Percentiles for different stages are not additive. The measurements are separate
runs; device load can change. Only the decoder choice was changed for the
candidate replay. Model, public input and target budget stayed fixed. The short
sequential latency assertion passed, while two initial color/rotation fixture
tests rejected an extended color-standard value. This is not yet a successful
final 100-inference optimized acceptance run.

## Candidate implementation and remaining checks

`SequentialVideoDecoder` reads forward with MediaExtractor/MediaCodec and uses
actual presentation timestamps. Its YUV converter handles crop rectangles,
plane/pixel strides, quarter-turn display rotation, destination-size sampling,
limited/full range and supported SDR matrices. Android documents raw readable
YUV output images for this codec mode. [MediaCodec reference](https://developer.android.com/reference/android/media/MediaCodec)

The rejected value was **85**, an AOSP extended primaries/matrix encoding with
the BT.601 matrix. `SdrColorStandard` now recognizes supported extended SDR
combinations, while unsupported gamuts, vendor values and HDR remain outside
this converter. The temporary reflection probe was removed after identifying
the value. [AOSP ColorUtils conversion](https://android.googlesource.com/platform/frameworks/av/+/master/media/module/foundation/ColorUtils.cpp),
[AOSP color-standard definitions](https://android.googlesource.com/platform/frameworks/av/+/master/media/module/foundation/include/media/stagefright/foundation/ColorUtils.h)

`RecordedFrameReader` selects the sequential path for supported recordings and
retains the platform reader as an initial fallback. It never switches decoders
mid-action after emitting frames. `RecordedVideoAnalyzer` uses this shared
reader; the pose model and coaching thresholds remain unchanged.

## Full-run correction and physical acceptance

The first bound-model full sequential run failed at **195.27 ms p95**. It also
exposed a one-pixel scaled-width mismatch (479 versus 480); destination dimensions
now round rather than truncate. That run constructed seven GPU detectors and
retained input bitmaps across rounds. It is preserved in
`outputs/blocking-bound-sequential-full-raw.json` and its instrumentation log.

The final harness uses one detector per session, matching capture lifetime,
while resetting action feature windows between replays. The actual detector now
closes copied bitmap inputs after submission, and capture also releases inputs
when the detector is unavailable. The physical test asserts input release and
checks actual asynchronous pose outputs. Because detector lifetime and input
ownership also changed, the final speed difference cannot be attributed solely
to the decoder.

On 3 November the final physical run passed **all six device tests**, including
four decoder checks and the 150 ms full-pipeline assertion. It completed 100
inferences over the replay with zero invalid outputs: p50 **108.41 ms**, p95
**132.20 ms**, decode p95 **45.21 ms**, pose p95 **89.06 ms**. The single detector's
construction took **7.13 seconds**, reported separately and still a startup cost.
This measures the public replay path, not live-camera throughput or accuracy.
Artifacts: `outputs/blocking-sustained-phone-instrumentation.log` (`OK (6 tests)`)
and `outputs/blocking-sustained-phone-pipeline-raw.json`.

Desktop recomputation passed for the exact model and pose-asset hashes:
maximum feature difference 0.000015259, normalized-input difference 8.35e-7,
and probability difference 5.96e-8. Verified evidence is in
`outputs/blocking-sustained-phone-pipeline-verified.json`. The subsequent
`outputs/blocking-sustained-deployment-readiness.json` remains false because
locked player-separated evaluation, calibrated acceptance and independent
review evidence are missing; its blocking reasons contain no phone timing gate.

Independent coach labels, separated-player calibration/test data and whole-skill
validation remain release requirements despite passing this timing budget.
