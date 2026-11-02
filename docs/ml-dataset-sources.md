# Online sources for five-skill form training

Investigated 30 October 2026. This is a source and label audit, not an accuracy
report. No general five-skill form-accuracy percentage follows from these sources.

## Most useful dataset lead: SportSkills

The UT Austin project's dataset instructions explicitly offer per-video JSON
annotations identifying positive and negative demonstrations. The project links
a public visualizer and a Box annotation archive. This is a closer match to form
learning than a match-action dataset.
[Project](https://vision.cs.utexas.edu/projects/sportskills/),
[dataset instructions](https://vision.cs.utexas.edu/projects/sportskills/dataset.html),
[annotation archive](https://utexas.box.com/s/op20nszvkf0ij55qz68rlvmd30hucqvp).

The public visualizer HTML embeds a JSON object under `script#data-json`, grouped
by sport. Its volleyball section reports `total_videos: 2808` and exposes 1,000
preview annotation windows: 891 marked `correct`, 109 marked `incorrect`. Each
window supplies a YouTube `video_id`, start/end seconds, narration, a label and
free-form skill tags. These are preview windows, not 1,000 independently verified
athletes or necessarily 1,000 distinct source videos. The extracted subset is
saved locally at `outputs/sportskills-volleyball.json`.
[Public visualizer](https://vision.cs.utexas.edu/projects/sportskills/visualizer.html).

Inspection found substantial noise. Some volleyball-labelled entries concern
general exercises or video games; one window even has a negative duration.
Window `-B3OEckTZPQ`, 102.479–179.84 seconds, is marked correct while the narration
describes good and bad serve tosses. Several incorrect windows explain a
correction rather than isolating an incorrect execution. Therefore the labels
cannot be copied onto all frames or entire videos. They can locate candidate
demonstrations for visual review and subdivision. No separate reuse license was
found on the inspected project/instructions pages; public access alone should
not be recorded as a verified training/distribution license.
[Visualizer containing those annotations](https://vision.cs.utexas.edu/projects/sportskills/visualizer.html).

`outputs/sportskills-five-skill-candidates.json` contains provisional review
windows with original narration and precise timestamps: 4 serve negative / 5
positive, 6 receive negative / 5 positive, 5 set negative / 5 positive, 6 spike
negative / 3 positive, and 0 block negative / 5 positive. Some sources recur
across windows. These counts are review leads, not eligible training counts.
Negative block windows in this preview did not establish observable block-form
faults; defensive positioning and tactical errors were not substituted for them.

## Coach-authored demonstrations

International Volleyball Academy's own publication index lists mistake-focused
episodes for serving, receiving, setting, attacking and blocking. These are useful
for locating explicit demonstrations, but exact faulty/correct subsegments and
visible body landmarks must be checked before extraction. An episode title is
not a per-repetition form label.
[Creator's episode index](https://www.patreon.com/InternationalVolleyballAcademy/sitemap).

| App skill | Candidate creator video | Scope and limitation |
| --- | --- | --- |
| Serving | [IVA EP50: jump-serve errors](https://www.youtube.com/watch?v=3UovLFQvuC0) | Jump serving; confirm compatibility with the app's overhand-serve cues. |
| Receiving | [IVA EP49: reception errors](https://www.youtube.com/watch?v=ouZ6KFa31ow) | Identify individual body-form demonstrations and their context. |
| Setting | [IVA EP53: setting errors](https://www.youtube.com/watch?v=u2OcizHbTgc) | Separate hand technique from legal-contact judgements the pose model cannot observe. |
| Spiking | [IVA EP48: hitting-arm errors](https://www.youtube.com/watch?v=TYlr1S_0t5s) | Creator description explicitly compares execution with errors; requires temporal subdivision. |
| Blocking | [IVA EP64: blocking errors](https://www.youtube.com/watch?v=c0s9_fH8Mrs) | Stronger negative-data lead than tactics-only match examples. |

Elevate Yourself's own site also provides tutorial and tip playlists spanning all
five skills. US Youth Volleyball League lists separate setting, serving,
spiking, blocking and forearm-passing videos. They are useful supplementary
sources; neither page supplies reviewed per-repetition binary form labels or a
dataset license on the inspected page.
[Elevate Yourself](https://www.elevateyourself.org/tutorial-videos.html),
[USYVL](https://usyvl.org/videos/).

## Sources that answer a different question

VNL-STES supplies serve, receive, set, spike and block events with temporal and
spatial annotations, sourced from eight matches. Its labels identify what
happened and where, rather than whether a body-form cue was performed correctly.
Its match-separated splits are useful for event-recognition evaluation, but do
not create correct/faulty form labels. Match footage should not automatically be
assigned `ok`, and a failed point should not automatically be assigned `fault`.
[VNL-STES first-party dataset description](https://hoangqnguyen.github.io/stes/).

Sports Videos in the Wild contains amateur smartphone recordings and volleyball
action annotations such as forearm pass, set and spike. Its documented labels
are sports/actions and spatial/temporal extents, not form correctness.
[SVW first-party description](https://cvlab.cse.msu.edu/project-svw.html).

ExAct has expert commentary and good-execution versus improvement annotations.
Its first-party project page explicitly lists Basketball, Soccer and Bouldering
as its sports coverage. Based on that listed coverage, it is not a verified
source of the missing volleyball form labels; it was not downloaded as volleyball
training data. [ExAct project coverage](https://texaser.github.io/exact_project_page/),
[dataset card](https://huggingface.co/datasets/Alexhimself/ExAct/blob/main/README.md).

## How these leads may be used

Download accessible source material, preserve source IDs and label provenance,
visually isolate the demonstrated movement, and discard clips with unreliable
landmarks. A source-authored wrong/right demonstration can provide provisional
development labels; AI-inferred labels must be identified as weak supervision.
Do not name an AI as a human coach reviewer. Independent athlete/session test
footage and coach-reviewed cue judgements are still needed to establish coaching
accuracy. Splitting cuts from one source into both training and evaluation would
measure source overlap, not generalization.

The existing Android receiving-ready model has a narrower six-feature contract.
These online leads do not validate new weights for that contract or establish
Android integration of the new whole-rep sequence classifiers.
