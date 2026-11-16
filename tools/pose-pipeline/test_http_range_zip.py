import io
import re
import unittest
import zipfile

from http_range_zip import HTTPRangeFile


class Response:
    def __init__(self, status, headers, body):
        self.status_code, self.headers, self.raw = status, headers, body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.raw.close()


class Session:
    def __init__(self, blob):
        self.blob, self.calls = blob, []

    def get(self, url, headers, **kwargs):
        self.calls.append(headers)
        start, end = map(int, re.fullmatch(r'bytes=(\d+)-(\d+)', headers['Range']).groups())
        return Response(206, {'Content-Range': f'bytes {start}-{end}/{len(self.blob)}', 'ETag': 'fixed'},
                        io.BytesIO(self.blob[start:end+1]))

    def close(self):
        pass


class RangeZipTests(unittest.TestCase):
    def test_zip64_member_can_be_read_with_exact_bounded_ranges(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            with archive.open('reference.txt', 'w', force_zip64=True) as member:
                member.write(b'independent annotations')
        session = Session(stream.getvalue())
        with HTTPRangeFile('https://example.org/test.zip', max_bytes=4096, session=session) as remote:
            with zipfile.ZipFile(remote) as archive:
                self.assertEqual(archive.read('reference.txt'), b'independent annotations')
            self.assertLess(remote.transferred_bytes, 4096)
            self.assertEqual(remote.size, len(stream.getvalue()))
        self.assertTrue(all(call['Accept-Encoding'] == 'identity' for call in session.calls))

    def test_full_archive_response_is_refused_without_reading_body(self):
        class ForbiddenBody(io.BytesIO):
            def read(self, *args):
                raise AssertionError('A full response must never be read')
        session = Session(b'abc')
        session.get = lambda *args, **kwargs: Response(200, {}, ForbiddenBody(b'huge archive'))
        with self.assertRaisesRegex(ValueError, 'full download aborted'):
            HTTPRangeFile('https://example.org/test.zip', session=session)

    def test_wrong_content_range_is_refused(self):
        session = Session(b'abc')
        session.get = lambda *args, **kwargs: Response(206, {'Content-Range': 'bytes 1-1/3'}, io.BytesIO(b'b'))
        with self.assertRaisesRegex(ValueError, 'different byte range'):
            HTTPRangeFile('https://example.org/test.zip', session=session)

    def test_budget_checked_before_requesting_more_bytes(self):
        session = Session(b'abc')
        with HTTPRangeFile('https://example.org/test.zip', max_bytes=2, session=session) as remote:
            with self.assertRaisesRegex(ValueError, 'budget'):
                remote.read(3)
            self.assertEqual(len(session.calls), 1)

    def test_changed_archive_is_rejected(self):
        session = Session(b'abc')
        with HTTPRangeFile('https://example.org/test.zip', session=session) as remote:
            session.blob = b'abcd'
            with self.assertRaisesRegex(ValueError, 'length changed'):
                remote.read(1)


if __name__ == '__main__':
    unittest.main()
