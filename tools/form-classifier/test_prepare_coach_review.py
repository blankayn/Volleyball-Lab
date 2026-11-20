"""Review packets preserve evidence without manufacturing coach truth."""
import csv
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
import prepare_coach_review as review


class CoachReviewTests(unittest.TestCase):
    def dataset(self, skill, stem):
        return SimpleNamespace(X=np.zeros((1, 32, 1)), y=np.array([0]), rows=[dict(
            sample_id=f'{stem}:0', skill=skill, task_scope='unspecified_form', observed_arm='',
            source_group=stem, source_sha256='source-hash', sequence_sha256='sequence-hash')])

    def test_multi_skill_packet_has_blank_labels_and_preserves_legacy_timing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            extracted, legacy, videos = [root / name for name in ('extracted', 'legacy', 'videos')]
            for path in (extracted, legacy, videos):
                path.mkdir()
            clip = extracted / 'serve.mp4'
            clip.write_bytes(b'public serve fixture')
            (videos / 'pass.mp4').write_bytes(b'public pass fixture')
            (extracted / 'provenance.json').write_text(json.dumps(dict(clips=[dict(
                clip=str(clip), status='extracted')])))
            (legacy / 'pass_segments.csv').write_text('segment,start_s,duration_s\n0,1.5,0.7\n')
            output = root / 'review'
            argv = ['review', '--corpus', str(root), '--extracted', str(extracted),
                    '--skills', 'serve', 'pass', '--legacy-data', str(legacy),
                    '--legacy-videos', str(videos), '--out', str(output)]
            with patch('sys.argv', argv), patch.object(review, 'load_skill',
                    side_effect=lambda directory, skill, *args: self.dataset(skill, skill)):
                review.main()
            with (output / 'review.csv').open(newline='') as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual([row['skill'] for row in rows], ['serve', 'pass'])
            self.assertEqual(rows[1]['segment_start_s'], '1.5')
            for row in rows:
                self.assertEqual(row['review_label'], '')
                self.assertNotIn('label', row)
                self.assertEqual(row['reviewer_id'], '')
                self.assertTrue((output / row['video']).is_file())
            manifest = json.loads((output / 'manifest.json').read_text())
            self.assertFalse(manifest['coach_verified'])
            self.assertFalse(manifest['independent_test_eligible'])
            self.assertEqual(set(manifest['data_fingerprints']), {'serve', 'pass'})
            with patch('sys.argv', argv), patch.object(review, 'load_skill',
                    side_effect=lambda directory, skill, *args: self.dataset(skill, skill)):
                with self.assertRaises(FileExistsError):
                    review.main()

    def test_missing_media_fails_before_creating_a_partial_packet(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'provenance.json').write_text('{"clips": []}')
            output = root / 'review'
            with patch('sys.argv', ['review', '--corpus', str(root), '--extracted', str(root),
                                   '--out', str(output)]), \
                    patch.object(review, 'load_skill', return_value=self.dataset('block', 'missing')):
                with self.assertRaisesRegex(ValueError, 'Missing original video'):
                    review.main()
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
