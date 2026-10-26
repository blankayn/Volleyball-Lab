/* Coaching-cue reference, mirrored from the Android app's
   domain/model/FormCue.kt (FormCueCatalog). Labels and tips match the app's
   strings word-for-word so both clients tell the athlete the same thing;
   targets match the catalog's first-pass coaching values. If the app retunes a
   threshold after the coach interviews, change it here too. */

export type CueUnit = "deg" | "m" | "ratio";

export interface CueDef {
  id: string;
  label: string;
  tip: string;
  unit: CueUnit;
  target: [number, number];
}

const CUES: Record<string, CueDef[]> = {
  Serving: [
    { id: "ServeContactArmExtension", label: "Extend at contact", tip: "Meet the ball with a straight hitting arm. A bent elbow at contact costs both power and accuracy.", unit: "deg", target: [155, 180] },
    { id: "ServeReachHeight", label: "Reach high", tip: "Contact the ball at the top of your reach, not out in front of your chest.", unit: "deg", target: [140, 180] },
    { id: "ServeTrunkLean", label: "Stay tall", tip: "Keep the trunk close to upright. Leaning back sends the serve long.", unit: "deg", target: [-10, 15] },
    { id: "ServeTossArmLift", label: "Lift the toss arm", tip: "Raise the tossing arm and hold it up. Dropping it early pulls the hitting shoulder down with it.", unit: "deg", target: [90, 160] },
  ],
  Receiving: [
    { id: "PassPlatformStraight", label: "Lock the platform", tip: "Keep both elbows straight and joined. Bending them makes the pass unpredictable.", unit: "deg", target: [165, 180] },
    { id: "PassKneeFlexion", label: "Bend the knees", tip: "Load into an athletic stance. Passing with straight legs takes away your control.", unit: "deg", target: [110, 150] },
    { id: "PassTrunkAngle", label: "Lean into the ball", tip: "Angle the chest forward over the platform rather than standing upright.", unit: "deg", target: [15, 40] },
    { id: "PassShouldersLevel", label: "Level the shoulders", tip: "Square, level shoulders keep the platform flat and the pass on target.", unit: "deg", target: [0, 12] },
  ],
  Setting: [
    { id: "SetHandsAboveForehead", label: "Hands above the forehead", tip: "Meet the ball above and slightly in front of the forehead, not down at chin height.", unit: "m", target: [0.02, 0.4] },
    { id: "SetElbowFlexion", label: "Load the elbows", tip: "Elbows bent and out on contact, so you can push through the ball.", unit: "deg", target: [80, 120] },
    { id: "SetKneeFlexion", label: "Set with the legs", tip: "Bend the knees before the set. The power comes from the legs, not the wrists.", unit: "deg", target: [130, 165] },
    { id: "SetExtensionFollowThrough", label: "Finish extended", tip: "Follow through until the arms are straight and the hands finish toward the target.", unit: "deg", target: [155, 180] },
  ],
  Spiking: [
    { id: "SpikeApproachLoad", label: "Load the knees", tip: "Bend the knees from a balanced stance before an in-place jump. Take off from the same spot.", unit: "deg", target: [80, 115] },
    { id: "SpikeArmExtension", label: "Swing through full extension", tip: "Contact with the hitting arm straight, above the shoulder.", unit: "deg", target: [155, 180] },
    { id: "SpikeJumpHeight", label: "Get off the floor", tip: "Drive the hips upward. Measured against your own standing hip height.", unit: "m", target: [0.3, 1.0] },
    { id: "SpikeTrunkDrive", label: "Drive the trunk", tip: "Snap the chest forward through contact instead of hanging back.", unit: "deg", target: [-5, 25] },
  ],
  Blocking: [
    { id: "BlockArmExtension", label: "Straight arms", tip: "Lock both elbows at the top of the block. Bent arms collapse under a hard hit.", unit: "deg", target: [160, 180] },
    { id: "BlockHandSeparation", label: "Hand spacing", tip: "Close enough that the ball cannot pass between the hands, wide enough to cover the seam.", unit: "m", target: [0.15, 0.45] },
    { id: "BlockPenetration", label: "Penetrate the net", tip: "Reach the hands over and past the net line rather than straight up.", unit: "m", target: [0.05, 0.35] },
    { id: "BlockKneeLoad", label: "Load before the jump", tip: "Bend the knees to load. Blocking flat-footed means late hands.", unit: "deg", target: [90, 125] },
  ],
};

export function cuesFor(skill: string): CueDef[] {
  return CUES[skill] ?? [];
}

export function cueLabel(cueId: string): string {
  for (const list of Object.values(CUES)) {
    const found = list.find((c) => c.id === cueId);
    if (found) return found.label;
  }
  return cueId;
}

export function formatMeasured(value: number, unit: CueUnit): string {
  if (unit === "deg") return `${Math.round(value)}°`;
  if (unit === "m") return `${value.toFixed(2)} m`;
  return `${Math.round(value * 100)}%`;
}

export function formatTarget([lo, hi]: [number, number], unit: CueUnit): string {
  if (unit === "deg") return `${Math.round(lo)}–${Math.round(hi)}°`;
  if (unit === "m") return `${lo.toFixed(2)}–${hi.toFixed(2)} m`;
  return `${Math.round(lo * 100)}–${Math.round(hi * 100)}%`;
}

export const SKILL_LABEL: Record<string, string> = {
  Serving: "Serving",
  Receiving: "Receiving",
  Setting: "Setting",
  Spiking: "Spiking",
  Blocking: "Blocking",
};
