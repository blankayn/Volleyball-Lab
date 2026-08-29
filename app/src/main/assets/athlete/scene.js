import * as THREE from 'three';
import { buildAthlete, buildClips, DEFAULT_COLORS, CLIP_DEFINITIONS } from './athlete.js';

/**
 * The Volleyball Lab athlete scene, and the API Android drives it through.
 *
 * ## Two sources for the character, one API
 *
 * If `athlete.glb` sits next to this file it is loaded and used, and its own
 * animation clips are matched to the five names below. If it is absent - which
 * is the case until someone rigs one - the procedural athlete in `athlete.js`
 * is built instead. Nothing above this file can tell the difference: the same
 * `window.playAnimation('spike')` works either way, which is what stops the
 * Android side from having to be rewritten when the real model lands.
 *
 * ## Mobile budget
 *
 * This runs in a WebView on a phone that may also be holding a MediaPipe GPU
 * delegate open. The rules that follow from that, all applied below: pixel ratio
 * capped at 2, antialias only when the display is not already dense enough to
 * hide the difference, no shadow maps, no post-processing, no environment map,
 * and the render loop stops dead whenever the page is hidden.
 */

const CANVAS_ID = 'athlete-canvas';

/** The five names Android may pass, and the aliases worth tolerating. */
const ANIMATION_ALIASES = {
  idle: 'idle',
  serve: 'serve',
  serving: 'serve',
  receive: 'receive',
  receiving: 'receive',
  pass: 'receive',
  dig: 'receive',
  set: 'set',
  setting: 'set',
  spike: 'spike',
  spiking: 'spike',
  attack: 'spike',
  wave: 'wave',
  hello: 'wave',
  greet: 'wave',
  // Blocking has no clip of its own yet. Falling back to idle is honest; a
  // silent no-op would look like the bridge was broken.
  block: 'idle',
  blocking: 'idle',
};

/**
 * Clips that repeat rather than play once and hold.
 *
 * A wave loops because a greeting that plays once and freezes with the hand up
 * is worse than no greeting; the skill demonstrations do not, because they end
 * in a landing and then hand back to idle.
 */
const LOOPING_CLIPS = new Set(['idle', 'wave']);

/** Camera presets, so Android can say "closeup" instead of doing spherical maths. */
const CAMERA_PRESETS = {
  full: { theta: 22, phi: 78, distance: 4.6, target: 1.05 },
  closeup: { theta: 18, phi: 80, distance: 2.9, target: 1.35 },
  action: { theta: 42, phi: 72, distance: 4.0, target: 1.30 },
  front: { theta: 0, phi: 82, distance: 4.4, target: 1.05 },
  side: { theta: 90, phi: 82, distance: 4.4, target: 1.05 },
};

const CROSS_FADE_SECONDS = 0.28;

const state = {
  renderer: null,
  scene: null,
  camera: null,
  mixer: null,
  clock: new THREE.Clock(),
  athlete: null,
  ballNode: null,
  materials: null,
  actions: {},
  current: null,
  currentName: null,
  running: false,
  usingGltf: false,
  orbit: { theta: 22, phi: 78, distance: 4.6, target: 1.05 },
};

// ── Boot ───────────────────────────────────────────────────────────────────

init();

function init() {
  const canvas = document.getElementById(CANVAS_ID);
  const dpr = Math.min(window.devicePixelRatio || 1, 2);

  state.renderer = new THREE.WebGLRenderer({
    canvas,
    alpha: true,
    // Above 1.5x density the sub-pixel edges MSAA would smooth are already
    // smaller than anything the eye resolves, so the fill-rate cost buys nothing.
    antialias: dpr < 1.5,
    powerPreference: 'high-performance',
  });
  state.renderer.setPixelRatio(dpr);
  state.renderer.setClearColor(0x000000, 0);
  state.renderer.setSize(window.innerWidth, window.innerHeight, false);

  state.scene = new THREE.Scene();

  state.camera = new THREE.PerspectiveCamera(
    34,
    window.innerWidth / window.innerHeight,
    0.1,
    50,
  );

  // Three lights, no shadows. A hemisphere for ambient bounce, a key from the
  // front-right, and a dim rim from behind so the figure separates from a white
  // background without needing a shadow map to sit on.
  state.scene.add(new THREE.HemisphereLight(0xffffff, 0x8a94a6, 1.45));
  const key = new THREE.DirectionalLight(0xffffff, 1.5);
  key.position.set(2.5, 4.0, 3.0);
  state.scene.add(key);
  const rim = new THREE.DirectionalLight(0xdfe8f6, 0.6);
  rim.position.set(-2.5, 2.0, -2.5);
  state.scene.add(rim);

  loadCharacter();
  attachTouch(canvas);
  applyOrbit();

  window.addEventListener('resize', onResize);
  document.addEventListener('visibilitychange', () => {
    // A WebView that scrolls off screen keeps its page alive. Without this the
    // render loop keeps burning GPU behind whatever the athlete is looking at.
    if (document.hidden) stop();
    else start();
  });

  exposeApi();
  start();
}

/**
 * Loads `athlete.glb` if it is there, and falls back to the procedural figure.
 *
 * The fallback is not an error path - it is the expected path today - so it is
 * silent apart from a console note. The bridge reports which one ended up in
 * the scene so Android can log it if it cares.
 */
function loadCharacter() {
  import('three/addons/loaders/GLTFLoader.js')
    .then(({ GLTFLoader }) => {
      new GLTFLoader().load(
        './athlete.glb',
        (gltf) => useGltf(gltf),
        undefined,
        (error) => useProcedural(`athlete.glb failed to load: ${error?.message || error}`),
      );
    })
    // The reason is carried through rather than swallowed. GLTFLoader has its
    // own relative import of BufferGeometryUtils, so "the file is right there
    // and it still fell back" is a real and otherwise baffling failure: what is
    // missing is a dependency of the file you did copy, one directory over.
    .catch((error) => useProcedural(`GLTFLoader unavailable: ${error?.message || error}`));
}

function useGltf(gltf) {
  state.usingGltf = true;
  state.athlete = gltf.scene;
  state.athlete.traverse((node) => {
    if (!node.isMesh) return;
    node.frustumCulled = true;
    node.castShadow = false;
    node.receiveShadow = false;
  });

  fitToStage(state.athlete);
  state.scene.add(state.athlete);

  state.mixer = new THREE.AnimationMixer(state.athlete);

  /** Loops only what is meant to loop; everything else plays once and holds. */
  const makeAction = (clip, name) => {
    const action = state.mixer.clipAction(clip);
    if (!LOOPING_CLIPS.has(name)) {
      action.setLoop(THREE.LoopOnce, 1);
      action.clampWhenFinished = true;
    }
    return action;
  };

  // Every clip in the file is registered under its own name first. Looking only
  // for the five the procedural figure happens to define would silently drop
  // anything extra the model was authored with - a wave, a celebration, a
  // block - and leave no trace that it had been ignored.
  for (const clip of gltf.animations) {
    // "mixamo.com|Serve" and "Armature|serve" both reduce to "serve".
    const key = clip.name.toLowerCase().split('|').pop().trim();
    if (!key || state.actions[key]) continue;
    state.actions[key] = makeAction(clip, key);
  }

  // Then make sure the canonical names resolve even on a rig that spells them
  // differently, by matching on a contained keyword.
  for (const name of Object.keys(CLIP_DEFINITIONS)) {
    if (state.actions[name]) continue;
    const clip =
      THREE.AnimationClip.findByName(gltf.animations, name) ||
      gltf.animations.find((candidate) =>
        candidate.name.toLowerCase().includes(name),
      );
    if (clip) state.actions[name] = makeAction(clip, name);
  }

  state.mixer.addEventListener('finished', () => {
    const finished = state.currentName;
    if (finished && finished !== 'idle') {
      notifyAndroid('onAnimationFinished', finished);
      if (state.actions.idle) play('idle');
    }
  });

  // A model that loads but animates nothing is the most likely outcome of
  // dropping in a generated mesh, and the least obvious to diagnose - the
  // character appears, looks right, and simply never moves. So say exactly
  // what was in the file and what the names needed to be.
  if (Object.keys(state.actions).length === 0) {
    const found = gltf.animations.map((clip) => clip.name);
    console.warn(
      '[athlete] athlete.glb loaded but none of its clips matched. ' +
        `Wanted: ${Object.keys(CLIP_DEFINITIONS).join(', ')}. ` +
        `Found: ${found.length ? found.join(', ') : '(no animations in the file)'}.`,
    );
  }

  finishSetup(Object.keys(state.actions));
}

/**
 * Scales and re-seats an imported model so it stands where the camera expects.
 *
 * Generated and marketplace models arrive at whatever scale and origin the tool
 * felt like - centimetres, inches, origin at the navel, sunk half through the
 * floor. Rather than making that someone's export problem every time, the model
 * is measured and fitted: scaled to a real athlete's height, centred on x and z,
 * and dropped so the lowest vertex sits at y=0.
 *
 * Only uniform scale and translation, so nothing is distorted, and it is applied
 * to the wrapper's transform rather than baked into geometry so the animation
 * data underneath is untouched.
 */
function fitToStage(model, targetHeight = 1.8) {
  const box = measure(model);
  if (!box) return;

  const height = box.getSize(new THREE.Vector3()).y;
  if (height > 0.001) {
    const scale = targetHeight / height;
    // Left alone when it is already about right, so a correctly authored
    // export is not nudged by rounding.
    if (scale < 0.95 || scale > 1.05) model.scale.setScalar(scale);
  }

  const fitted = measure(model);
  const centre = fitted.getCenter(new THREE.Vector3());
  model.position.x -= centre.x;
  model.position.z -= centre.z;
  model.position.y -= fitted.min.y;
}

/**
 * World-space bounds of a model, accounting for skinning.
 *
 * `Box3.setFromObject` is the obvious call here and it is wrong for rigged
 * characters: it measures the geometry as authored in bind space, which for a
 * glTF export is whatever local space the exporter happened to use. On this
 * model that reads as 0.28 units tall against a real height of 0.97, so fitting
 * to it scaled the character six times too large and the camera ended up inside
 * its shoes.
 *
 * `SkinnedMesh.computeBoundingBox` walks the vertices through their bone
 * transforms and reports what is actually on screen.
 */
function measure(model) {
  model.updateWorldMatrix(true, true);

  const bounds = new THREE.Box3();
  const single = new THREE.Box3();
  let found = false;

  model.traverse((node) => {
    if (!node.isMesh) return;
    if (node.isSkinnedMesh && typeof node.computeBoundingBox === 'function') {
      node.computeBoundingBox();
      single.copy(node.boundingBox);
    } else {
      if (!node.geometry.boundingBox) node.geometry.computeBoundingBox();
      single.copy(node.geometry.boundingBox);
    }
    single.applyMatrix4(node.matrixWorld);
    bounds.union(single);
    found = true;
  });

  return found && !bounds.isEmpty() ? bounds : null;
}

function useProcedural(reason) {
  if (state.athlete) return;
  console.info(`[athlete] using the procedural figure (${reason})`);
  state.usingGltf = false;

  const built = buildAthlete(DEFAULT_COLORS);
  state.athlete = built.root;
  state.materials = built.materials;
  state.ballNode = built.joints.ball;
  state.scene.add(state.athlete);

  state.mixer = new THREE.AnimationMixer(state.athlete);
  const clips = buildClips();
  for (const [name, entry] of Object.entries(clips)) {
    const action = state.mixer.clipAction(entry.clip);
    action.showsBall = entry.showsBall;
    if (entry.loop === 'once') {
      action.setLoop(THREE.LoopOnce, 1);
      action.clampWhenFinished = true;
    }
    state.actions[name] = action;
  }

  // A one-shot clip returning to idle is what makes the figure feel alive
  // rather than frozen mid-swing on the last frame it was asked for.
  state.mixer.addEventListener('finished', (event) => {
    const finished = state.currentName;
    if (finished && finished !== 'idle') {
      notifyAndroid('onAnimationFinished', finished);
      play('idle');
    }
    void event;
  });

  finishSetup(Object.keys(state.actions));
}

function finishSetup(available) {
  // A model with no idle clip still has to be rendered - it just stands there.
  if (state.actions.idle) play('idle', { immediate: true });
  else start();
  notifyAndroid('onReady', JSON.stringify({
    source: state.usingGltf ? 'glb' : 'procedural',
    animations: available,
  }));
  document.body.classList.add('ready');
}

// ── Playback ───────────────────────────────────────────────────────────────

/**
 * Cross-fades to [name].
 *
 * Cross-fade rather than a hard cut, because these clips do not all start from
 * the same pose. Cutting from a landed spike straight into idle snaps the whole
 * skeleton in one frame, which reads as a glitch rather than as a transition.
 */
function play(name, options = {}) {
  // A clip present in the model is playable by its own name even if nothing in
  // the alias table has heard of it, so a model authored with extra animations
  // is usable without editing this file.
  const requested = String(name || '').toLowerCase();
  const resolved = state.actions[requested] ? requested : ANIMATION_ALIASES[requested];
  const next = resolved ? state.actions[resolved] : null;
  if (!next) {
    console.warn(
      `[athlete] no clip for "${name}". Available: ${Object.keys(state.actions).join(', ') || '(none)'}`,
    );
    return false;
  }

  if (state.ballNode) state.ballNode.visible = Boolean(next.showsBall);

  next.reset();
  next.setEffectiveTimeScale(options.speed || 1);
  next.setEffectiveWeight(1);
  next.enabled = true;

  if (state.current && state.current !== next && !options.immediate) {
    next.crossFadeFrom(state.current, CROSS_FADE_SECONDS, false);
  } else if (state.current && state.current !== next) {
    state.current.stop();
  }

  next.play();
  state.current = next;
  state.currentName = resolved;
  start();
  return true;
}

function start() {
  if (state.running) return;
  state.running = true;
  state.clock.getDelta();
  state.renderer.setAnimationLoop(tick);
}

function stop() {
  if (!state.running) return;
  state.running = false;
  state.renderer.setAnimationLoop(null);
}

function tick() {
  const delta = state.clock.getDelta();
  if (state.mixer) state.mixer.update(delta);
  state.renderer.render(state.scene, state.camera);
}

// ── Camera ─────────────────────────────────────────────────────────────────

function applyOrbit() {
  const { theta, phi, distance, target } = state.orbit;
  const t = THREE.MathUtils.degToRad(theta);
  const p = THREE.MathUtils.degToRad(THREE.MathUtils.clamp(phi, 12, 165));
  state.camera.position.set(
    distance * Math.sin(p) * Math.sin(t),
    target + distance * Math.cos(p),
    distance * Math.sin(p) * Math.cos(t),
  );
  state.camera.lookAt(0, target, 0);
}

function onResize() {
  const width = window.innerWidth;
  const height = window.innerHeight;
  state.camera.aspect = width / height;
  state.camera.updateProjectionMatrix();
  state.renderer.setSize(width, height, false);
}

/**
 * Drag to orbit, pinch to zoom.
 *
 * Hand-written rather than pulling in OrbitControls: this needs two gestures out
 * of the dozen that module ships, and one fewer file to vendor is one fewer way
 * for the WebView to end up with a blank screen.
 */
function attachTouch(canvas) {
  let lastX = 0;
  let lastY = 0;
  let lastPinch = 0;
  let dragging = false;

  const pinchDistance = (touches) =>
    Math.hypot(
      touches[0].clientX - touches[1].clientX,
      touches[0].clientY - touches[1].clientY,
    );

  canvas.addEventListener('touchstart', (event) => {
    if (event.touches.length === 1) {
      dragging = true;
      lastX = event.touches[0].clientX;
      lastY = event.touches[0].clientY;
    } else if (event.touches.length === 2) {
      dragging = false;
      lastPinch = pinchDistance(event.touches);
    }
  }, { passive: true });

  canvas.addEventListener('touchmove', (event) => {
    if (dragging && event.touches.length === 1) {
      const touch = event.touches[0];
      state.orbit.theta -= (touch.clientX - lastX) * 0.35;
      state.orbit.phi -= (touch.clientY - lastY) * 0.3;
      lastX = touch.clientX;
      lastY = touch.clientY;
      applyOrbit();
    } else if (event.touches.length === 2) {
      const pinch = pinchDistance(event.touches);
      if (lastPinch > 0) {
        state.orbit.distance = THREE.MathUtils.clamp(
          state.orbit.distance * (lastPinch / pinch),
          1.8,
          9,
        );
        applyOrbit();
      }
      lastPinch = pinch;
    }
    // Not passive: without preventDefault the WebView scrolls the page while
    // the athlete is being orbited, and the drag fights the scroll.
    event.preventDefault();
  }, { passive: false });

  canvas.addEventListener('touchend', () => {
    dragging = false;
    lastPinch = 0;
  }, { passive: true });

  // Mouse, so the page is testable in a desktop browser without a device.
  canvas.addEventListener('pointerdown', (event) => {
    if (event.pointerType === 'touch') return;
    dragging = true;
    lastX = event.clientX;
    lastY = event.clientY;
  });
  window.addEventListener('pointermove', (event) => {
    if (!dragging || event.pointerType === 'touch') return;
    state.orbit.theta -= (event.clientX - lastX) * 0.35;
    state.orbit.phi -= (event.clientY - lastY) * 0.3;
    lastX = event.clientX;
    lastY = event.clientY;
    applyOrbit();
  });
  window.addEventListener('pointerup', () => { dragging = false; });
}

// ── Android bridge ─────────────────────────────────────────────────────────

/**
 * Calls back into the `@JavascriptInterface` object Android injected, if any.
 *
 * Every call is guarded. The same page has to run in a plain browser during
 * development, where `AndroidAthlete` simply is not there.
 */
function notifyAndroid(method, payload) {
  const bridge = window.AndroidAthlete;
  if (bridge && typeof bridge[method] === 'function') {
    try {
      bridge[method](payload);
    } catch (error) {
      console.warn('[athlete] bridge call failed', error);
    }
  }
}

/** Everything Android is allowed to call, and nothing else. */
function exposeApi() {
  /**
   * window.playAnimation("serve" | "receive" | "set" | "spike" | "idle")
   * Optional second argument: { speed: 1.0, immediate: false }
   */
  window.playAnimation = (name, options) => play(name, options || {});

  /** Back to idle, from the top. */
  window.resetAnimation = () => {
    for (const action of Object.values(state.actions)) action.stop();
    state.current = null;
    state.currentName = null;
    state.orbit = { ...CAMERA_PRESETS.full };
    applyOrbit();
    return play('idle', { immediate: true });
  };

  /**
   * window.setPlayerColor({ shirt, stripe, shorts, shoeBody, skin, hair, ... })
   * window.setPlayerColor("#14161c")  - shorthand for the shirt alone.
   *
   * Accepts "#rrggbb", "rrggbb" or a number. Unknown keys are ignored rather
   * than throwing, so an Android build that knows about a colour this page does
   * not degrades to leaving it alone.
   */
  window.setPlayerColor = (input) => {
    if (!state.materials) {
      console.warn('[athlete] colours apply to the procedural figure only');
      return false;
    }
    const colors = typeof input === 'string' || typeof input === 'number'
      ? { shirt: input }
      : (input || {});
    let changed = 0;
    for (const [slot, value] of Object.entries(colors)) {
      const material = state.materials[slot];
      if (!material) continue;
      material.color.set(normaliseColor(value));
      changed++;
    }
    return changed > 0;
  };

  /**
   * window.setCamera("full" | "closeup" | "action" | "front" | "side")
   * window.setCamera({ theta, phi, distance, target })  - degrees and metres.
   */
  window.setCamera = (input) => {
    const preset = typeof input === 'string' ? CAMERA_PRESETS[input] : null;
    if (typeof input === 'string' && !preset) {
      console.warn(`[athlete] unknown camera preset "${input}"`);
      return false;
    }
    Object.assign(state.orbit, preset || input || {});
    applyOrbit();
    return true;
  };

  /** window.setBackground("transparent" | "white" | "#f0f8ff") */
  window.setBackground = (value) => {
    if (!value || value === 'transparent') {
      state.renderer.setClearColor(0x000000, 0);
      document.body.style.background = 'transparent';
      return true;
    }
    const color = value === 'white' ? 0xffffff : normaliseColor(value);
    state.renderer.setClearColor(color, 1);
    document.body.style.background = `#${new THREE.Color(color).getHexString()}`;
    return true;
  };

  /** Which animations actually loaded. Useful for logging on the Android side. */
  window.availableAnimations = () => Object.keys(state.actions);

  /** Lets Android stop the render loop explicitly, e.g. from onPause. */
  window.setRendering = (enabled) => {
    if (enabled) start(); else stop();
    return enabled;
  };
}

function normaliseColor(value) {
  if (typeof value === 'number') return value;
  const text = String(value).trim();
  return text.startsWith('#') ? text : `#${text}`;
}
