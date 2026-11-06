"""Prepare a small, predeclared research-only MPI-INF-3DHP benchmark subset.

Uses official public HTTPS downloads, exact byte ranges and a transfer cap.
No full archive fallback, random frame selection or selection by model success.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

import h5py
import numpy as np
import requests

from http_range_zip import HTTPRangeFile

BASE = 'https://vcai.mpi-inf.mpg.de/3dhp-dataset/'


def select_frames(valid, count):
    valid = np.asarray(valid).reshape(-1)
    if not np.isfinite(valid).all() or not np.isin(valid, [0, 1]).all():
        raise ValueError('Official valid-frame flags must be finite binary values')
    indices = np.flatnonzero(valid)
    if not 1 <= count <= len(indices):
        raise ValueError('Requested frame count exceeds valid annotations')
    return indices[np.rint(np.linspace(0, len(indices)-1, count)).astype(int)].tolist()


def prepare(out, sequence='TS5', count=20, max_transfer_mb=64):
    if sequence not in {f'TS{i}' for i in range(1,7)} or count < 1 or max_transfer_mb < 1:
        raise ValueError('Invalid sequence, sample count or download budget')
    out.mkdir(parents=True, exist_ok=True)
    images = out/'images'
    images.mkdir(exist_ok=True)
    # The small official toolkit contains the licence; never execute its scripts.
    with requests.get(BASE+'mpi_inf_3dhp.zip',stream=True,timeout=(15,45)) as response:
        response.raise_for_status()
        bootstrap = response.raw.read(1024*1024+1)
        if len(bootstrap)>1024*1024:
            raise ValueError('Unexpected toolkit size; download stopped')
    with zipfile.ZipFile(io.BytesIO(bootstrap)) as toolkit:
        for member,target in [('license.txt','license.txt'),('README.txt','dataset-README.txt')]:
            (out/target).write_bytes(toolkit.read('mpi_inf_3dhp/'+member))
    records=[]
    with HTTPRangeFile(BASE+'mpi_inf_3dhp_test_set.zip',
                       max_bytes=max_transfer_mb*1024*1024) as remote, zipfile.ZipFile(remote) as archive:
        def obtain(member, path, maximum):
            info=archive.getinfo(member)
            if info.file_size>maximum:
                raise ValueError(f'Unexpected member size: {member}')
            if path.exists():
                import zlib
                blob=path.read_bytes()
                if len(blob)!=info.file_size or zlib.crc32(blob)!=info.CRC:
                    raise ValueError(f'Existing data differ; refusing overwrite: {path}')
            else:
                blob=archive.read(info)
                path.write_bytes(blob)
            records.append(dict(archive_member=member,file=str(path),bytes=len(blob),
                sha256=hashlib.sha256(blob).hexdigest(),archive_crc32=info.CRC))
            return blob
        obtain('mpi_inf_3dhp_test_set/README.txt',out/'test-README.txt',64*1024)
        annotation=out/'annotations.mat'
        annotation_bytes=obtain(f'mpi_inf_3dhp_test_set/{sequence}/annot_data.mat',annotation,16*1024*1024)
        with h5py.File(annotation) as reference:
            indices=select_frames(reference['valid_frame'][:],count)
        selection=dict(sequence=sequence,selection=f'{count} uniformly spaced official valid frames; chosen before pose inference',
            zero_based_frame_indices=indices,annotation_sha256=hashlib.sha256(annotation_bytes).hexdigest(),
            task_scope='general_3d_pose_benchmark_not_volleyball',uses_universal_skeleton=False)
        selection_path=out/'selection.json'
        if selection_path.exists() and json.loads(selection_path.read_bytes())!=selection:
            raise ValueError('Refusing to change an existing benchmark selection')
        selection_path.write_text(json.dumps(selection,indent=2))
        for index in indices:
            obtain(f'mpi_inf_3dhp_test_set/{sequence}/imageSequence/img_{index+1:06d}.jpg',
                   images/f'{sequence}_{index+1:06d}.jpg',4*1024*1024)
            print(f'Prepared {sequence} frame {index+1}',flush=True)
        provenance=dict(source=BASE,archive_url=remote.url,archive_bytes=remote.size,
            archive_etag=remote.etag,downloaded_bytes_this_run=remote.transferred_bytes+len(bootstrap),
            maximum_archive_transfer_bytes=remote.max_bytes,toolkit_sha256=hashlib.sha256(bootstrap).hexdigest(),
            intended_use='non-commercial academic benchmark only; no redistribution',members=records)
        (out/'download-provenance.json').write_text(json.dumps(provenance,indent=2))
    print(f"Prepared {count} frames; transferred {provenance['downloaded_bytes_this_run']} bytes this run")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--sequence',choices=[f'TS{i}' for i in range(1,7)],default='TS5')
    parser.add_argument('--frames',type=int,default=20)
    parser.add_argument('--max-transfer-mb',type=int,default=64)
    args=parser.parse_args()
    prepare(args.out,args.sequence,args.frames,args.max_transfer_mb)


if __name__=='__main__':
    main()
