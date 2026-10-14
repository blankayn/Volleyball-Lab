# Player assignment flow

Players open **Coach → Assignments → View task** to read the coach's message, full instructions, skill, difficulty, sets, repetitions, and due date.

**Start camera task** opens the existing camera and form-analysis workflow with the selected assignment attached. A library assignment uses its selected drill; a custom assignment uses a camera drill for its skill and difficulty. The live camera shows the exact task's target. Capture does not infer completed sets from a continuous recording; the result reports detected repetitions against the requested sets and repetitions.

After recording, the player reviews their form score, cue feedback, and available AI report, then chooses **Send result to coach**. Scores and measured cue results are shared; the video remains private on the device. The coach receives the recording in the existing review queue and can write feedback. That feedback appears on the task page.

The assignment-to-recording link is saved locally with the session, so leaving before submission does not lose the result. **Review saved result** resumes it. Coach assignment data remains in Firestore and scoped to its assigned player.

Submission checks the original assignment, player, skill, and current Active coach membership. A server transaction shares the session, creates the receipt, and marks the assignment completed together. A failed send preserves the local result and leaves completion unchanged. Retrying an already received session keeps an existing coach review intact. Empty sessions and recordings associated with another task cannot be submitted through this flow.

The backend rule change permits signed-in checks for absent session and submission documents before the first upload. Existing results remain restricted to their owner and explicitly shared coaches. No data or billing migration is needed.

## Verification

- Nine focused Android tests passed: saved-session compatibility and assignment-link recovery; failed-send retry; task and feedback isolation; camera drill selection; disconnected membership; and empty or mismatched recording rejection.
- Seventeen Firestore emulator tests passed, including atomic task submission, review-preserving retry, failure after disconnection, first-send absence checks, and denial of unrelated-account result reads.
- Debug APK builds successfully. The final build also contains the account-page updates and sign-out fixes from this conversation.
- Android lint passed. The approved backend rule fix was published to `volleyball-lab` and verified by reading the deployed rules.
- Physical-device verification is pending: no Android device was connected during this run.
