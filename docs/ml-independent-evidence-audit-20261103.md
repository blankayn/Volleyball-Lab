# Independent evidence audit — 3 November 2026

The active objective remains at least 80% verified performance for each of
Serving, Receiving, Setting, Spiking and Blocking. No current report proves it.
This pass inspected the latest five-skill comparison and the subsequent expanded
Setting report, plus their actual dataset manifests. It did not launch repeated
fits on unchanged examples.

| Skill | Eligible faulty / correct segments | Registered rows with all required verification and partition evidence | Independently measured accuracy |
|---|---:|---:|---|
| Serving | 2 / 2 | 0 | Unavailable |
| Receiving | 4 / 6 | 0 | Unavailable |
| Setting | 2 / 4 | 0 | Unavailable |
| Spiking | 2 / 2 | 0 | Unavailable |
| Blocking | 0 / 2 | 0 | Unavailable |

Zero registered verified rows means that the actual manifests do not establish
the required reviewed label, actor, complete-action and athlete flags, known
athlete identities and train/tune/calibration/test assignments. It does not mean
zero classifier accuracy. The generated evidence record includes report and
manifest SHA-256 hashes:
`outputs/five-skill-independent-evidence-audit-20261103.json`.

## Further research checked

[Holatka et al.'s setting study](https://sig-iss.work/percomworkshops2019/papers/p567-holatka.pdf)
collected setting movements from 35 people with a Myo IMU/EMG armband and used
video to derive labels. Its trained inputs and experimental results cannot be
transferred to this camera-only model. No downloadable labelled camera corpus
was identified in the paper or the accompanying source search during this pass.
The study's label design is a useful reference, not newly available app training
data or validation evidence.

[Sports-QA's authors](https://link.springer.com/article/10.1007/s11263-026-02734-1)
describe team/action outcomes, causes and relationships for sports VideoQA.
Their volleyball outcome/cause annotations are not individual posture or
joint-mechanics correctness labels. A blocked spike or a ball landing out cannot
automatically label the player's whole technique faulty. This dataset was not
substituted for expert mechanics review or downloaded as such a training set.

## Required next evidence

The prepared local review packets can be completed by a qualified coach, with
genuine player/session identities and explicit observable-action labels. Prior
development clips remain exposed examples even after review. New recordings
from additional identified players must supply both classes for every skill and
separate development, calibration and locked-test cohorts. The existing local
student video alone cannot establish unseen-player performance.

Coach Yose's linked profile and the empty review worksheets are not completed
expert assessments. The last authenticated app check found zero coach notes.
Permission to upload the private Blocking recording remains pending; no video
has been shared by these training or research passes. Until actual review and
new cohort evidence arrive, the 80% objective remains active and unachieved.

## Current review handoff

`outputs/five-skill-current-coach-review-20261103` is a new, local 26-example
packet matching the latest model corpora: 4 Serving, 10 Receiving, 6 Setting,
4 Spiking and 2 Blocking. All five feature-data fingerprints match the actual
training reports. All 26 copied-video hashes were checked; reviewer identities,
qualifications, labels and athlete IDs remain blank. Source-family links and
legacy segment timings are preserved. The packet has no model predictions or
app scores; original tutorial narration/graphics may still reveal creator
opinions. It is exposed development material, not an untouched test set. It has
not been uploaded or sent to a reviewer. The prior packet remains intact.
