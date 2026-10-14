# Capture save recovery

Baseline: `checkpoint/pre-architecture-20261014` (`0d922c6`).

## Problem

Stopping capture enters `Analysing` before scoring, video finalization, and local
session storage. An exception from local storage currently escapes the coroutine;
there is no recoverable screen state and the result is not reported as finished.

## Required behavior

- A failed completion leaves the busy state and shows a retry action.
- A failed save never navigates to a result that was not saved.
- Retrying a storage failure saves the same scored session, video association,
  assignment, and angle summaries without another recording or scoring pass.
- Repeated retry taps do not start overlapping saves or create duplicate sessions.
- Captured input is frozen when stopping; completion owns scoring, video
  finalization, prepared-result retention, and storage ordering in one module.
- Coroutine cancellation remains cancellation and is not shown as a storage error.
- Existing successful capture, optional video, and late-finalization behavior remain.

## Agreed verification surface

Use the real `CaptureViewModel` stop/retry commands and its observable `uiState`,
with controlled detector, review-writer, and session-storage adapters. Existing
guided-capture tests remain part of the regression checks. Physical CameraX
recording is outside this test scope, as confirmed by the user.

Retention is in memory while the capture screen's ViewModel remains alive;
process-death recovery and background uploads are outside this change.
