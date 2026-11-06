# Volleyball reference coverage — 5 November 2026

The sources checked below do **not yet establish separately labelled, paired
RGB/metric-3D coverage of all five skills**. A sequence named volleyball does not
prove that it contains serving, receiving, setting, spiking and blocking.

| Candidate | Independent reference evidence | Individual skill evidence | Available next step |
|---|---|---|---|
| MADS | Synchronized, calibrated motion-capture references and three RGB views | Published label is volleyball; no five-skill frame labels in the inspected release | Continue reproducible evaluation of the acquired camera-1 sequence; keep the result `volleyball_unspecified` |
| SportsPose | Multi-view triangulated reference; authors separately compared it with marker-based motion capture | Paper lists volleyball as one activity; it does not establish all five volleyball skills | Await the already-authorized access request; inspect actual sequences and labels after access |
| EgoHumans | Multi-view 3D human references, calibrated views and persistent identities | Volleyball sequences are distributed, but no five-skill action labels were established | Retrieve a bounded prefix of an original sequence when the current public-download quota clears; inspect member order before choosing a transfer budget |

MADS provenance, the completed measurement and its limits are in
[benchmark status](mpjpe-3d-benchmark-status.md). The authors explicitly describe
the sports demonstrations as actions performed without balls or rackets; they
provide synchronized ground truth, rather than monocular pseudo-labels.
[MADS paper](http://visal.cs.cityu.edu.hk/static/pubs/journal/ivc17-mads.pdf),
[release README](https://visal.cs.cityu.edu.hk/static/downloads/MADS/README.txt).

## SportsPose: coverage must be confirmed

The original paper identifies five **sports activities**, which are not five
volleyball skills. Its searchable text names volleyball but does not identify
serving or a five-skill subdivision. Do not infer that volleyball means
serve-only, and do not infer all-five coverage. The authors' 34.5 mm reference
comparison is an assessment of their dataset, not Volleyball Lab's pose-model
error. [Original paper](https://openaccess.thecvf.com/content/CVPR2023W/CVSports/papers/Ingwersen_SportsPose_-_A_Dynamic_3D_Sports_Pose_Dataset_CVPRW_2023_paper.pdf).

Access requires an academic request. A user-authorized request has already been
sent; no additional message was sent during this research. Personal details and
the email receipt remain in ignored outputs.
[Official access instructions](https://christianingwersen.github.io/SportsPose/download.html).

## EgoHumans: a smaller original sequence exists, but prefix access is blocked

The authors distribute the approximately 550 GB collection as individual
subsequence `.tar.gz` archives. Their separately downloadable benchmark contains
tagging, lego assembly and fencing test sequences; its COCO image files are **2D**
keypoints and boxes, so that shortcut does not establish volleyball 3D MPJPE.
[Official download instructions](https://github.com/rawalkhirodkar/egohumans/blob/main/assets/DOWNLOAD.md),
[COCO image annotation documentation](https://github.com/rawalkhirodkar/egohumans/blob/main/assets/COCO_IMAGE.md).

The authors' public volleyball-folder metadata lists eleven archives. The
smallest is `005_volleyball.tar.gz`, **2,197,460,082 bytes**; the first is
14,891,144,176 bytes. Thus the first archive's size must not be presented as the
minimum required for all possible EgoHumans evaluation.
[Official volleyball folder](https://drive.google.com/drive/folders/1vwSF5P4-YAicPIJKfKccKbg8c-unzzrX).

On 5 November, anonymous one-byte range probes succeeded for `005`, `003` and
follow-up `004`. All subsequent exact 2 MiB prefix requests returned Google's HTML message that
too many users had recently viewed or downloaded the file, with a suggestion to
try later. The binary body was not downloaded; no archive members were obtained.
This is a verified access limitation for those three attempts, not proof that all
eleven archives are inaccessible. There is no basis yet to claim that a useful
RGB/reference subset lies near the front of either gzip stream.
The `004` follow-up is recorded in
`outputs/mpjpe-access-20261105/egohumans-004-access-audit.log`.

Inspection scripts, cached author-folder metadata and bounded-request diagnostics
remain in ignored `.codex-tmp` and `outputs/mpjpe-access-20261105`. The normal
download confirmation was followed; no authentication or quota bypass was used.

When public access is restored, first inspect a bounded compressed prefix without
extracting arbitrary paths. A usable subset needs corresponding image frames,
3D pose arrays, actor identities, camera calibration and coordinate transforms.
The original implementation places 3D data under `processed_data/fit_poses3d`
and RGB under `exo/camNN/images`; it loads calibration and transforms from
`colmap/workplace`. Those dependencies matter: isolated mesh-render preview
videos or 2D COCO files cannot replace the original measurements.
[Scene loader](https://github.com/rawalkhirodkar/egohumans/blob/main/egohumans/lib/datasets/ego_exo_scene.py),
[camera loader](https://github.com/rawalkhirodkar/egohumans/blob/main/egohumans/lib/datasets/exo_camera.py).

## Gate before reporting an individual skill result

For each of serving, receiving, setting, spiking and blocking, retain a documented
action interval and actor identity, original synchronized RGB, finite metric 3D
reference joints, supplied camera geometry and a fixed anatomical mapping.
Class labels alone, generic volleyball labels, court coordinates, synthetic 3D,
or Gemini/MediaPipe estimates of the same video cannot establish independent
millimetre ground truth. Any provisional visual action annotation must be named
as provisional and must not be reported as an independent expert label.
