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
An optional visual reference for the limb involved in a visible form correction at the current movement phase.
_Avoid_: Motion trail, ideal athlete

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
