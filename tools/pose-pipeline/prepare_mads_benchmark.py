"""Prepare just the pinned MADS volleyball camera-1 benchmark, not the full archive.

The public resource-key links are published by the dataset authors. No login,
credentials, executable code or complete archive download is used. Changed
release bytes fail closed against pinned CRCs, sizes and SHA-256 hashes.
"""
from __future__ import annotations

import argparse
import hashlib
from html import unescape
from html.parser import HTMLParser
import json
from pathlib import Path
import struct
from urllib.parse import parse_qs, urlparse
import zlib

import requests

from http_range_zip import HTTPRangeFile

MANIFEST = Path(__file__).with_name('mads_volleyball_manifest.json')


class DownloadForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.action, self.values, self.active = None, {}, False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'form' and attrs.get('id') == 'download-form':
            self.action, self.active = unescape(attrs.get('action', '')), True
        if tag == 'input' and self.active and attrs.get('name'):
            self.values[attrs['name']] = attrs.get('value', '')

    def handle_endtag(self, tag):
        if tag == 'form':
            self.active = False


def public_download(view):
    parsed = urlparse(view)
    if parsed.scheme != 'https' or parsed.hostname != 'drive.google.com':
        raise ValueError('Expected the authors\' public Drive link')
    parts = parsed.path.split('/')
    if len(parts) != 5 or parts[1:3] != ['file', 'd']:
        raise ValueError('Unexpected public file link')
    identifier = parts[3]
    key = parse_qs(parsed.query)['resourcekey'][0]
    session = requests.Session()
    params = dict(id=identifier, export='download', resourcekey=key)
    url = 'https://drive.google.com/uc'
    try:
        for _ in range(2):
            with session.get(url, params=params, headers={'Range':'bytes=0-0',
                'Accept-Encoding':'identity'}, stream=True, timeout=(15,45)) as response:
                if response.status_code == 206 and response.headers.get('Content-Range','').startswith('bytes 0-0/'):
                    if len(response.raw.read(2)) != 1:
                        raise ValueError('Partial response body length mismatch')
                    return response.url, session
                if 'text/html' not in response.headers.get('Content-Type',''):
                    raise ValueError('Partial access refused; archive body was not read')
                response.raw.decode_content = True
                parser = DownloadForm()
                parser.feed(response.raw.read(512*1024).decode('utf-8', errors='replace'))
                action = urlparse(parser.action or '')
                if action.scheme != 'https' or action.hostname != 'drive.usercontent.google.com':
                    raise ValueError('No anonymous public download form')
                if parser.values.get('id') != identifier:
                    raise ValueError('Download form changed the archive identifier')
                url, params = parser.action, parser.values
        raise ValueError('Public download did not honour byte ranges')
    except Exception:
        session.close()
        raise


def verify(blob, entry):
    if (len(blob) != entry['bytes'] or zlib.crc32(blob) != entry['crc32'] or
            hashlib.sha256(blob).hexdigest() != entry['sha256']):
        raise ValueError('Dataset member failed its pinned size, CRC or SHA-256')


def fetch_member(entry):
    url, session = public_download(entry['public_source'])
    # Payload plus bounded header/name overhead; full-part responses are refused.
    with HTTPRangeFile(url, max_bytes=entry['compressed_bytes']+65536, session=session) as remote:
        if remote.size != entry['part_bytes']:
            raise ValueError('Public archive part size changed')
        remote.seek(entry['offset'])
        header = struct.unpack('<4s5H3I2H', remote.read(30))
        if header[0] != b'PK\x03\x04' or header[2]&1 or header[3] != entry['method']:
            raise ValueError('Unexpected or encrypted archive member')
        name = remote.read(header[9]).decode('utf-8' if header[2]&0x800 else 'cp437')
        if name != entry['name']:
            raise ValueError('Archive member name changed')
        remote.seek(header[10], 1)
        if remote.tell()+entry['compressed_bytes'] > remote.size:
            raise ValueError('Member spans archive parts; no full-download fallback')
        compressed = remote.read(entry['compressed_bytes'])
        if entry['method'] == 8:
            decoder = zlib.decompressobj(-15)
            blob = decoder.decompress(compressed, entry['bytes']+1)
            if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
                raise ValueError('Incomplete, oversized or surplus compressed member')
        elif entry['method'] == 0:
            blob = compressed
        else:
            raise ValueError('Unsupported ZIP compression')
        verify(blob, entry)
        return blob, remote.transferred_bytes+1


def prepare(out, max_transfer_mb=150, references_only=False):
    manifest = json.loads(MANIFEST.read_text())
    entries = [e for e in manifest['members'] if not references_only or e['file'].endswith('.mat')]
    out.mkdir(parents=True, exist_ok=True)
    budget = int(max_transfer_mb*1024*1024)
    if budget < 1:
        raise ValueError('Transfer budget must be positive')
    missing = []
    for entry in entries:
        target = out/entry['file']
        if target.exists():
            verify(target.read_bytes(), entry)
        else:
            missing.append(entry)
    if sum(e['compressed_bytes']+65537 for e in missing) > budget:
        raise ValueError('Selected missing members exceed the transfer budget')
    total, records = 0, []
    for entry in entries:
        target = out/entry['file']
        cached = entry not in missing
        if not cached:
            print(f"Fetching only {entry['file']} ({entry['compressed_bytes']} compressed bytes)", flush=True)
            blob, transferred = fetch_member(entry)
            total += transferred
            target.write_bytes(blob)
        records.append(dict(file=entry['file'], sha256=entry['sha256'], cached=cached,
                            public_source=entry['public_source']))
    receipt = dict(schema_version=1, dataset=manifest['dataset'],
        manifest_sha256=hashlib.sha256(MANIFEST.read_bytes()).hexdigest(),
        transferred_archive_bytes=total, max_transfer_bytes=budget,
        references_only=references_only, camera_selection=manifest['camera_selection'], members=records)
    (out/'preparation-provenance.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(json.dumps(dict(files=len(entries), transferred_archive_bytes=total), indent=2))
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--max-transfer-mb', type=float, default=150)
    parser.add_argument('--references-only', action='store_true', help='Download <0.5 MB of references, without RGB video')
    args = parser.parse_args()
    prepare(args.out, args.max_transfer_mb, args.references_only)
