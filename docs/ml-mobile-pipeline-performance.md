# Mobile replay performance investigation — 31 October 2026

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

Before calling this optimization verified, rerun all rotation/color/scale and
timestamp device tests, the 100-inference replay with the 150 ms assertion,
Python recomputation and the exact-model deployment audit. The wireless phone
disconnected during the color-fix update; the earlier installed version remains
intact. The integrated final build is under verification locally. Independent
coach labels, separated-player calibration/test data and whole-skill validation
remain separate release requirements even if the timing budget passes.
