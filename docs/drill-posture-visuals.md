# Drill posture samples

## App integration

All 15 drill detail pages and their manual practice screens now use full-body athlete samples. Each drill has four posture frames, Play/Pause, step selection and a short posture cue. Serving and spiking include right- and left-handed examples. Playback stops when the visible screen backgrounds; it does not affect workout timers, reps, camera analysis, or scores.

The skill-learning dialogs use the corresponding starter-drill visual. The existing Free/Pro gates still control full drill guides and practice; Free users retain exactly the five starter drills.

These are illustrated posture snapshots with a timed preview, not recordings of an athlete performing a continuous repetition. They do not demonstrate ball-target accuracy or validate camera measurements.

## Artwork and prompts

Created with the built-in `image_gen` tool. All assets are saved in the project:

- `app/src/main/res/drawable-nodpi/drill_visual_serving.png`
- `app/src/main/res/drawable-nodpi/drill_visual_receiving.png`
- `app/src/main/res/drawable-nodpi/drill_visual_setting.png`
- `app/src/main/res/drawable-nodpi/drill_visual_spiking.png`
- `app/src/main/res/drawable-nodpi/drill_visual_blocking.png`

Full generation prompts and the targeted back-set correction are in [drill-visual-prompts.json](drill-visual-prompts.json). The style is a faceless ivory athlete, burnt-orange jersey, navy shorts, pale blue-white background and soft studio lighting.

Each sheet has three drill rows and four frame columns. Reviewed row boundaries preserve full-body framing; Android selects the appropriate region at display time. Original generated files are copied unchanged into the app. No bitmap transformations were used to generate the athlete poses outside `image_gen`.

## Verification

- All 60 cropped frames inspected in the local browser preview: the selected frames show the full athlete and intended drill row.
- Back-set release corrected to show both hands extending together.
- Three new mapping/framing tests passed for all 15 drills, unknown IDs, atlas bounds and starter mappings.
- Seven existing drill policy/state tests passed, including the five-drill Free allowlist and manually confirmed reps.
- Android APK build passed. Lint passed with zero errors and 99 warnings. Artifact and verification results are recorded in `outputs/build-verification.json`.
- Physical-phone checks of playback, left/right mirroring, scrolling and backgrounding are pending; ADB detected no connected phone.

The browser preview in `outputs/drill-visual-preview/index.html` checks artwork selection and framing. It is not evidence of Android layout or device performance.
