import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zlib

import prepare_mads_benchmark as module


class MadsPreparationTests(unittest.TestCase):
    def fixture(self, root):
        blob = b'independent reference bytes'
        entry = dict(file='truth.mat', bytes=len(blob), compressed_bytes=len(blob),
                     crc32=zlib.crc32(blob), sha256=hashlib.sha256(blob).hexdigest(),
                     public_source='https://drive.google.com/file/d/public/view?resourcekey=public')
        manifest = root/'manifest.json'
        manifest.write_text(json.dumps(dict(dataset='fixture',camera_selection='fixed',members=[entry])))
        return blob, entry, manifest

    def test_insufficient_budget_makes_no_download(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            _, _, manifest = self.fixture(root)
            with patch.object(module,'MANIFEST',manifest), patch.object(module,'fetch_member') as fetch:
                with self.assertRaisesRegex(ValueError, 'budget'):
                    module.prepare(root/'out', .001)
                fetch.assert_not_called()

    def test_changed_cached_reference_is_rejected_without_replacing_it(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            _, entry, manifest = self.fixture(root)
            out = root/'out'
            out.mkdir()
            target = out/entry['file']
            target.write_bytes(b'wrong release')
            with patch.object(module,'MANIFEST',manifest), patch.object(module,'fetch_member') as fetch:
                with self.assertRaisesRegex(ValueError, 'SHA-256'):
                    module.prepare(out)
                fetch.assert_not_called()
            self.assertEqual(target.read_bytes(), b'wrong release')

    def test_cached_verified_reference_has_zero_archive_transfer(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            blob, entry, manifest = self.fixture(root)
            out = root/'out'
            out.mkdir()
            (out/entry['file']).write_bytes(blob)
            with patch.object(module,'MANIFEST',manifest), patch.object(module,'fetch_member') as fetch:
                receipt = module.prepare(out, max_transfer_mb=.001, references_only=True)
                fetch.assert_not_called()
            self.assertEqual(receipt['transferred_archive_bytes'], 0)
            self.assertTrue(receipt['members'][0]['cached'])

    def test_pinned_digest_rejects_corruption_even_when_crc_matches(self):
        with tempfile.TemporaryDirectory() as temp:
            blob, entry, _ = self.fixture(Path(temp))
            entry['sha256'] = '0'*64
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                module.verify(blob, entry)


if __name__ == '__main__':
    unittest.main()
