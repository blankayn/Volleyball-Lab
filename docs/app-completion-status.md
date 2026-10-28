# App completion and release gates

Updated 24 October 2026. This record separates implementation and automated
verification from deployment, physical-device checks and research evidence.
An unchecked release gate must not be described as 100% complete.

## Completed in this pass

- Preserved the existing welcome, training, calendar, coach-portal and cloud changes.
- Finished account-scoped push processing through WorkManager and the notification
  inbox. Delayed pushes for another signed-in account are rejected; delivery
  preferences, read state and duplicate event identities are respected.
- Added coach approval and ended-connection events, available through live
  Firestore observation, periodic refresh and optional deployed push triggers.
- Added bounded FCM batches, transient-failure retries, deleted-account checks,
  and stale-device cleanup that does not remove a device's newer account mapping.
- Fixed emulator test interference. Suites that clear the shared cross-service
  project now run sequentially; Storage must use the emulator startup project.
- Fixed the offline jump-height baseline selecting crouching height. Its median
  estimate assumes standing occupies most of the segment; continuous jumping or
  crouching still needs an independently measured baseline. Android scoring was
  not changed by this offline-tool fix.
- Added `tools/verify-app.ps1` for repeatable Android, portal, backend and pose-tool
  verification, with optional emulator and release-build checks. Its short Java
  temporary path avoids the Windows Java NIO loopback failure seen locally.

## Reproduce verification

Automated verification for this build passed:

| Check | Result |
|---|---|
| Android debug assembly | Passed; both pose-model assets packaged |
| Android optimized release assembly | Passed; unsigned until release signing is configured |
| Android unit suite | 297 passed, no failures or skipped tests |
| Android debug lint | No errors; 129 warnings and 2 hints remain |
| Coach portal production build | Passed |
| Backend policy and notification unit tests | 11 passed |
| Firestore, Storage and account-cleanup emulator suite | 29 passed |
| Python geometry parity and jump-baseline checks | Passed |
| MPJPE evaluator unit tests | 2 passed; these validate the evaluator, not real-world accuracy |

Install the dependencies documented in the root, portal and pose-pipeline READMEs.
From the repository root in PowerShell:

```powershell
./tools/verify-app.ps1
./tools/verify-app.ps1 -IncludeEmulators -IncludeRelease
```

The optional emulator check requires Firebase CLI on PATH and runs only against
demo projects. The script never deploys services or activates paid features.
Release assembly produces an unsigned artifact until a release signing
configuration is supplied. A successful build does not establish device behavior.

Current machine-readable results and artifact hashes are recorded separately in
`outputs/completion-verification.json`; generated outputs are intentionally ignored
by Git. Earlier verification records describe earlier builds.

## Remaining release gates

| Gate | Concrete requirement |
|---|---|
| Android hardware acceptance | Connect a physical phone and complete `device-verification.md`, including actual athlete capture, voice/haptics, pickers, account changes and background notification taps. No phone was connected during this pass. |
| Firebase services | Confirm current project billing/Storage setup and deploy the account, recording and notification services described in `account-and-recording-services.md` and `coach-cloud-contract.md`. Historical activation records are not proof of current production readiness. |
| Paid checkout | Supply verified merchant credentials, price and access period, then deploy and test payment, renewal and revocation per `gcash-checkout-setup.md`. Checkout remains disabled. |
| Distribution | Supply the release signing configuration and verify the signed release on the target devices and distribution channel. |
| Coaching validation | Obtain coach-reviewed movement labels and review the cue thresholds and illustrations. Automated tests do not validate coaching quality. |
| 3D accuracy benchmark | Obtain independent calibrated metric 3D ground truth for all five skills and run the MPJPE evaluator. No measured accuracy claim has been made. |

The GitHub CLI was absent locally. The public GitHub Issues page showed no open
issues during inspection; no tracker changes were made.
