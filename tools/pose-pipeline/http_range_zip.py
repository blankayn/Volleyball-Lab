"""Bounded, read-only HTTP range access for public research ZIP archives.

The server must honour every exact range; a full archive response is refused
before reading its body. zipfile supplies ZIP64 parsing and member CRC checks.
"""
from __future__ import annotations

import io
import re

import requests


class HTTPRangeFile(io.RawIOBase):
    def __init__(self, url: str, max_bytes: int = 64 * 1024 * 1024, session=None):
        super().__init__()
        if not url.startswith('https://') or max_bytes < 1:
            raise ValueError('Use an HTTPS URL and a positive transfer budget')
        self.url = url
        self.session = session or requests.Session()
        self.position = 0
        self.transferred_bytes = 0
        self.max_bytes = max_bytes
        self.etag = None
        self.ranges = []
        self.size = None
        self._range(0, 0)

    def _range(self, start, end):
        expected = end - start + 1
        if expected < 1 or expected > self.max_bytes - self.transferred_bytes:
            raise ValueError('HTTP range transfer budget exceeded')
        headers = {'Range': f'bytes={start}-{end}', 'Accept-Encoding': 'identity'}
        if self.etag and not self.etag.startswith('W/'):
            headers['If-Range'] = self.etag
        with self.session.get(self.url, headers=headers, stream=True, timeout=(15, 45)) as response:
            if response.status_code != 206:
                raise ValueError(f'Server refused partial access (HTTP {response.status_code}); full download aborted')
            match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', response.headers.get('Content-Range', ''))
            if not match or tuple(map(int, match.groups()[:2])) != (start, end):
                raise ValueError('Server returned a different byte range')
            total = int(match[3])
            if end >= total or (self.size is not None and total != self.size):
                raise ValueError('Archive length changed or range is invalid')
            if response.headers.get('Content-Encoding', 'identity') != 'identity':
                raise ValueError('Unexpected content encoding for byte ranges')
            etag = response.headers.get('ETag')
            if self.etag and etag and etag != self.etag:
                raise ValueError('Archive changed during access')
            blob = response.raw.read(expected + 1)
            self.transferred_bytes += len(blob)
            if len(blob) != expected:
                raise ValueError('Partial response body length mismatch')
            self.size = total
            self.etag = etag or self.etag
            self.ranges.append((start, end))
            return blob

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=io.SEEK_SET):
        if whence not in (io.SEEK_SET, io.SEEK_CUR, io.SEEK_END):
            raise ValueError('Invalid seek mode')
        target = offset + (0 if whence == io.SEEK_SET else self.position if whence == io.SEEK_CUR else self.size)
        if target < 0:
            raise ValueError('Negative seek')
        self.position = target
        return target

    def read(self, size=-1):
        if self.closed:
            raise ValueError('Closed archive')
        remaining = max(0, self.size - self.position)
        count = remaining if size is None or size < 0 else min(size, remaining)
        if count == 0:
            return b''
        blob = self._range(self.position, self.position + count - 1)
        self.position += len(blob)
        return blob

    def close(self):
        if not self.closed:
            session = getattr(self, 'session', None)
            if session is not None:
                session.close()
        super().close()
