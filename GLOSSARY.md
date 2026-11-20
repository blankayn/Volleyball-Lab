# Volleyball Lab

Shared language for the player app and coach workspace.

## Language

**Coach presence**:
A coach’s automatically determined activity status: Online, Busy, or Offline. Busy means they are actively reviewing training or preparing coaching guidance.
_Avoid_: Manually selected availability, booking capacity

**Coach response time**:
The typical time a coach has taken to answer previous coaching questions. It describes past responses and is separate from their current presence.
_Avoid_: Guaranteed response time

**Pose Guide**:
The gold reference drawn over the camera: the reference figure at the moments the athlete is being told where to be, and the limb involved in a visible form correction at the current movement phase. One switch, on unless turned off.
_Avoid_: Motion trail, ideal athlete

**Reference figure**:
The athlete's own body drawn in a skill's start or finish position, standing on their feet and turned the way they should face. Its angles are read from the cue targets, so it shows where the app's measurements want the body to be. It is never drawn while a movement is under way.
_Avoid_: ideal form, target athlete, ghost

**Detailed AI Coaching**:
An optional explanation of one repeatedly measured form cue during a break between camera angles, with a practical tip for the next attempt.
_Avoid_: AI scoring, video analysis by the language model


**Coach Director**:
Local decisions about when to correct one measured cue, allow a retry, or
acknowledge a measured improvement after a completed repetition.

**Debrief**:
The spoken turn at a camera-angle change: the final rep's count, one line of
counted results for the finished view, one measured takeaway, then the turn
instruction.

**Closing**:
The last spoken turn of an ordinary finish, restating counted reps, any cue a
view recorded as resolved, and the one open cue to carry forward.
_Avoid_: session summary, score

**Regroup**:
A single prompt to reset when measured form in a view has slipped from its own
earlier level. Not a correction, and not a comparison with another session.

**Carry focus**:
The name of the cue still open when a view ends. Only the name crosses the view
change; the new view must measure the fault again before it is corrected.

**Coach Reel**:
Selected correction clips from the original session video, with its retained
skeleton and educational coaching notes.

**Required view**:
Front or Side capture needed for a guided assessment. Rear remains optional.
Early saved recordings retain an incomplete assessment label.

**Posture comparison**:
Front and Side stills from separate recorded repetitions at a measured cue phase.
Missing views and measurements are labelled rather than inferred.

**Movement check**:
The test a finished movement must pass to be a repetition of the selected skill:
large enough to be the action, performed with the limbs that skill uses, and
finishing where that skill finishes. The live count and the saved session apply
the same check.
_Avoid_: rep detection, form score

**Not counted**:
A finished movement that failed the movement check. It adds nothing to reps, good
reps, streak or score, and the coach names the one change that would make it count.
_Avoid_: failed rep, bad rep

**Facing**:
Which way the athlete points their body for a view: forward, left, right or away.
Left and right are always the athlete's own, starting from facing the camera.
_Avoid_: screen left, screen right
