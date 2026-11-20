"""Prepare a local review worksheet; never fabricate coach labels or identities."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import shutil

from coach_dataset import SKILLS, load_skill


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--extracted', type=Path, nargs='+', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--skills', nargs='+', choices=SKILLS, default=['block'])
    parser.add_argument('--legacy-data', type=Path, help='Optional original segment CSV directory')
    parser.add_argument('--legacy-videos', type=Path, help='Original videos matching legacy CSV stems')
    args = parser.parse_args()
    if bool(args.legacy_data) != bool(args.legacy_videos):
        parser.error('--legacy-data and --legacy-videos must be provided together')
    datasets = {skill: load_skill(args.corpus, skill, {}, 'feature_usable_frac', .5)
                for skill in dict.fromkeys(args.skills)}
    samples = [sample for data in datasets.values() for sample in data.rows]
    if not samples:
        raise ValueError('No eligible demonstrations')
    sources = {}
    for folder in args.extracted:
        provenance = json.loads((folder / 'provenance.json').read_text(encoding='utf-8'))
        for record in provenance['clips']:
            clip = Path(record['clip'])
            if record.get('status') == 'extracted':
                if clip.stem in sources:
                    raise ValueError('Ambiguous original extraction')
                sources[clip.stem] = clip
    timings = {}
    if args.legacy_data:
        for path in args.legacy_data.glob('*_segments.csv'):
            stem = path.name.removesuffix('_segments.csv')
            original = args.legacy_videos / f'{stem}.mp4'
            if not original.is_file():
                continue
            if stem in sources:
                raise ValueError('Ambiguous original extraction')
            sources[stem] = original
            with path.open(encoding='utf-8-sig', newline='') as handle:
                for record in csv.DictReader(handle):
                    start, duration = float(record['start_s']), float(record['duration_s'])
                    if not (0 <= start < float('inf') and 0 < duration < float('inf')):
                        raise ValueError('Invalid original segment timing')
                    timings[f"{stem}:{record['segment']}"] = (start, duration)
    # Resolve every source before creating output; a failure must not leave a
    # partial worksheet that looks ready for review.
    for sample in samples:
        stem = sample['sample_id'].rsplit(':', 1)[0]
        if stem not in sources or not sources[stem].is_file():
            raise ValueError(f"Missing original video for {sample['sample_id']}")
        if args.legacy_videos and sources[stem].parent == args.legacy_videos and sample['sample_id'] not in timings:
            raise ValueError('Missing original segment timing')
    # Refuse overwrites so handwritten reviews cannot be erased by regeneration.
    args.out.mkdir(parents=True, exist_ok=False)
    clips = args.out / 'clips'
    clips.mkdir()
    rows, records = [], []
    for index, sample in enumerate(samples):
        stem = sample['sample_id'].rsplit(':', 1)[0]
        original = sources[stem]
        destination = clips / f'demonstration-{index + 1:02d}.mp4'
        shutil.copyfile(original, destination)
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        start, duration = timings.get(sample['sample_id'], ('', ''))
        rows.append(dict(sample_id=sample['sample_id'], skill=sample['skill'], task_scope=sample['task_scope'],
            observed_arm=sample['observed_arm'], video=str(destination.relative_to(args.out)), clip_sha256=digest,
            segment_start_s=start, segment_duration_s=duration,
            source_group=sample['source_group'], source_sha256=sample['source_sha256'],
            source_family=sample.get('source_family', ''),
            prior_development_exposure='true', review_label='', reviewer_id='', reviewer_qualification='',
            athlete_id='', session_id='', actor_verified='', complete_rep='', complete_arm_cycle='',
            visible_arm_sufficient='', rationale='', correction='', drill=''))
        records.append(dict(sample_id=sample['sample_id'], clip_sha256=digest,
            sequence_sha256=sample['sequence_sha256'], original_clip=str(original)))
    with (args.out / 'review.csv').open('w', encoding='utf-8', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)
    manifest = dict(coach_verified=False, independent_test_eligible=False,
        data_fingerprints={skill: hashlib.sha256(data.X.tobytes() + data.y.tobytes()).hexdigest()
                           for skill, data in datasets.items()}, samples=records)
    if len(datasets) == 1:
        manifest['data_fingerprint'] = next(iter(manifest['data_fingerprints'].values()))
    (args.out / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    (args.out / 'README.md').write_text('''# Independent coaching review worksheet

These demonstrations have already been used for development. Reviewing them
can improve training labels; it cannot create an untouched independent test set.
Original video narration and overlays can reveal the creator's opinion, so this
is not a blinded performance evaluation.

Open each relative video in `clips/` and complete `review.csv`. Review the named
segment when timing is supplied; other actions in the video are context only.
Check whether the proposed segment actually contains a complete action.
Review the named
skill within its recorded task scope and only what is observable. For
`block_visible_arm_reach_hold`, review ONLY the named anatomical arm's reach and
hold, not the hidden arm, footwork, jump height, airborne time, contact or landing.
`unspecified_form` records need an explicit scope decision before reuse; do not
assume a tutorial excerpt covers a complete action. Mark `review_label` as `ok`, `fault`, or
`exclude`. Use `exclude` when the action, context, technique variant or visible
evidence cannot support a justified binary label. Explain each label in rationale.

Record the actual reviewer's identifier and relevant qualification. Do not fill
in an athlete identity or verification flag by guessing. Athlete/session aliases
must consistently identify the same real participant across all videos. Verify
the actor and complete repetition; use true or false. For the arm-only scope,
also verify the complete arm cycle and sufficient arm visibility. Leave fields
that do not apply blank. Describe evidence, an actionable correction and a drill
when supported; do not invent feedback for unobservable mechanics.

The source class labels are intentionally absent from this worksheet. Clips may
still contain revealing creator speech/graphics. Keep immutable sample IDs and
hashes. Conflicting reviews require adjudication and a provenance-preserving
re-extraction; never silently relabel a feature file through its split registry.

A separate cohort of newly recorded, identified athletes and sessions is required
for tuning, calibration and an independently reviewed locked test. Do not reuse
these examples as that test. No identity, coaching review or release approval
has been generated by this tool.
''', encoding='utf-8')
    print(f'Review worksheet: {args.out.resolve()} ({len(rows)} samples)')


if __name__ == '__main__':
    main()
