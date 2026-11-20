# Volleyball Lab TensorFlow release evidence (v35)

## Decision

Keep the deployed v29 TensorFlow Lite classifier in the app. The exact app asset passed the new prediction-blind, source-disjoint v35 validation at **92.86% conditional accuracy (13/14)** and **90.0% balanced accuracy** on terminal windows that passed the app's pose-quality gate.

This supports the narrow feature it was trained for: detecting an **insufficient-knee-bend / too-upright forearm-pass ready posture**. It does not validate every volleyball skill or make the app a complete coach.

## Frozen v35 result

| Measure | Result |
|---|---:|
| Locked clips | 20 (10 fault, 10 OK) |
| YouTube source groups | 7 |
| Training/validation source overlap | 0 |
| Clips passing terminal pose/visual quality | 14/20 (70.0%) |
| Decisive predictions after quality gate | 14/14 (100%) |
| Correct decisive predictions | 13/14 (92.86%) |
| Wilson 95% interval | 68.53% to 98.73% |
| Balanced accuracy | 90.0% |
| Fault recall | 9/9 (100%) |
| OK recall | 4/5 (80.0%) |
| End-to-end correct decisive outputs | 13/20 (65.0%) |

The single classification error was a correct Sarah Pavan movement/platform clip that the model called a fault with probability 0.6135, just above the 0.60 fault threshold. Six other clips were correctly withheld before scoring because the terminal pose window failed quality or the one-person tracker switched athletes.

The 20 clips are not 20 fully independent sources: six fault clips share one source and six OK clips share another. Treat 92.86% as encouraging external evidence with a wide confidence interval, not a promise of 93% accuracy for every phone, camera angle, athlete, or gym.

## App parity

- Validated and deployed TensorFlow Lite SHA-256: `ab47e1c2e7c0fbb634dec4d8424524cdf3a7eb56a4e7f77ff006eb765acba251`
- The training artifact and Android asset hashes match exactly.
- App-equivalent policy: 32 valid pose frames, reset after gaps above 500 ms, OK below 0.40, fault at or above 0.60, uncertain between those thresholds.
- The v35 primary result uses the actual final 32-pose candidate window. An earlier passing window never replaces a failing terminal window.

## Volleyball MPJPE

The exact `pose_landmarker_full.task` bundled in the app was evaluated on the official TUM Volleyball test split. On 107 detected images out of 271:

- 2-D MPJPE on 12 directly mapped joints: **17.76 px** (image-cluster bootstrap 95% interval 14.65 to 21.32 px)
- Visibility-gated MPJPE: **15.71 px**
- Detection rate: **39.48%**
- Detection-inclusive PCK@0.2 torso: **23.19%**

MPJPE measures landmark placement on detected still images. It is separate from the ready-posture classifier result, and the low still-image detection coverage explains why the live app must keep its pose-quality gate.

## Remaining work

1. Improve pose/framing coverage. Six of 20 locked clips could not produce a valid terminal decision.
2. Add more independent correct-posture sources and movement examples. The one v35 error was an OK movement clip.
3. Preserve v35 as spent validation evidence. Do not train or choose thresholds on these clips and later present v35 as fresh proof.

## Evidence

- Classifier report: `acquisition-v35-release-validation/v35_external_validation_report.json`
- Terminal predictions: `acquisition-v35-release-validation/terminal_clip_predictions.csv`
- Pose-quality audit: `acquisition-v35-release-validation/pre_score_quality_audit.json`
- Overlay audit: `acquisition-v35-release-validation/v35-overlay-audit-sheet.jpg`
- Frozen manifest: `acquisition-v35-release-validation/sealed_manifest.json`
- Volleyball MPJPE: `mpjpe/results/tum_volleyball_test_app_full_recheck_20260922_mpjpe.json`
