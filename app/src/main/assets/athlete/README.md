# 3D athlete — Three.js in a WebView

A stylised low-poly volleyball player with five named animations, driven from
Kotlin. Rendered by Three.js inside a `WebView`, hosted by `AthleteScene.kt`.

---

## One thing to add before it runs

Three.js is vendored under `lib/`. **Three** files, not two — `GLTFLoader` has a
relative import of its own:

```bash
mkdir -p app/src/main/assets/athlete/lib/addons/loaders app/src/main/assets/athlete/lib/addons/utils && curl -L -o app/src/main/assets/athlete/lib/three.module.js https://unpkg.com/three@0.160.0/build/three.module.js && curl -L -o app/src/main/assets/athlete/lib/addons/loaders/GLTFLoader.js https://unpkg.com/three@0.160.0/examples/jsm/loaders/GLTFLoader.js && curl -L -o app/src/main/assets/athlete/lib/addons/utils/BufferGeometryUtils.js https://unpkg.com/three@0.160.0/examples/jsm/utils/BufferGeometryUtils.js
```

About 1.4 MB together, all MIT licensed, pinned to r160. They are vendored rather
than pulled from a CDN at runtime for the same reason the pose model is packaged
in the APK: this app is used in gyms, and a mascot that only appears on wifi is
not a mascot.

Miss `BufferGeometryUtils.js` and the failure is quiet and confusing — the page
loads, the character appears, and it is the *procedural* one, because the
`GLTFLoader` import rejected. The scene now logs the underlying reason, so check
the console before assuming the GLB is at fault.

If `three.module.js` itself is absent the page shows a plain message naming the
missing folder rather than a blank white rectangle.

---

## Where the character comes from

`athlete.glb` is here and is what loads. It began as a Tripo generation and was
prepared in Blender:

| Step | Result |
|---|---|
| Removed the base disc | 169k faces of welded-in plinth, cut at z = 0.035 |
| Decimated | 784k → 15k triangles, all 41 vertex groups intact |
| Re-grounded | feet at y = 0 |
| Animated | `wave`, keyframed onto Tripo's 41-bone rig |
| | **29.9 MB → 1.4 MB** |

**The model carries one clip: `wave`.** Six were authored — `idle`, `wave`,
`serve`, `receive`, `set`, `spike` — and only the wave is exported, because the
splash is the only place the character appears today and the rest would be weight
for nothing.

Getting them back is one command, not a redo. The pose tables in
`animate_athlete.py` still define all six; the last argument narrows the export:

```bash
blender --background --factory-startup --python animate_athlete.py -- athlete_cut.glb athlete.glb ./previews "wave,serve,receive,set,spike,idle"
```

Poses are authored by pointing bones in world-space directions rather than by
Euler angle, and the figure is re-grounded per keyframe by measuring its lowest
vertex. See `prepare_athlete.py` and `animate_athlete.py`, kept with the session
notes.

They are demonstrations, hand-keyed by eye. They are **not** motion capture and
nothing in them feeds the scoring: the pose analysis measures the athlete's own
body through the camera. Keep the two apart in the write-up.

### If you replace it

The scene tries `athlete.glb` first and falls back to a figure built in code.

**Today:** no GLB exists, so `athlete.js` builds the athlete out of primitives —
a joint hierarchy with one low-poly mesh per bone, around 1.5k triangles. Side
part, glasses, black shirt with a yellow side stripe, black shorts, white
volleyball shoes. Animations are authored as pose tables at the bottom of that
file and compiled into real `THREE.AnimationClip`s, so `AnimationMixer`,
`AnimationAction` and cross-fading all behave exactly as they would with an
imported rig.

**When a real model arrives:** drop a rigged `athlete.glb` in this folder. It is
picked up automatically and nothing else changes — same API, same clip names,
same Kotlin. Clip matching is case-insensitive and substring-based, so Mixamo's
`mixamo.com|serve` is found by the name `serve` without renaming anything.

Name the clips `idle`, `serve`, `receive`, `set`, `spike`. Keep it under about
15k triangles and a single material.

Scale and placement are handled for you: the model is measured on load, scaled
uniformly to 1.8 m tall, centred on x/z and dropped so its lowest vertex rests at
y = 0. An export in centimetres with its origin at the navel still stands
correctly on the stage.

---

## Bringing a model in from Tripo (or any generator)

Generators produce a **mesh**, and usually not the five volleyball animations.
Expect to do this in two steps.

**1. The mesh.** Export GLB. Tripo's own rigging is fine to enable. Decimate to
roughly 10–15k triangles if it comes back dense — a phone rendering this
alongside the camera pipeline will feel the difference, and the stylised look
does not need the polygons.

**2. The animations.** Three routes, cheapest first:

- **Mixamo.** Upload the mesh, let it auto-rig, then download clips that stand in
  for each skill — "Overhead Throw" reads as a serve, "Kneeling Idle"/"Idle" for
  idle, "Jumping" or a spiking motion for the attack. Combine them into one GLB
  in Blender (each clip becomes an Action; use the NLA editor or Blender's glTF
  exporter with *Group by NLA Track*), naming the actions `idle`, `serve`,
  `receive`, `set`, `spike`.
- **Keyframe them in Blender** against the rig directly. More work, and the only
  route that gives poses actually matching the cues the app scores.
- **Ship the mesh with no clips at all.** It loads and stands there. The scene
  logs exactly which clip names it wanted and which it found, and reports an
  empty animation list to Android through `onReady`, so this fails loudly rather
  than looking like a broken bridge.

A caution worth having in advance: a generated character is a *demonstration*,
not a measurement. The pose analysis scores the athlete's own body from the
camera — nothing on this screen feeds it. Keep the two apart in the write-up.

---

## The JavaScript API

Everything Android calls, and nothing else, lives on `window`:

| Call | Effect |
|---|---|
| `window.playAnimation(name, opts?)` | Cross-fades to a clip. `name` is `idle`, `serve`, `receive`, `set` or `spike`. `opts` may carry `{ speed, immediate }`. |
| `window.resetAnimation()` | Stops everything, re-centres the camera, returns to idle. |
| `window.setPlayerColor(colors)` | Object of `{ shirt, stripe, shorts, skin, hair, shoeBody, shoeAccent, … }`, or a single colour string as a shorthand for the shirt. |
| `window.setCamera(preset \| object)` | Preset: `full`, `closeup`, `action`, `front`, `side`. Object: `{ theta, phi, distance, target }` in degrees and metres. |
| `window.setBackground(value)` | `"transparent"` (default), `"white"`, or any hex. |
| `window.availableAnimations()` | The clip names that actually loaded. |
| `window.setRendering(bool)` | Starts/stops the render loop. |

Names are forgiving: `serving`, `receiving`, `pass`, `dig`, `setting`,
`spiking` and `attack` all resolve. `block` maps to `idle`, because there is no
blocking clip yet and pretending otherwise would be worse than saying so.

### Calling back into Android

If a `@JavascriptInterface` object is injected as `AndroidAthlete`, the page
calls:

- `onReady(json)` — `{ source: "glb" | "procedural", animations: [...] }`
- `onAnimationFinished(name)` — when a one-shot clip ends, before it returns to idle
- `onError(message)` — if the module fails to boot

Every call is guarded, so the same page still runs in a desktop browser with no
bridge present. That is the intended way to iterate on it: open `index.html`
through any local static server and drag to orbit.

---

## Using it from Compose

```kotlin
val athlete = rememberAthleteSceneController()

AthleteScene(
    controller = athlete,
    modifier = Modifier.fillMaxWidth().height(280.dp),
    onReady = { names -> Log.d("Athlete", "loaded $names") },
)

// Anywhere afterwards:
athlete.play(AthleteAnimation.Spike)
athlete.play(Skill.Receiving)          // maps the domain enum to a clip
athlete.setCamera(AthleteCamera.Action)
athlete.setColors(AthleteColors(shirt = Color(0xFF14161C), stripe = Color(0xFFF2C811)))
athlete.reset()
```

Calls made before the scene finishes booting are queued and replayed in order,
so there is no ready-state to wait on at the call site.

---

## Notes for whoever tunes this next

- **Animation timing** lives in `CLIP_DEFINITIONS` at the bottom of `athlete.js`,
  as poses at times in degrees. Editing a swing is editing numbers in one table.
- **Proportions and colours** are the top half of the same file.
- **Performance**: pixel ratio is capped at 2, antialiasing is off on dense
  displays, there are no shadow maps and no post-processing, and the loop stops
  whenever the page is hidden or the host activity pauses. On a mid-range phone
  this leaves headroom for the camera pipeline to keep running alongside it.
- The page is served from `https://appassets.androidplatform.net/athlete/…`
  rather than `file://` — ES modules are refused from file origins, and the
  alternative flag (`allowUniversalAccessFromFileURLs`) relaxes that rule for
  every URL the WebView touches. See the KDoc on `AthleteScene`.
