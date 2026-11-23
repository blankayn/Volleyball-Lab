/* Coaching-cue reference, mirrored from the Android app's
   domain/model/FormCue.kt (FormCueCatalog). Labels and tips match the app's
   strings word-for-word so both clients tell the athlete the same thing;
   targets match the catalog's first-pass coaching values. If the app retunes a
   threshold after the coach interviews, change it here too. */

export type CueUnit = "deg" | "m" | "ratio";

export interface CueDef {
  id: string;
  weight: number;
  label: string;
  tip: string;
  unit: CueUnit;
  target: [number, number];
}

const CUES: Record<string, CueDef[]> = {
  Serving: [
    { id: "ServeContactArmExtension", weight: 0.35, label: "Extend the raised arm", tip: "Reach upward and extend your elbow comfortably above your shoulder.", unit: "deg", target: [155, 180] },
    { id: "ServeReachHeight", weight: 0.30, label: "Reach high", tip: "Raise your arm overhead while keeping your trunk controlled.", unit: "deg", target: [140, 180] },
    { id: "ServeTrunkLean", weight: 0.20, label: "Stay tall", tip: "Keep your trunk comfortably upright and your weight balanced over your feet.", unit: "deg", target: [-10, 15] },
    { id: "ServeTossArmLift", weight: 0.15, label: "Lift the guiding arm", tip: "Raise your guiding arm steadily while preparing the other elbow.", unit: "deg", target: [90, 160] },
  ],
  Receiving: [
    { id: "PassPlatformStraight", weight: 0.30, label: "Steady the platform", tip: "Join your forearms and extend both elbows comfortably to form a steady platform.", unit: "deg", target: [165, 180] },
    { id: "PassPlatformHeight", weight: 0.10, label: "Keep the platform below the chest", tip: "Angle your joined forearms down toward the floor instead of lifting them to shoulder height.", unit: "deg", target: [25, 90] },
    { id: "PassKneeFlexion", weight: 0.30, label: "Bend the knees", tip: "Bend your knees comfortably while keeping a balanced stance.", unit: "deg", target: [110, 150] },
    { id: "PassTrunkAngle", weight: 0.15, label: "Lean from the hips", tip: "Lean slightly forward from your hips while keeping your base balanced.", unit: "deg", target: [15, 40] },
    { id: "PassShouldersLevel", weight: 0.15, label: "Level the shoulders", tip: "Keep your shoulders level to steady your forearm platform.", unit: "deg", target: [0, 12] },
  ],
  Setting: [
    { id: "SetHandsAboveForehead", weight: 0.35, label: "Hands above the forehead", tip: "Position your hands above and slightly in front of your forehead.", unit: "m", target: [0.02, 0.4] },
    { id: "SetElbowFlexion", weight: 0.25, label: "Load the elbows", tip: "Keep your elbows comfortably bent before extending your arms upward.", unit: "deg", target: [80, 120] },
    { id: "SetKneeFlexion", weight: 0.20, label: "Soften the knees", tip: "Start with softly bent knees, then extend under control.", unit: "deg", target: [130, 165] },
    { id: "SetExtensionFollowThrough", weight: 0.20, label: "Finish extended", tip: "Extend both arms upward evenly, then return to your starting posture.", unit: "deg", target: [155, 180] },
  ],
  Spiking: [
    { id: "SpikeApproachLoad", weight: 0.20, label: "Load the knees", tip: "Bend the knees from a balanced stance before an in-place jump. Take off from the same spot.", unit: "deg", target: [80, 115] },
    { id: "SpikeArmExtension", weight: 0.25, label: "Swing through full extension", tip: "Extend the raised arm above your shoulder within a comfortable range.", unit: "deg", target: [155, 180] },
    { id: "SpikeElbowHeight", weight: 0.20, label: "Keep the hitting elbow high", tip: "As you draw the hitting arm back, keep the elbow at or above shoulder height.", unit: "deg", target: [0, 90] },
    { id: "SpikeJumpHeight", weight: 0.20, label: "Get off the floor", tip: "Drive the hips upward. Measured against your own standing hip height.", unit: "m", target: [0.3, 1.0] },
    { id: "SpikeTrunkDrive", weight: 0.15, label: "Control the trunk", tip: "Keep your trunk controlled as your raised arm extends overhead.", unit: "deg", target: [-5, 25] },
  ],
  Blocking: [
    { id: "BlockArmExtension", weight: 0.30, label: "Straight arms", tip: "Extend both elbows comfortably during the overhead reach.", unit: "deg", target: [160, 180] },
    { id: "BlockHandSeparation", weight: 0.25, label: "Hand spacing", tip: "Keep your hands evenly spaced above your shoulders.", unit: "m", target: [0.15, 0.45] },
    { id: "BlockPenetration", weight: 0.25, label: "Reach slightly forward", tip: "Position your hands slightly in front of your shoulders during the overhead reach.", unit: "m", target: [0.05, 0.35] },
    { id: "BlockKneeLoad", weight: 0.20, label: "Load before the jump", tip: "Bend your knees from a balanced stance before the upward movement.", unit: "deg", target: [90, 125] },
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

export function cueDefinition(id: string): CueDef | undefined {
  return Object.values(CUES).flat().find(cue => cue.id === id);
}
