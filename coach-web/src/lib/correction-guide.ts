import { cueDefinition, formatMeasured } from "./cues";

/* What a correction means, in the same words as the Android app's CorrectionGuide.kt.
   Order: wrong below the range, wrong above it, why, fix below, fix above. */

type Row = [string, string, string, string, string];
const NEAR = "The posture check flagged this position even though the measured value is close to the practice range.";
export const CORRECTIONS: Record<string, Row> = {
  ServeContactArmExtension: [
    "The hitting elbow was bent at the top of the swing.",
    "The hitting arm was pushed past straight at the top of the swing.",
    "A long arm meets the ball higher and gives the swing more reach over the net. A bent elbow lowers contact and makes the swing a push.",
    "Draw the elbow back high, then swing up and finish with the arm long above the shoulder.",
    "Reach up tall but stop at a comfortable straight arm. Do not force the elbow.",
  ],
  ServeReachHeight: [
    "The upper arm did not lift high enough, so the reach stayed low.",
    "The arm reached past the practice range overhead.",
    "A higher reach leaves more room over the net and keeps contact in front of the body.",
    "Lift the hitting elbow above the shoulder first, then reach up toward the ceiling before swinging through.",
    "Keep the reach tall but in front of the body, with the ribs down and the lower back relaxed.",
  ],
  ServeTrunkLean: [
    "The body leaned back at the top of the swing.",
    "The body leaned too far forward at the top of the swing.",
    "A controlled, upright trunk keeps balance, so the arm can swing the same way on every serve.",
    "Step toward the target and bring the chest over the front foot as the arm swings.",
    "Keep the chest up and the eyes forward, and let the arm reach instead of bending from the waist.",
  ],
  ServeTossArmLift: [
    "The guiding arm stayed low before the swing.",
    "The guiding arm was lifted higher than the practice range.",
    "The guiding arm sets the direction of the toss and gives the hitting side time to load.",
    "Raise the guiding arm smoothly to about eye level or higher while the hitting elbow draws back.",
    "Lift the guiding arm smoothly and stop in front of the face, without swinging it back overhead.",
  ],
  PassPlatformStraight: [
    "The elbows were bent when the pass was made, so the forearms did not form one flat platform.",
    "The arms were pushed past straight when the pass was made.",
    "Straight, joined forearms give the ball one flat surface, so it goes where the platform points. Bent elbows make two uneven surfaces and the ball can go anywhere.",
    "Join the hands early, point the thumbs at the floor and lock both elbows before the knees extend. Keep the arms still and lift with the legs.",
    "Keep the arms straight but relaxed. Do not force the elbows.",
  ],
  PassPlatformHeight: [
    "The platform was held too high, close to shoulder height.",
    "The platform pointed almost straight down.",
    "A platform below the chest and angled to the floor sends the ball up and forward. Held at the shoulders, the ball tends to come back or go overhead.",
    "Drop the joined forearms below the chest, roll the shoulders forward and keep the arms out in front of the body.",
    "Bring the forearms up a little so they point out in front of the knees.",
  ],
  PassKneeFlexion: [
    "The knees were bent very deep when the pass was made.",
    "The knees stayed almost straight when the pass was made.",
    "A medium bend keeps balance and lets the legs lift the ball, so the arms can stay still.",
    "Rise slightly from the crouch, keep the weight on the balls of the feet and the hips behind the heels.",
    "Bend the knees until the hips drop clearly below standing height, then push up through the legs as the arms meet the ball.",
  ],
  PassTrunkAngle: [
    "The trunk was too upright when the pass was made.",
    "The trunk leaned too far forward when the pass was made.",
    "A slight lean from the hips keeps the shoulders in front of the knees, so the platform stays out in front of the body.",
    "Push the hips back and lean slightly forward, with the shoulders over the knees.",
    "Bring the chest up a little and bend the knees more instead of folding at the waist.",
  ],
  PassShouldersLevel: [
    "One shoulder was lower than the other when the pass was made.",
    "One shoulder was lower than the other when the pass was made.",
    "Tilted shoulders tilt the platform, which can send the ball off to one side.",
    "Keep both shoulders at the same height, facing where the pass should go.",
    "Move the feet so the body is behind the ball, then keep both shoulders at the same height as the arms come up.",
  ],
  SetHandsAboveForehead: [
    "The hands were too low or too close to the face when setting.",
    "The hands were too far away from the forehead when setting.",
    "Hands just above and in front of the forehead let both arms push evenly and keep the target in sight.",
    "Lift both hands earlier, shaped above the forehead, with the elbows out in front.",
    "Bring both hands closer, just above and slightly in front of the forehead.",
  ],
  SetElbowFlexion: [
    "The elbows were folded very tight before the push.",
    "The elbows were already almost straight before the push.",
    "A medium bend loads both arms, so they can extend together and send the ball with control.",
    "Open the elbows a little, so the hands stay in front of the face instead of dropping to it.",
    "Bend the elbows more as the hands come up, then extend both arms together.",
  ],
  SetKneeFlexion: [
    "The knees were bent very deep before the push.",
    "The knees stayed almost straight before the push.",
    "Soft knees start the push from the legs, so the arms do not have to do all the work.",
    "Stand a little taller with soft knees, ready to extend.",
    "Bend the knees softly as the hands come up, then straighten the legs and arms together.",
  ],
  SetExtensionFollowThrough: [
    "The arms stopped short and did not finish straight.",
    "The arms were pushed past straight at the finish.",
    "A full, even finish with both arms keeps the direction and height of the set the same each time.",
    "Push all the way up until both elbows are straight, and hold the finish for a moment.",
    "Finish tall but relaxed. Do not force the elbows.",
  ],
  SpikeApproachLoad: [
    "The knees bent too deeply before the swing.",
    "The knees did not bend enough before the swing.",
    "A controlled knee bend helps you stay balanced as the swing begins.",
    "Use a slightly shallower bend and keep both feet planted.",
    "Bend your knees a little more as the hitting arm draws back, then straighten into the swing.",
  ],
  SpikeArmExtension: [
    "The hitting elbow was bent at the top of the swing.",
    "The hitting arm was pushed past straight at the top of the swing.",
    "A long arm meets the ball at its highest point and gives the swing more speed and angle.",
    "Lead with a high elbow, then reach up so the arm is long above the shoulder at the top.",
    "Reach tall but stop at a comfortable straight arm. Do not force the elbow.",
  ],
  SpikeElbowHeight: [
    "The hitting elbow dropped below shoulder height as the arm was drawn back.",
    "The hitting elbow was drawn higher than the practice range.",
    "A high elbow lets the arm swing up and over. A dropped elbow lowers the reach and puts more strain on the shoulder.",
    "Draw the elbow back level with the shoulder or higher, like pulling a bow, before swinging.",
    "Keep the elbow about level with the shoulder as the arm draws back.",
  ],
  SpikeJumpHeight: [
    "The hips did not rise as high as the practice range.",
    "The hips rose higher than the practice range.",
    "More height gives more time and a better angle over the net.",
    "Bend the knees, swing both arms back, then drive the arms and hips up together.",
    "Keep the jump balanced and land softly on both feet.",
  ],
  SpikeTrunkDrive: [
    "The body stayed back as the arm swung.",
    "The body folded too far forward as the arm swung.",
    "A controlled trunk helps transfer force through the swing while you stay balanced.",
    "Let the chest follow the arm forward as it swings through.",
    "Keep the chest taller and let the arm swing through, instead of bending at the waist.",
  ],
  BlockArmExtension: [
    "The elbows were bent at the top of the block.",
    "The arms were pushed past straight at the top of the block.",
    "Straight, firm arms make a taller wall and stop the ball from pushing the hands back.",
    "Reach up from the shoulders until both elbows are straight, with the hands firm.",
    "Reach tall and firm, without forcing the elbows.",
  ],
  BlockHandSeparation: [
    "The hands were too close together at the top of the block.",
    "The hands were too far apart at the top of the block.",
    "Hands about a ball apart cover more space without leaving a gap the ball can go through.",
    "Move the hands slightly apart, with the fingers spread.",
    "Bring the hands closer, so the gap is smaller than a ball.",
  ],
  BlockPenetration: [
    "The hands stayed back instead of reaching slightly forward.",
    "The hands reached too far forward.",
    "Reaching slightly forward closes the space in front of the body. Reaching too far makes it easy to touch the net.",
    "Shrug the shoulders and press the hands slightly forward at the top.",
    "Keep the hands above the shoulders and only slightly in front.",
  ],
  BlockKneeLoad: [
    "The knees were bent very deep before going up.",
    "The knees did not bend enough before going up.",
    "A quick, medium bend gives height and keeps the block on time.",
    "Use a shallower, quicker bend, so the hands go up on time.",
    "Bend the knees more from a balanced stance, hands already up, then push straight up.",
  ],
};

export type Correction = { wrong: string; why: string; fix: string; gap: string };

/** Null for a cue this website does not know. */
export function correctionFor(cueId: string, measured: number): Correction | null {
  const row = CORRECTIONS[cueId], cue = cueDefinition(cueId);
  if (!row || !cue) return null;
  const [low, high] = cue.target;
  if (measured < low) return { wrong: row[0], why: row[2], fix: row[3], gap: `${formatMeasured(low - measured, cue.unit)} below the range` };
  if (measured > high) return { wrong: row[1], why: row[2], fix: row[4], gap: `${formatMeasured(measured - high, cue.unit)} above the range` };
  return { wrong: NEAR, why: row[2], fix: row[3], gap: "" };
}
