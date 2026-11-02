# Full-release annotation follow-up — 31 October 2026

This follow-up expanded the data investigation beyond the public visualizer
preview. It did not establish new coaching accuracy or promote a model.

## Release coverage and provenance

The [SportSkills dataset instructions](https://vision.cs.utexas.edu/projects/sportskills/dataset.html)
link a public Box folder with `annotations.zip` and a smaller expert-feedback
archive. The small archive contained basketball, soccer and rock-climbing
materials, with no volleyball section. It was not treated as volleyball ground
truth.

The large archive's directory contained 199,442 entries, including 1,578 volleyball
JSON files under `volleyball/positive_or_negative_corrected/`. Supported HTTP
byte ranges allowed downloading only the contiguous volleyball section:
6,498,973 bytes rather than the 979,478,075-byte archive. Local member lengths,
available CRCs and JSON syntax were checked, and per-member SHA-256 hashes were
recorded in `outputs/sportskills-full-review/provenance.json`. The partial full
archive download was stopped and removed after this selective retrieval worked.

These files contain shot boundaries, overlapping narration, `vlm_result` and
`parsed_result`. Their judgments are machine-generated source annotations,
**not independently coach-reviewed technique labels**. Overlapping narration can
describe correct and incorrect actions across several shots. Neither a shot
judgment nor a paragraph is automatically a single-repetition training label.
Public availability also does not establish a verified redistribution license.

## Review index

`tools/form-classifier/index_sportskills.py` creates an explicitly unapproved
review queue. It preserves original source IDs and boundaries, rejects invalid
time ranges, marks conflicting judgments as mixed, and never majority-votes
them into a training label. Skill keywords propose candidates; they cannot
establish which skill is actually demonstrated.

The recorded run found 13,584 skill-mentioned review windows. Counts overlap
between skills and are not independent repetitions:

| Suggested skill | Source says correct | Source says incorrect | Mixed or unknown |
|---|---:|---:|---:|
| Serving | 1,122 | 172 | 2,586 |
| Receiving | 1,008 | 173 | 2,265 |
| Setting | 1,174 | 133 | 3,576 |
| Spiking | 1,434 | 219 | 3,379 |
| Blocking | 944 | 103 | 3,680 |

The 103 blocking-negative leads span 49 source videos. They include tactics,
non-volleyball exercises, video games, static explanations and footage whose
body framing cannot support the current full-feature sequence model. They are
review leads, not 103 usable faulty block executions.

Reproduce the index after obtaining the public volleyball JSON files:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/index_sportskills.py `
  --annotations outputs/sportskills-full-review/annotations `
  --out outputs/sportskills-full-review/review-leads.json
```

## Additional source reviews

Timestamped contact sheets and original metadata are saved under
`outputs/online-form-data/review` and `outputs/online-form-data/source`.
The following decisions concern eligibility for the existing full-body,
whole-repetition binary model, not the usefulness of the creator's instruction.

| Source | Observation / decision |
|---|---|
| [Jiri Popelka: blocking for shorter players](https://www.youtube.com/watch?v=pV2dPb-L8c4) | Talking-head advice, stills and distant crowded match examples; no isolated clearly labelled faulty full-body rep identified. |
| [Wicked Volleyball: block and transition](https://www.youtube.com/watch?v=HRyDkzLgQNc) | Real complete drill actions, but no isolated faulty execution is labelled. Do not assume every drill rep is correct or invent a negative label. |
| [Blocking mistakes start with footwork](https://www.youtube.com/watch?v=fO2Df2sZzV4) | Explicit footwork mistakes, but portrait closeups omit upper or lower body during the action. Cannot supply all required features. |
| [Three keys to blocking](https://www.youtube.com/watch?v=U8p2Ask-9es) | Demonstrator stands on a box and explains hand position; labelled windows contain static arm examples, not complete block jumps. |
| [Blocking when you are not tall](https://www.youtube.com/watch?v=ZjXGana1-30) | Negative windows cover static arm explanations and a brief different-camera drill. No complete creator-labelled faulty block isolated. |
| [Beach blocking tutorial](https://www.youtube.com/watch?v=Zrr1po_FpLU) | Net-distance and reach explanations are static poses, not a faulty block repetition. |
| [Better at Beach blocking instruction](https://www.youtube.com/watch?v=8CSQARq6JC8) | Negative windows near 384–392 seconds show distant match/drill examples amid instructional edits. Faulty body mechanics of a single actor are not established. |
| [Beach blocking do's and don'ts](https://www.youtube.com/watch?v=MOILXZAGTbo) | The negative window near 59–62 seconds cuts/crops the body; the surrounding instruction is primarily waist-up explanation. Insufficient complete-body evidence. |
| [Czech Volleyball instructional exercises](https://www.youtube.com/watch?v=uC-wRMJXOIg) | Around 130–140 seconds, multiple athletes demonstrate instructional sidesteps; a named actor's fault is not established. More seriously, the nominal blocking-negative windows near 486–497 seconds show diving/rolling drills, not blocking. Narration/skill mismatch prevents their use. |

## Split protection improved

New online extractions embed the original video's SHA-256 in every sequence
bundle. The dataset loader carries it into connected identity groups and rejects
registry attempts to replace a recorded hash. Different feature sequences or
renamed source IDs from the same original video must therefore stay together.
Existing bundles without this metadata still use their original source groups
and optional registry hashes; this change does not retroactively verify old
identity metadata or unseen players.

All 51 form-pipeline regression tests passed, including new source-identity and
annotation-review tests. No Android source changed in this follow-up; the prior
344-test Android/build/lint verification remains the applicable check.

## Training status

The four-skill experimental comparison remains the recorded model experiment.
No reviewed new faulty blocking repetition has yet been admitted, so the
blocking binary model remains untrained. Review leads were not converted into
verified truth, duplicated into fake repetitions, or used to claim an accuracy
increase. Independent player evaluation, calibrated confidence and actual MPJPE
still require the data described in [AI Coach status](ai-coach-system-status.md).

## Subsequent remaining-candidate pass

All 49 videos in the blocking-negative queue now have a recorded narration
triage decision. Twelve of those sources received selected-window visual review;
two additional search sources outside that queue were also visually reviewed
(14 sources total). The other 37 received narration-only triage, principally
because the referenced subject was another skill, tactics/rules, video games,
fitness or an instructional drill without an isolated faulty execution. This is
not a claim that every frame of those 49 videos was inspected or that entire
videos contain no useful material. Exact decisions and candidate timestamps are
in `outputs/sportskills-full-review/block-triage-ledger.json`.

The five additional selected-window reviews were:

| Source | Decision |
|---|---|
| [Blocking timing](https://www.youtube.com/watch?v=iT0SbW2gNDQ) | Static timing explanation and arm gestures; no complete labelled faulty jump isolated. |
| [Spike/transition tips](https://www.youtube.com/watch?v=vVRXPxYsZR4) | One creator-labelled ineffective retreat and one proposed corrected retreat isolated as a matched pair. This is specifically block-to-attack retreat, not general block form. Pose quality excludes the faulty example. |
| [Attacking mistakes](https://www.youtube.com/watch?v=AUbO9piiztE) | Reviewed early windows show attacking instruction, talking-head explanations and cutaways; the word “block” in narration does not identify a faulty blocking demonstration. |
| [Blocking progression](https://www.youtube.com/watch?v=ij8ym8zzHQc) | Floor/bench preparation, crowded progression drills and match examples; no labelled complete faulty block isolated. |
| [Leaving the net / late run-up](https://www.youtube.com/watch?v=p4ClvISseq8) | Creator-marked late run-up attempt extracted with an athlete crop; it fails the unchanged pose-quality threshold. Tactical context also limits what a skeleton-only form classifier could establish. |

### Actual extraction and admission results

The required13 profile still requires visibility at least 0.5 for every input
joint, finite image/world coordinates, image bounds and a valid skeleton. A
segment needs at least 50% reliable original frames. No threshold was reduced.

| Candidate | Label scope | Reliable-frame fraction | Admission |
|---|---|---:|---|
| `vVRXPxYsZR4`, 118.5–122.8s | Ineffective block-to-attack retreat | 0.203125 | Excluded; left elbow/wrist frequently unobservable |
| `vVRXPxYsZR4`, 167.1–171.3s | Proposed corrected block-to-attack retreat | 0.555556 | Quality passes for this narrow task; no eligible paired negative |
| `p4ClvISseq8`, 130.9–133.8s | Creator-marked incorrect run-up block attempt | 0.454545 | Excluded; actor/context review is also required before any future admission |

Original source identity and task scope are embedded in the generated bundles.
The two isolated dataset audits correctly return `needs_data`; neither contains
both eligible classes. Reports are `outputs/ai-coach-transition-audit/report.json`
and `outputs/ai-coach-run-up-audit/report.json`. **No new model was fitted in this
pass**, and the general blocking model remains untrained.

### Preventing narrow labels from becoming general claims

New selections may specify `task_scope`, such as `block_to_attack_retreat`.
The extractor preserves this field; ingestion rejects mixed scopes and registry
attempts to rewrite them. Export metadata records the scope. Locked evaluation
now requires one explicit scope, in addition to the existing source/player,
review and partition checks. Legacy unscoped data remain usable for provisional
development, with scope recorded as `unspecified_form`.

The final form-pipeline regression suite passed **55 tests**, including scope
preservation, mixed-task rejection, invalid scopes, source hashes and locked
evaluation isolation. Android source was unchanged in this pass.

A later [visible-arm experiment](ml-blocking-visible-arm.md) trained four narrow
blocking arm-reach/hold candidates from a different, explicitly scoped feature
contract. That does not turn the failed full-body/transition candidates above
into eligible samples or establish whole-skill blocking accuracy.
