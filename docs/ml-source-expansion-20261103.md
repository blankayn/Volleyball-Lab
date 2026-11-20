# Source expansion and Setting refit — 3 November 2026

The 80% objective remains unverified for all five whole-skill models. This pass
adds actual source investigation and a consistently re-extracted Setting corpus;
it does not manufacture independent labels or rename development as testing.

## Source review

| Source | Selected-window findings and disposition |
|---|---|
| [KoKo Volley overhand serving drills](https://www.youtube.com/watch?v=-B3OEckTZPQ) | Inspected 95–185s and narration. Good/bad toss demonstrations omit the lower legs and are partial toss explanations rather than complete serves. Excluded from whole-skill training. |
| [Better at Beach serving lesson](https://www.youtube.com/watch?v=0QvfXe0m7fE) | Inspected 0–120s and subsequent contact-feedback narration. The contrasted fault concerns hand contact and spin, which the current body-pose feature contract cannot establish. Not admitted as a skeleton-only form label. This is not an exhaustive review of the full video. |
| [IVA setting footwork, episode 51](https://www.youtube.com/watch?v=2usad5j12Os) | Inspected early examples and 194–259s, including creator-marked faulty finishes around 248–258s. Earlier activities include heading/catching drills rather than sets. The later demonstrations contain editing/slow-motion confounds and possible overlap with the existing IVA collection. Pending further review; not admitted as new independent faulty athletes. |
| [John Speraw setting technique](https://www.youtube.com/watch?v=9Q9mm3dXFM0) | Inspected the short lesson and dense action frames. A fixed actor crop excludes the foreground coach. One provisional instructional set at 50.20–52.25s passes unchanged required13 quality at 0.93548. Actor landmark overlays confirm the demonstrator is selected. A second proposed interval was corrected after boundary review and still fails admission. |

These are AI reviews of source-authored instruction. They are not independently
coach-verified labels. The first subtitle request for the Speraw lesson received
HTTP 429; no additional caption request was made. The accessible public video
was downloaded separately without subtitles. No private student media were shared.

## Consistent data and split protection

The five previous Setting selections and two proposed new intervals were
re-extracted with one pose asset and the current one-pose, 0.65 detector settings
and confirmed-player tracker. This avoids mixing legacy detector configurations
with the new source. Required13 visibility/image-bound requirements remain
unchanged. Six sequences pass: **two faulty and four correct**, in three linked
groups. Two other generated sequences are excluded. Counts are segments, not
independently verified athletes or actions.

Sources and exact extraction are recorded in
`outputs/online-form-data/extracted-set-runtime-expanded-20261103/provenance.json`.
The new corpus and admission audit are under
`outputs/online-form-data/corpus-set-runtime-expanded-20261103`.

`source_family` is an optional conservative publication-group alias, distinct
from athlete/session identity. It links related episodes without pretending
that an unknown person has been identified. The original source ID/hash remain
intact; embedded families cannot be replaced by the split registry. New IVA
extractions carry `iva-public-coaching-collection`. Loader, connected-group,
registry-template and review-packet paths preserve it. **91 regression tests
passed**, including prevention of cross-family split leakage and registry changes.

## Training and remaining evidence

The expanded Setting development run compares linear, TCN, LSTM and GRU at
learning rate 0.001, 30 epochs, training-only augmentation and class weighting.
Artifacts are in `outputs/ai-coach-setting-expanded-20261103`. This refit is not
directly comparable to the old five-example run because both the source corpus
and detector configuration changed. No production candidate is selected.

All four fits and native exports completed. Held-out coverage is 2/6 examples,
both labelled correct: linear agrees on 0/2; TCN, LSTM and GRU each agree on 1/2.
Fault detection is unmeasured. All four exported policies remain uncalibrated
and abstaining. Native probes show no argmax flips and maximum probability
difference 0.002312 or less. These are host export checks, not a phone test for
the newly fitted Setting weights. The 80% target has not been achieved.

Faulty examples still come from one collection. Holding it out removes the
fault class from training, so that fold must remain skipped. Additional positive
sources cannot fix the missing independent faulty-player evaluation. The next
data priority is complete, observable faulty actions from other coaching
collections and new independently reviewed players for all five skills.
