/* Joints found on /assets/volleyball-serving.jpg (736 x 920) by the Android app's own
   model, pose_landmarker_full.task. Pixel coordinates are the raw detections; every
   landmark was returned with a visibility of at least 0.99. */
export const servingPose = {
  leftShoulder: [246.9, 397.9], rightShoulder: [159.7, 412.3],
  leftElbow: [329.2, 381.4], rightElbow: [93.1, 377],
  leftWrist: [406.6, 356], rightWrist: [89.4, 332.1],
  leftHip: [249.2, 572.3], rightHip: [197.2, 569.8],
  leftKnee: [281.1, 672.7], rightKnee: [162.3, 677.2],
  leftAnkle: [248.5, 794], rightAnkle: [71.7, 754.4],
  leftFoot: [301.1, 825.4], rightFoot: [48.4, 815.5],
} as const;

export type ServingJoint = keyof typeof servingPose;

export const servingLinks: readonly (readonly [ServingJoint, ServingJoint])[] = [
  ["leftShoulder", "rightShoulder"],
  ["leftShoulder", "leftElbow"], ["leftElbow", "leftWrist"],
  ["rightShoulder", "rightElbow"], ["rightElbow", "rightWrist"],
  ["leftShoulder", "leftHip"], ["rightShoulder", "rightHip"], ["leftHip", "rightHip"],
  ["leftHip", "leftKnee"], ["leftKnee", "leftAnkle"], ["leftAnkle", "leftFoot"],
  ["rightHip", "rightKnee"], ["rightKnee", "rightAnkle"], ["rightAnkle", "rightFoot"],
];
