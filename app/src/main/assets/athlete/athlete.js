import * as THREE from 'three';

/**
 * The procedural Volleyball Lab athlete.
 *
 * ## Why a character is built in code at all
 *
 * The proper way to ship a 3D character is a rigged GLB from Blender or Mixamo,
 * and `scene.js` loads one the moment `athlete.glb` appears next to these files.
 * Until then this exists so the feature is not blocked on art: it is the same
 * skeleton, the same five clip names and the same API, so swapping in the real
 * model changes nothing above this file.
 *
 * ## How it is animated
 *
 * A hierarchy of named `Object3D` joints with one low-poly mesh parented to each,
 * rather than a `SkinnedMesh`. For a stylised figure with hard joints, skinning
 * buys nothing visible and costs a per-vertex GPU skin every frame on phones that
 * are already running MediaPipe. Rotating joints is the whole animation model.
 *
 * Clips are authored below as poses at times, then compiled into real
 * `AnimationClip`s, so `AnimationMixer`, `AnimationAction`, cross-fading and
 * playback rate all work exactly as they would with imported clips.
 */

const deg = (d) => (d * Math.PI) / 180;

/** Every colour the athlete is made of, all overridable via setPlayerColor. */
export const DEFAULT_COLORS = {
  skin: 0xd9a074,
  hair: 0x2b2118,
  glasses: 0x1c1c22,
  shirt: 0x14161c,
  stripe: 0xf2c811,
  shorts: 0x0e1014,
  shoeBody: 0xf5f5f5,
  shoeSole: 0x14161c,
  shoeAccent: 0xf2c811,
  ball: 0xf5f5f5,
  ballSeam: 0xf2811d,
};

/**
 * Shared material cache.
 *
 * One material per colour rather than one per mesh: materials are what drive
 * shader compilation and draw-call batching, and a forty-mesh figure with forty
 * materials is forty state changes a frame for no visual difference.
 */
function createMaterials(colors) {
  const make = (hex) =>
    new THREE.MeshLambertMaterial({ color: hex, flatShading: true });
  return {
    skin: make(colors.skin),
    hair: make(colors.hair),
    glasses: make(colors.glasses),
    shirt: make(colors.shirt),
    stripe: make(colors.stripe),
    shorts: make(colors.shorts),
    shoeBody: make(colors.shoeBody),
    shoeSole: make(colors.shoeSole),
    shoeAccent: make(colors.shoeAccent),
    ball: make(colors.ball),
    ballSeam: make(colors.ballSeam),
  };
}

/** A joint: an empty transform that meshes hang off and animations rotate. */
function joint(name, parent, x, y, z) {
  const node = new THREE.Object3D();
  node.name = name;
  node.position.set(x, y, z);
  parent.add(node);
  return node;
}

/**
 * A limb segment drawn as a capsule running *down* from its joint.
 *
 * Offset by half its length so the joint sits at the top of the segment, which
 * is what makes rotating the joint bend the limb at the right place rather than
 * pivoting it around its middle.
 */
function limb(parent, material, length, radius, sides = 6) {
  const geometry = new THREE.CapsuleGeometry(radius, length, 1, sides);
  const mesh = new THREE.Mesh(geometry, material);
  mesh.position.y = -length / 2;
  parent.add(mesh);
  return mesh;
}

function box(parent, material, w, h, d, x = 0, y = 0, z = 0) {
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), material);
  mesh.position.set(x, y, z);
  parent.add(mesh);
  return mesh;
}

/**
 * Builds the athlete and returns its root plus the joints animations drive.
 *
 * Polygon budget is spent where the eye goes: the head and the ball get round
 * geometry, limbs get six-sided capsules, and everything else is a box. The
 * whole figure lands around 1.5k triangles, which is roughly one mid-range
 * phone frame's worth of nothing.
 */
export function buildAthlete(colors = DEFAULT_COLORS) {
  const materials = createMaterials(colors);

  const root = new THREE.Group();
  root.name = 'Athlete';

  // ── Spine chain ────────────────────────────────────────────────────────
  const hips = joint('hips', root, 0, 0.92, 0);
  box(hips, materials.shorts, 0.30, 0.18, 0.19);

  const spine = joint('spine', hips, 0, 0.10, 0);
  const chest = joint('chest', spine, 0, 0.18, 0);
  box(chest, materials.shirt, 0.36, 0.34, 0.21, 0, 0.06, 0);

  // The yellow side stripes: two thin boxes proud of the shirt on either side.
  // Sitting slightly outside the torso rather than flush avoids z-fighting,
  // which on a phone GPU shows up as a stripe that flickers when you orbit.
  box(chest, materials.stripe, 0.025, 0.30, 0.16, 0.185, 0.06, 0);
  box(chest, materials.stripe, 0.025, 0.30, 0.16, -0.185, 0.06, 0);

  const neck = joint('neck', chest, 0, 0.25, 0);
  box(neck, materials.skin, 0.09, 0.07, 0.09, 0, 0.02, 0);

  // ── Head ───────────────────────────────────────────────────────────────
  const head = joint('head', neck, 0, 0.06, 0);
  const skull = new THREE.Mesh(new THREE.SphereGeometry(0.115, 12, 10), materials.skin);
  skull.scale.set(1, 1.12, 0.98);
  head.add(skull);

  // Side-part hair: a cap plus a swept fringe offset to one side, which is the
  // cheapest way to read as a parting without modelling strands.
  const hairCap = new THREE.Mesh(
    new THREE.SphereGeometry(0.121, 12, 10, 0, Math.PI * 2, 0, Math.PI * 0.55),
    materials.hair,
  );
  hairCap.position.y = 0.012;
  hairCap.scale.set(1, 1.08, 1);
  head.add(hairCap);
  const fringe = box(head, materials.hair, 0.13, 0.045, 0.10, 0.035, 0.086, 0.052);
  fringe.rotation.z = deg(-8);

  // Glasses: two rims and a bridge, in front of the face.
  const rimGeometry = new THREE.TorusGeometry(0.033, 0.007, 4, 10);
  const rimL = new THREE.Mesh(rimGeometry, materials.glasses);
  rimL.position.set(-0.042, 0.012, 0.104);
  head.add(rimL);
  const rimR = new THREE.Mesh(rimGeometry, materials.glasses);
  rimR.position.set(0.042, 0.012, 0.104);
  head.add(rimR);
  box(head, materials.glasses, 0.022, 0.007, 0.007, 0, 0.012, 0.106);
  box(head, materials.glasses, 0.012, 0.006, 0.05, -0.072, 0.012, 0.082);
  box(head, materials.glasses, 0.012, 0.006, 0.05, 0.072, 0.012, 0.082);

  // ── Arms ───────────────────────────────────────────────────────────────
  const arms = {};
  for (const side of ['L', 'R']) {
    const sign = side === 'L' ? 1 : -1;
    const shoulder = joint(`shoulder${side}`, chest, sign * 0.20, 0.19, 0);
    // Rest pose is a relaxed A-pose, not a T-pose: every clip below is written
    // as a departure from a stance the athlete could actually be standing in.
    shoulder.rotation.z = sign * deg(-72);

    const upper = joint(`upperArm${side}`, shoulder, 0, 0, 0);
    limb(upper, materials.skin, 0.26, 0.045);

    const lower = joint(`lowerArm${side}`, upper, 0, -0.28, 0);
    limb(lower, materials.skin, 0.24, 0.038);

    const hand = joint(`hand${side}`, lower, 0, -0.26, 0);
    box(hand, materials.skin, 0.075, 0.10, 0.045, 0, -0.045, 0);

    arms[side] = { shoulder, upper, lower, hand };
  }

  // ── Legs ───────────────────────────────────────────────────────────────
  const legs = {};
  for (const side of ['L', 'R']) {
    const sign = side === 'L' ? 1 : -1;
    const thigh = joint(`thigh${side}`, hips, sign * 0.095, -0.08, 0);
    limb(thigh, materials.shorts, 0.30, 0.062);

    const shin = joint(`shin${side}`, thigh, 0, -0.40, 0);
    limb(shin, materials.skin, 0.34, 0.048);

    const foot = joint(`foot${side}`, shin, 0, -0.38, 0);
    // Volleyball shoe: white body, dark gum sole, one accent flash.
    box(foot, materials.shoeBody, 0.10, 0.07, 0.24, 0, -0.03, 0.045);
    box(foot, materials.shoeSole, 0.105, 0.025, 0.25, 0, -0.072, 0.048);
    box(foot, materials.shoeAccent, 0.106, 0.016, 0.09, 0, -0.02, 0.02);

    legs[side] = { thigh, shin, foot };
  }

  // ── Ball ───────────────────────────────────────────────────────────────
  // Parented to the root, not to a hand: the clips move it independently so it
  // can be tossed, struck and dug without the hand having to hold it.
  const ball = joint('ball', root, 0, 1.05, 0.45);
  const ballMesh = new THREE.Mesh(new THREE.SphereGeometry(0.105, 14, 10), materials.ball);
  ball.add(ballMesh);
  for (let i = 0; i < 3; i++) {
    const seam = new THREE.Mesh(
      new THREE.TorusGeometry(0.104, 0.011, 4, 14),
      materials.ballSeam,
    );
    seam.rotation.set(deg(90), 0, deg(i * 60));
    ball.add(seam);
  }
  ball.visible = false;

  return {
    root,
    materials,
    joints: { hips, spine, chest, neck, head, arms, legs, ball },
  };
}

// ───────────────────────────────────────────────────────────────────────────
// Animation clips
//
// Authored as poses at times rather than as raw keyframe tracks. A pose is a map
// of "joint.property" to a value in degrees (or metres, for positions), and only
// the properties a pose actually mentions are keyed - so a clip that never moves
// the head simply says nothing about the head, and the head keeps whatever the
// previous action left it at.
//
// Tuning any of this is editing numbers in one table, which is the whole reason
// it is written this way instead of as compiled tracks.
// ───────────────────────────────────────────────────────────────────────────

/** Every animation the API exposes. Keys are the names Android passes in. */
export const CLIP_DEFINITIONS = {
  /**
   * Idle: breathing, a slow weight shift, and nothing else.
   *
   * Deliberately small. An idle that gestures reads as a character waiting for
   * you to notice it, which is exactly wrong for a screen the athlete is meant
   * to be looking past on their way to training.
   */
  idle: {
    duration: 4.0,
    loop: 'repeat',
    keys: [
      { t: 0.0, pose: { 'chest.rotation.x': -1, 'hips.position.y': 0.92, 'hips.rotation.y': 3, 'head.rotation.y': 2, 'upperArmL.rotation.x': 4, 'upperArmR.rotation.x': 4, 'lowerArmL.rotation.x': -10, 'lowerArmR.rotation.x': -10 } },
      { t: 1.0, pose: { 'chest.rotation.x': 2, 'hips.position.y': 0.935, 'head.rotation.y': -3 } },
      { t: 2.0, pose: { 'chest.rotation.x': -1, 'hips.position.y': 0.92, 'hips.rotation.y': -3, 'head.rotation.y': 0 } },
      { t: 3.0, pose: { 'chest.rotation.x': 2, 'hips.position.y': 0.935, 'head.rotation.y': 3 } },
      { t: 4.0, pose: { 'chest.rotation.x': -1, 'hips.position.y': 0.92, 'hips.rotation.y': 3, 'head.rotation.y': 2, 'upperArmL.rotation.x': 4, 'upperArmR.rotation.x': 4, 'lowerArmL.rotation.x': -10, 'lowerArmR.rotation.x': -10 } },
    ],
  },

  /** Serve: toss, load, strike, follow through. Right arm hits. */
  serve: {
    duration: 2.6,
    loop: 'once',
    ball: true,
    keys: [
      { t: 0.0, pose: { 'ball.position.y': 1.05, 'ball.position.x': 0.28, 'ball.position.z': 0.30, 'shoulderR.rotation.z': 72, 'upperArmR.rotation.x': 0, 'upperArmL.rotation.x': 0, 'chest.rotation.x': 0, 'chest.rotation.y': 0, 'thighL.rotation.x': 0, 'thighR.rotation.x': 0, 'shinL.rotation.x': 0, 'shinR.rotation.x': 0 } },
      // Toss up off the left hand.
      { t: 0.5, pose: { 'ball.position.y': 1.30, 'upperArmL.rotation.x': -70, 'lowerArmL.rotation.x': -10, 'chest.rotation.x': -6, 'thighL.rotation.x': -12, 'thighR.rotation.x': -12, 'shinL.rotation.x': 18, 'shinR.rotation.x': 18 } },
      // Ball peaks; hitting arm draws back behind the head.
      { t: 1.0, pose: { 'ball.position.y': 2.02, 'ball.position.x': 0.24, 'shoulderR.rotation.z': 130, 'upperArmR.rotation.x': 30, 'lowerArmR.rotation.x': -95, 'chest.rotation.x': -14, 'chest.rotation.y': -16, 'upperArmL.rotation.x': -80 } },
      // Contact.
      { t: 1.35, pose: { 'ball.position.y': 1.92, 'ball.position.x': 0.32, 'ball.position.z': 0.18, 'shoulderR.rotation.z': 160, 'upperArmR.rotation.x': -14, 'lowerArmR.rotation.x': -6, 'chest.rotation.x': 6, 'chest.rotation.y': 10, 'thighL.rotation.x': -4, 'thighR.rotation.x': -4, 'shinL.rotation.x': 4, 'shinR.rotation.x': 4 } },
      // Ball leaves; arm follows through across the body.
      { t: 1.7, pose: { 'ball.position.y': 2.30, 'ball.position.x': 0.20, 'ball.position.z': -1.4, 'shoulderR.rotation.z': 96, 'upperArmR.rotation.x': -46, 'lowerArmR.rotation.x': -22, 'chest.rotation.y': 20, 'upperArmL.rotation.x': -20 } },
      { t: 2.6, pose: { 'ball.position.y': 1.05, 'ball.position.x': 0.28, 'ball.position.z': 0.30, 'shoulderR.rotation.z': 72, 'upperArmR.rotation.x': 0, 'lowerArmR.rotation.x': -10, 'upperArmL.rotation.x': 0, 'lowerArmL.rotation.x': -10, 'chest.rotation.x': 0, 'chest.rotation.y': 0 } },
    ],
  },

  /**
   * Receive: drop into the platform and absorb.
   *
   * The pose this plays through is the one `PoseQualityValidator` and the
   * receiving cues are written against - deep knees, trunk forward, arms locked
   * together - so the animation and the scoring are describing the same thing.
   */
  receive: {
    duration: 2.4,
    loop: 'once',
    ball: true,
    keys: [
      { t: 0.0, pose: { 'ball.position.x': -1.4, 'ball.position.y': 1.5, 'ball.position.z': 0.9, 'shoulderL.rotation.z': -72, 'shoulderR.rotation.z': 72, 'hips.position.y': 0.92, 'chest.rotation.x': 0, 'thighL.rotation.x': 0, 'thighR.rotation.x': 0, 'shinL.rotation.x': 0, 'shinR.rotation.x': 0 } },
      // Load: knees bend, trunk comes forward, arms join into a platform.
      { t: 0.7, pose: { 'ball.position.x': -0.55, 'ball.position.y': 1.05, 'ball.position.z': 0.65, 'hips.position.y': 0.70, 'chest.rotation.x': 26, 'thighL.rotation.x': -52, 'thighR.rotation.x': -52, 'shinL.rotation.x': 62, 'shinR.rotation.x': 62, 'shoulderL.rotation.z': -20, 'shoulderR.rotation.z': 20, 'upperArmL.rotation.x': -58, 'upperArmR.rotation.x': -58, 'lowerArmL.rotation.x': 0, 'lowerArmR.rotation.x': 0 } },
      // Contact on the forearms.
      { t: 1.05, pose: { 'ball.position.x': -0.08, 'ball.position.y': 0.94, 'ball.position.z': 0.42, 'hips.position.y': 0.66, 'chest.rotation.x': 30, 'upperArmL.rotation.x': -64, 'upperArmR.rotation.x': -64 } },
      // Absorb and lift: legs drive, ball goes up and away.
      { t: 1.5, pose: { 'ball.position.x': 0.05, 'ball.position.y': 1.85, 'ball.position.z': -0.2, 'hips.position.y': 0.88, 'chest.rotation.x': 12, 'thighL.rotation.x': -16, 'thighR.rotation.x': -16, 'shinL.rotation.x': 20, 'shinR.rotation.x': 20, 'upperArmL.rotation.x': -74, 'upperArmR.rotation.x': -74 } },
      { t: 2.4, pose: { 'ball.position.x': -1.4, 'ball.position.y': 1.5, 'ball.position.z': 0.9, 'hips.position.y': 0.92, 'chest.rotation.x': 0, 'thighL.rotation.x': 0, 'thighR.rotation.x': 0, 'shinL.rotation.x': 0, 'shinR.rotation.x': 0, 'shoulderL.rotation.z': -72, 'shoulderR.rotation.z': 72, 'upperArmL.rotation.x': 0, 'upperArmR.rotation.x': 0, 'lowerArmL.rotation.x': -10, 'lowerArmR.rotation.x': -10 } },
    ],
  },

  /** Set: hands above the forehead, knees load, extend through the ball. */
  set: {
    duration: 2.2,
    loop: 'once',
    ball: true,
    keys: [
      { t: 0.0, pose: { 'ball.position.x': 0, 'ball.position.y': 2.2, 'ball.position.z': 0.1, 'shoulderL.rotation.z': -72, 'shoulderR.rotation.z': 72, 'hips.position.y': 0.92, 'chest.rotation.x': 0, 'thighL.rotation.x': 0, 'thighR.rotation.x': 0, 'shinL.rotation.x': 0, 'shinR.rotation.x': 0 } },
      // Hands up early - a setter is in shape before the ball arrives.
      { t: 0.55, pose: { 'ball.position.y': 1.75, 'shoulderL.rotation.z': -128, 'shoulderR.rotation.z': 128, 'upperArmL.rotation.x': -18, 'upperArmR.rotation.x': -18, 'lowerArmL.rotation.x': -78, 'lowerArmR.rotation.x': -78, 'handL.rotation.x': -30, 'handR.rotation.x': -30, 'thighL.rotation.x': -26, 'thighR.rotation.x': -26, 'shinL.rotation.x': 34, 'shinR.rotation.x': 34, 'hips.position.y': 0.80 } },
      // Contact above the forehead, knees at their deepest.
      { t: 0.95, pose: { 'ball.position.y': 1.60, 'lowerArmL.rotation.x': -92, 'lowerArmR.rotation.x': -92, 'thighL.rotation.x': -34, 'thighR.rotation.x': -34, 'shinL.rotation.x': 44, 'shinR.rotation.x': 44, 'hips.position.y': 0.76 } },
      // Full extension: legs, arms and wrists finish together.
      { t: 1.35, pose: { 'ball.position.y': 2.35, 'ball.position.z': -0.35, 'shoulderL.rotation.z': -155, 'shoulderR.rotation.z': 155, 'lowerArmL.rotation.x': -14, 'lowerArmR.rotation.x': -14, 'handL.rotation.x': -55, 'handR.rotation.x': -55, 'thighL.rotation.x': 0, 'thighR.rotation.x': 0, 'shinL.rotation.x': 0, 'shinR.rotation.x': 0, 'hips.position.y': 0.96 } },
      { t: 2.2, pose: { 'ball.position.x': 0, 'ball.position.y': 2.2, 'ball.position.z': 0.1, 'shoulderL.rotation.z': -72, 'shoulderR.rotation.z': 72, 'upperArmL.rotation.x': 0, 'upperArmR.rotation.x': 0, 'lowerArmL.rotation.x': -10, 'lowerArmR.rotation.x': -10, 'handL.rotation.x': 0, 'handR.rotation.x': 0, 'hips.position.y': 0.92 } },
    ],
  },

  /** Spike: approach load, jump, arm back, swing through, land. */
  spike: {
    duration: 2.8,
    loop: 'once',
    ball: true,
    keys: [
      { t: 0.0, pose: { 'ball.position.x': 0.1, 'ball.position.y': 2.3, 'ball.position.z': 0.2, 'hips.position.y': 0.92, 'shoulderR.rotation.z': 72, 'shoulderL.rotation.z': -72, 'chest.rotation.x': 0, 'chest.rotation.y': 0, 'thighL.rotation.x': 0, 'thighR.rotation.x': 0, 'shinL.rotation.x': 0, 'shinR.rotation.x': 0 } },
      // Approach load: deep knees, arms swung back behind the hips.
      { t: 0.45, pose: { 'ball.position.y': 2.15, 'hips.position.y': 0.62, 'chest.rotation.x': 26, 'thighL.rotation.x': -64, 'thighR.rotation.x': -64, 'shinL.rotation.x': 78, 'shinR.rotation.x': 78, 'shoulderL.rotation.z': -60, 'shoulderR.rotation.z': 60, 'upperArmL.rotation.x': 55, 'upperArmR.rotation.x': 55 } },
      // Takeoff: arms throw up, legs extend.
      { t: 0.8, pose: { 'ball.position.y': 2.05, 'hips.position.y': 1.15, 'chest.rotation.x': -6, 'thighL.rotation.x': -6, 'thighR.rotation.x': -6, 'shinL.rotation.x': 8, 'shinR.rotation.x': 8, 'shoulderL.rotation.z': -140, 'shoulderR.rotation.z': 140, 'upperArmL.rotation.x': -20, 'upperArmR.rotation.x': -20 } },
      // Peak of the jump, hitting arm cocked, bow shape through the trunk.
      { t: 1.15, pose: { 'ball.position.y': 2.00, 'ball.position.x': 0.25, 'hips.position.y': 1.42, 'chest.rotation.x': -20, 'chest.rotation.y': -22, 'shoulderR.rotation.z': 132, 'upperArmR.rotation.x': 34, 'lowerArmR.rotation.x': -110, 'shoulderL.rotation.z': -150, 'upperArmL.rotation.x': -30, 'thighL.rotation.x': 16, 'thighR.rotation.x': -16, 'shinL.rotation.x': 30, 'shinR.rotation.x': 42 } },
      // Contact: arm snaps over the ball, trunk whips forward.
      { t: 1.45, pose: { 'ball.position.y': 1.95, 'ball.position.x': 0.30, 'ball.position.z': 0.05, 'hips.position.y': 1.36, 'chest.rotation.x': 16, 'chest.rotation.y': 12, 'shoulderR.rotation.z': 168, 'upperArmR.rotation.x': -18, 'lowerArmR.rotation.x': -4, 'shoulderL.rotation.z': -100, 'upperArmL.rotation.x': 20 } },
      // Ball is gone; arm follows through past the hip.
      { t: 1.75, pose: { 'ball.position.y': 1.1, 'ball.position.z': -2.2, 'hips.position.y': 1.05, 'chest.rotation.x': 24, 'shoulderR.rotation.z': 84, 'upperArmR.rotation.x': -62, 'lowerArmR.rotation.x': -30 } },
      // Land soft: knees take it, not the ankles.
      { t: 2.1, pose: { 'ball.position.y': 0.6, 'ball.position.z': -3.0, 'hips.position.y': 0.70, 'chest.rotation.x': 22, 'chest.rotation.y': 0, 'thighL.rotation.x': -46, 'thighR.rotation.x': -46, 'shinL.rotation.x': 56, 'shinR.rotation.x': 56 } },
      { t: 2.8, pose: { 'ball.position.x': 0.1, 'ball.position.y': 2.3, 'ball.position.z': 0.2, 'hips.position.y': 0.92, 'chest.rotation.x': 0, 'thighL.rotation.x': 0, 'thighR.rotation.x': 0, 'shinL.rotation.x': 0, 'shinR.rotation.x': 0, 'shoulderL.rotation.z': -72, 'shoulderR.rotation.z': 72, 'upperArmL.rotation.x': 0, 'upperArmR.rotation.x': 0, 'lowerArmL.rotation.x': -10, 'lowerArmR.rotation.x': -10 } },
    ],
  },
};

/** Property paths that carry metres. Everything else is authored in degrees. */
const POSITION_PATH = /\.position\.[xyz]$/;

/**
 * Compiles one pose-table definition into a real [THREE.AnimationClip].
 *
 * Each distinct property path becomes one `NumberKeyframeTrack`, keyed only at
 * the times that pose actually mentions it. Three interpolates between those,
 * so a property named at t=0 and t=2 sweeps across the whole clip without every
 * intermediate key having to repeat it.
 */
export function compileClip(name, definition) {
  const byPath = new Map();

  for (const key of definition.keys) {
    for (const [path, value] of Object.entries(key.pose)) {
      if (!byPath.has(path)) byPath.set(path, { times: [], values: [] });
      const track = byPath.get(path);
      track.times.push(key.t);
      track.values.push(POSITION_PATH.test(path) ? value : deg(value));
    }
  }

  const tracks = [];
  for (const [path, data] of byPath) {
    // "hips.rotation.x" as authored becomes "hips.rotation[x]", which is what
    // PropertyBinding parses. Writing it the other way in the tables above
    // would be the same information in a less readable shape.
    const trackName = path.replace(/\.(position|rotation|scale)\.([xyz])$/, '.$1[$2]');
    tracks.push(new THREE.NumberKeyframeTrack(trackName, data.times, data.values));
  }

  return new THREE.AnimationClip(name, definition.duration, tracks);
}

/** Every clip, compiled. Keyed by the name the API accepts. */
export function buildClips() {
  const clips = {};
  for (const [name, definition] of Object.entries(CLIP_DEFINITIONS)) {
    clips[name] = {
      clip: compileClip(name, definition),
      loop: definition.loop,
      showsBall: Boolean(definition.ball),
    };
  }
  return clips;
}
