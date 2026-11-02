import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import extract


class ExtractCliTests(unittest.TestCase):
    def run_batch(self, failure):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); clip=root/'clip.mp4'; clip.write_bytes(b'fixture')
            summary=dict(clip=clip.name,frames=10,shots=1,segments=1,segments_ok=0,
                segments_fault=0,segments_unlabelled=1,segments_too_short=0,clean_frame_rate=1.)
            with patch.object(extract.sys,'argv',['extract.py',str(clip),'--out',str(root/'out')]), \
                 patch.object(extract,'process',side_effect=RuntimeError('fixture decode failure') if failure else None,
                     return_value=summary),contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
                status=extract.main()
            report=json.loads((root/'out/manifest.json').read_text())
            return status,report

    def test_failed_clips_preserve_batch_audit_but_return_failure(self):
        status,report=self.run_batch(True)
        self.assertEqual(status,1)
        self.assertEqual(report['totals']['failed'],1)
        self.assertEqual(report['totals']['clips'],0)
        self.assertIn('fixture decode failure',report['clips'][0]['error'])

    def test_successful_unlabelled_extraction_returns_success_without_invented_labels(self):
        status,report=self.run_batch(False)
        self.assertEqual(status,0)
        self.assertEqual(report['totals']['segments_unlabelled'],1)
        self.assertEqual(report['totals']['segments_ok'],0)
        self.assertEqual(report['totals']['segments_fault'],0)

if __name__=='__main__': unittest.main()
