# Spiking source review follow-up

Reviewed 30 October 2026. These are provisional AI reviews of creator
demonstrations, not independent coaching truth or measured model accuracy.

The existing extracted fault `pGW7klAElBY_38500_46500` must be rejected. Its
second segment's good-looking visibility score comes largely from tracking the
stationary trainer on a chair after the active athlete crosses him. Landmark
overlays at source times44.17–46.17s show the trainer selected. The local review
image is `outputs/online-form-data/review/spike-negative-actor-followup.jpg`.
[Original creator tutorial](https://www.youtube.com/watch?v=pGW7klAElBY).

Two clearer positive candidates are Coach Artie's complete self-toss jump spikes
at115.0–117.5s and119.0–121.3s. Each continuously shows a single athlete's
approach/toss, jump, contact and landing. The creator explains these as corrected
rotation and crosscourt/line demonstrations. His preceding and following dry
footwork explanations should not be labelled as complete faulty spikes.
[Creator source](https://www.youtube.com/watch?v=e6Ia30HOjsE).

The Olympic Games Jordan Larson tutorial has one continuous full-body positive
at141.5–144.15s. A foreground setter may distract the tracker, so actor identity
needs checking. Most surrounding demonstrations cut between upper-body,
feet-only and wider views and cannot supply a complete pose sequence.
[Creator source](https://www.youtube.com/watch?v=jx7z8bkIj4Y).

Wicked Volleyball's fifth-mistake section supplies two further faulty candidates
at190.0–193.9s and196.0–198.9s, explicitly marked with red crosses and explained
as a non-dominant-arm fault. These are complete spikes in a central portrait
pane with empty black pillarboxes. Removing that empty canvas is a possible
preprocessing step; retain the full athlete, record the crop, rerun pose
extraction, and keep both clips in the same source group. They may be alternate
views of the same execution and are not independent validation examples.
[Creator source](https://www.youtube.com/watch?v=pGW7klAElBY).

The additional Elevate Yourself timing tutorial is a useful coached drill, but
its examples catch the ball at the jump peak. The narration explicitly says the
arm swing is added afterward, so those catches should not be labelled as
complete correct spikes.
[Creator source](https://www.youtube.com/watch?v=7qRzBXGvezQ).

Exact selections, evidence, limitations and proposed crop coordinates are saved
in `outputs/online-form-data/review/spike-followup.json`.

Actual extraction checks subsequently rejected the196.0–198.9s portrait example:
only0.1136 of its frames passed required13-joint visibility0.5 plus image bounds.
The190.0–193.9s window scored0.4915, with no reliable frames in its post-landing
idle tail. The actual rep lands near193.0, so190.0–193.15 is the tighter complete
movement candidate; it must still be re-extracted and checked.

A second fault candidate is119.8–123.0s in the same Wicked tutorial. Landmark
overlays confirm that its selected person is the moving foreground spiker. The
first repetition lands near122.8, and the next starts after123.4. Its corresponding
verified extraction subset has required13-joint quality0.5417. The creator
freezes/slows the contact pose around120.8–122.1, however, which is a temporal
confound that must remain recorded with the provisional label.

The existing Olympic141.5–143.9 extraction was inspected and rejected: its
landmarks consistently follow the foreground setter while the spiker moves on
the left. It needs an explicit actor crop and verified re-extraction to be
salvaged. In contrast, segment1 of the115.8–117.35 extraction does consistently
follow the moving spiker and reports usable fraction0.81. Its first two frames
are a feet-only shot that must be excluded, and the later edit limits landing
coverage. It is a phase-limited technique demonstration, not a continuously
observed full-rep validation example.
