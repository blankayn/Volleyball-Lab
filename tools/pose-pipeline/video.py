"""Shot segmentation and badge reading - the two things that make coaching clips usable.

Short-form coaching clips are edited. A sixteen-second reel routinely holds five
camera setups, and a landmark sequence run straight across a cut contains a jump
no human body could produce. Any temporal model trained on that learns the edit.
`find_shots` exists to cut the sequence where the editor did.

Many of those clips also grade themselves, stamping a green tick on the correct
demonstration and a red cross on the wrong one. That is a free label for the
expensive half of the dataset - faulty form is the class you cannot get from
match footage, because professionals rarely supply it. `read_badge` looks for
those marks so the labelling is a review step rather than a transcription step.

Both are heuristics with tunable thresholds, and both are meant to be checked.
Run with --overlay and look at the output before trusting a batch.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

# --- Decoding -------------------------------------------------------------

def _find(tool: str) -> str | None:
    """Locates ffmpeg/ffprobe on PATH, or where winget put it.

    winget installs to a versioned directory and only amends PATH for new shells,
    so a freshly installed ffmpeg is routinely present and invisible at once.
    """
    found = shutil.which(tool)
    if found:
        return found
    packages = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet" / "Packages"
    if packages.is_dir():
        for candidate in packages.rglob(f"{tool}.exe"):
            return str(candidate)
    return None


def probe_codec(path: Path) -> str:
    """Best-effort codec name, for turning a decode failure into a useful message."""
    ffprobe = _find("ffprobe")
    if not ffprobe:
        return "unknown"
    try:
        result = subprocess.run(
            [ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
             "stream=codec_name,codec_tag_string", "-of", "csv=p=0", str(path)],
            capture_output=True, text=True, timeout=30,
        )
        return result.stdout.strip() or "unknown"
    except Exception:
        return "unknown"


def open_video(path: Path) -> tuple[cv2.VideoCapture, Path | None]:
    """Opens a clip, transcoding first if OpenCV cannot decode it.

    Downloads arrive in whatever the platform served, and OpenCV's bundled FFmpeg
    is narrower than the standalone one - HEVC, AV1 and VP9 clips open fine, read
    zero frames, and report no error worth acting on. Transcoding those to H.264
    costs a few seconds and makes the batch workflow survive mixed sources.

    Returns the capture and, when one was made, the temporary file to delete.
    Raises with the codec named when nothing can decode it: TikTok in particular
    serves ByteDance's proprietary `bvc2`, which no standard decoder handles, and
    the only fix is to fetch the clip again in H.264.
    """
    capture = cv2.VideoCapture(str(path))
    if capture.isOpened():
        ok, _ = capture.read()
        if ok:
            capture.set(cv2.CAP_PROP_POS_FRAMES, 0)
            return capture, None
    capture.release()

    ffmpeg = _find("ffmpeg")
    codec = probe_codec(path)
    if not ffmpeg:
        raise RuntimeError(
            f"cannot decode (codec: {codec}) and ffmpeg is not installed to transcode it"
        )

    temporary = Path(tempfile.gettempdir()) / f"posepipe_{os.getpid()}_{path.stem}.mp4"
    result = subprocess.run(
        [ffmpeg, "-y", "-v", "error", "-i", str(path),
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", str(temporary)],
        capture_output=True, text=True,
    )

    if result.returncode == 0 and temporary.exists():
        transcoded = cv2.VideoCapture(str(temporary))
        if transcoded.isOpened():
            ok, _ = transcoded.read()
            if ok:
                transcoded.set(cv2.CAP_PROP_POS_FRAMES, 0)
                return transcoded, temporary
        transcoded.release()

    temporary.unlink(missing_ok=True)
    raise RuntimeError(
        f"no decoder for this file (codec: {codec}); ffmpeg could not convert it either. "
        f"If this came from TikTok it is ByteDance's proprietary codec - re-download "
        f"forcing H.264, e.g. yt-dlp -f \"bv*[vcodec^=avc1]+ba/b[vcodec^=avc1]\""
    )


# --- Shot detection -------------------------------------------------------

# Frames are compared as colour histograms at this working size. Small is the
# point: at thumbnail scale a person crossing the frame barely moves the
# histogram, while a cut to a new camera changes it completely.
HIST_SIZE = (64, 36)
HIST_BINS = (8, 8, 8)

# A cut has to clear both bars. The absolute floor stops a static talking-head
# shot from dissolving into hundreds of one-frame shots on sensor noise; the
# relative one stops a genuinely busy clip - a spike, a dive - from reading as a
# cut every time the athlete moves fast.
CUT_ABSOLUTE = 0.30
CUT_RELATIVE = 4.0

# Shorter than this and it is a flash frame or a dissolve, not a demonstration.
MIN_SHOT_FRAMES = 8


@dataclass
class Shot:
    index: int
    start: int
    end: int  # exclusive

    @property
    def length(self) -> int:
        return self.end - self.start


def frame_signature(frame: np.ndarray) -> np.ndarray:
    """A normalised HSV histogram, cheap enough to compute on every frame."""
    small = cv2.resize(frame, HIST_SIZE, interpolation=cv2.INTER_AREA)
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1, 2], None, HIST_BINS, [0, 180, 0, 256, 0, 256])
    cv2.normalize(hist, hist)
    return hist.flatten()


def find_shots(signatures: list[np.ndarray]) -> list[Shot]:
    """Splits a clip at its cuts.

    The relative test compares each distance against the median of the whole
    clip rather than a local window. A local window sounds better and is worse
    here: consecutive cuts are common in these edits, and a window centred on one
    cut is contaminated by the next, which raises the bar exactly where it should
    not be raised.
    """
    if len(signatures) < 2:
        return [Shot(0, 0, len(signatures))]

    distances = np.array([
        float(cv2.compareHist(
            signatures[i].astype(np.float32),
            signatures[i + 1].astype(np.float32),
            cv2.HISTCMP_BHATTACHARYYA,
        ))
        for i in range(len(signatures) - 1)
    ])

    baseline = float(np.median(distances)) or 1e-6
    is_cut = (distances > CUT_ABSOLUTE) & (distances > CUT_RELATIVE * baseline)

    boundaries = [0] + [int(i) + 1 for i in np.flatnonzero(is_cut)] + [len(signatures)]

    shots: list[Shot] = []
    for start, end in zip(boundaries, boundaries[1:]):
        if end - start < MIN_SHOT_FRAMES and shots:
            # Fold a runt into its predecessor rather than dropping it, so frame
            # indices stay contiguous and nothing silently disappears.
            shots[-1] = Shot(shots[-1].index, shots[-1].start, end)
        else:
            shots.append(Shot(len(shots), start, end))

    return shots or [Shot(0, 0, len(signatures))]


# --- Badge detection ------------------------------------------------------

# Emoji badges are flat, saturated, bright, and - the discriminator that ends up
# doing most of the work - deliberately placed where a viewer will look. Every
# threshold below is calibrated against measured blobs rather than guessed; run
# debug_badge.py on a frame to re-derive them for footage that misbehaves.
BADGE_MIN_AREA_FRAC = 0.002

# And a ceiling, which turned out to matter more than the floor. A badge is a
# graphic laid over the frame and measures around 2% of it; a court, a wall, or a
# floor measures 7-9%. Without this, an orange playing surface is a large, solid,
# well-saturated blob that satisfies every shape test there is.
BADGE_MAX_AREA_FRAC = 0.05

# A bare cross fills under half its bounding box (measured: 0.469), while a tick
# on a rounded square fills nearly all of it (0.943). The bar has to clear the
# cross, which puts it well below where a naive "badges are solid" reading would
# leave it. What it must still exclude is court lines: a thin diagonal stripe has
# a near-square bounding box too, but its extent is under 0.15, so 0.35 sits in
# the gap between the two with room on both sides.
BADGE_MIN_EXTENT = 0.35

BADGE_ASPECT = (0.45, 2.2)
BADGE_MAX_VALUE_STD = 50.0   # flat fill; folds in a shirt exceed this

# The floor for building the colour mask. Kept permissive so a badge's whole
# shape survives into the contour rather than being eaten at the antialiased
# edges - the strict test is on the blob's mean, below.
BADGE_MIN_SATURATION = 130

# And the test that actually rejects skin. A face and a forearm sit in the same
# hue band as a red cross and are otherwise blob-shaped, compact, and centre
# frame; on a clip where the athlete stands close to the camera this fired on
# every frame and produced six confidently mislabelled "fault" segments.
#
# Measured mean saturation: red cross 248, green tick 182-195, skin 139-152, a
# painted gym mural 180. The tick's own value moves by 13 points between frames
# as compression softens it, so the bar has to sit below its floor, not below its
# best reading - 185 looked safe against one measurement and silently dropped a
# real demonstration. 165 clears skin by 13 and the tick by 17.
#
# The mural at 180 would pass this. It is rejected by BADGE_REGION instead, which
# is the right division of labour: saturation separates ink from flesh, position
# separates ink from scenery.
BADGE_MIN_MEAN_SATURATION = 165

# Position is no longer a filter, and the history is worth keeping. This started
# as a tight lower-centre box, because in the first sample clip every badge sat
# at x 0.50-0.52, y 0.72-0.77 and the box was the only thing separating a real
# tick from a green foliage mural painted on the gym wall.
#
# The next clip stamped its ticks at the TOP of frame, at y 0.19-0.22. The gate
# rejected them - and, worse, an orange court in the same frames satisfied the
# red tests, so the clip came back labelled `fault` on segments a human can see
# are marked with a green tick. Silently inverted labels, from a threshold tuned
# on one video.
#
# Hue and the area ceiling do that work now, and they do it on properties of the
# graphic itself rather than on where one editor chose to put it. Kept as a
# constant so a future clip with badges in a known corner can still narrow it.
BADGE_REGION = (0.0, 1.0, 0.0, 1.0)

# Hue bands narrowed onto the emoji themselves. Measured across two unrelated
# clips the green tick sits at hue 59.6-59.7 every time - it is the same Unicode
# asset, so it is the same pixels. That consistency is worth exploiting: a band
# of 50-70 keeps every real tick while excluding the yellower greens that gyms
# are actually painted with (the mural measured 41.4).
GREEN_HSV = ((50, BADGE_MIN_SATURATION, 90), (70, 255, 255))

# Red wraps the hue origin, so it needs two bands. The low one is deliberately
# narrow: an orange sports floor sits at hue 8-12 and a band reaching to 10 swept
# the entire court into the mask. The cross's own pixels cluster at the top of
# the scale, so it loses nothing by starting the low band at 5.
RED_HSV_LOW = ((0, BADGE_MIN_SATURATION, 90), (5, 255, 255))
RED_HSV_HIGH = ((160, BADGE_MIN_SATURATION, 90), (180, 255, 255))


def _count_badges(hsv: np.ndarray, mask: np.ndarray, frame_area: float) -> int:
    height, width = mask.shape
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    found = 0
    for contour in contours:
        area = cv2.contourArea(contour)
        if not (BADGE_MIN_AREA_FRAC <= area / frame_area <= BADGE_MAX_AREA_FRAC):
            continue
        x, y, w, h = cv2.boundingRect(contour)
        if w == 0 or h == 0:
            continue
        if not (BADGE_ASPECT[0] <= w / h <= BADGE_ASPECT[1]):
            continue
        if area / float(w * h) < BADGE_MIN_EXTENT:
            continue

        centre_x, centre_y = (x + w / 2) / width, (y + h / 2) / height
        left, right, top, bottom = BADGE_REGION
        if not (left <= centre_x <= right and top <= centre_y <= bottom):
            continue

        blob = np.zeros(mask.shape, np.uint8)
        cv2.drawContours(blob, [contour], -1, 255, cv2.FILLED)
        inside = blob > 0
        values = hsv[:, :, 2][inside]
        if values.size and float(np.std(values)) > BADGE_MAX_VALUE_STD:
            continue
        saturations = hsv[:, :, 1][inside]
        if saturations.size and float(np.mean(saturations)) < BADGE_MIN_MEAN_SATURATION:
            continue
        found += 1

    return found


def read_badge(frame: np.ndarray) -> str | None:
    """Returns 'ok' for a green tick, 'fault' for a red cross, None for neither.

    Exactly one badge, or nothing. Two or more means the frame is a comparison
    edit - either a tick beside a cross, or a split-screen stacking the same
    athlete at several moments with a mark on each panel. Both are common in
    short-form coaching content and both are untrainable: the pose detector
    returns one skeleton for the whole frame, so there is no way to know which
    panel it came from or which mark applies to it.

    Returning None there is the point. A frame the pipeline cannot interpret must
    not become a labelled training row, and a wrong label costs more than a
    missing one - it is confidently wrong, and nothing downstream will catch it.
    """
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    frame_area = float(frame.shape[0] * frame.shape[1])

    green = cv2.inRange(hsv, np.array(GREEN_HSV[0]), np.array(GREEN_HSV[1]))
    red = cv2.bitwise_or(
        cv2.inRange(hsv, np.array(RED_HSV_LOW[0]), np.array(RED_HSV_LOW[1])),
        cv2.inRange(hsv, np.array(RED_HSV_HIGH[0]), np.array(RED_HSV_HIGH[1])),
    )

    green_count = _count_badges(hsv, green, frame_area)
    red_count = _count_badges(hsv, red, frame_area)

    if green_count + red_count != 1:
        return None
    return "ok" if green_count else "fault"


# --- Segmentation ---------------------------------------------------------

# A badge flickers: motion blur or a limb crossing the mark drops a frame or two
# out of an otherwise solid run. Bridging gaps up to this length stops one
# demonstration fragmenting into five.
BADGE_MAX_GAP = 6

# And a run this short is the flicker rather than the mark - too brief to be a
# demonstration anyone was meant to read.
BADGE_MIN_RUN = 6

# Below this a segment cannot hold a rep. They are still emitted, flagged, so
# that "we dropped 40% of the clip" is visible rather than inferred.
MIN_SEGMENT_FRAMES = 10


@dataclass
class Segment:
    index: int
    start: int  # inclusive
    end: int    # exclusive
    label: str  # 'ok' | 'fault' | 'unlabelled'
    shot: int

    @property
    def length(self) -> int:
        return self.end - self.start


def smooth_badges(badges: list[str | None]) -> list[str | None]:
    """Bridges flicker gaps and discards runs too short to be a real mark."""
    smoothed = list(badges)

    # Pass one: fill short None-gaps bounded by the same label on both sides.
    i = 0
    while i < len(smoothed):
        if smoothed[i] is not None:
            i += 1
            continue
        j = i
        while j < len(smoothed) and smoothed[j] is None:
            j += 1
        before = smoothed[i - 1] if i > 0 else None
        after = smoothed[j] if j < len(smoothed) else None
        if before is not None and before == after and (j - i) <= BADGE_MAX_GAP:
            for k in range(i, j):
                smoothed[k] = before
        i = j

    # Pass two: drop labelled runs too short to be a deliberate mark.
    i = 0
    while i < len(smoothed):
        if smoothed[i] is None:
            i += 1
            continue
        j = i
        while j < len(smoothed) and smoothed[j] == smoothed[i]:
            j += 1
        if (j - i) < BADGE_MIN_RUN:
            for k in range(i, j):
                smoothed[k] = None
        i = j

    return smoothed


def find_segments(signatures: list[np.ndarray], badges: list[str | None]) -> tuple[list[Shot], list[Segment]]:
    """Partitions a clip into stretches that are one demonstration each.

    Two signals, and the second one matters more than it first appears. Cuts are
    obvious but coarse: a single six-second shot in the sample reel holds both a
    correct demo and a faulty one, so segmenting on cuts alone produces a stretch
    that is simultaneously labelled right and wrong and is therefore unusable.

    The badge is the finer signal, and it is the *editor's own* segmentation -
    they stamped the mark on precisely the stretch they were grading. Cutting
    where the badge changes gives segments whose label is homogeneous by
    construction, so no voting is needed and no segment comes out ambiguous.

    Cuts are still used, because a badge can persist across one and because
    footage carrying no marks at all still needs to be split somewhere.
    """
    shots = find_shots(signatures)
    smoothed = smooth_badges(badges)

    boundaries = {0, len(signatures)}
    boundaries.update(shot.start for shot in shots)
    boundaries.update(
        i for i in range(1, len(smoothed)) if smoothed[i] != smoothed[i - 1]
    )

    shot_of_frame = np.zeros(max(len(signatures), 1), dtype=int)
    for shot in shots:
        shot_of_frame[shot.start:shot.end] = shot.index

    ordered = sorted(boundaries)
    segments = [
        Segment(index=n, start=start, end=end,
                label=smoothed[start] or "unlabelled",
                shot=int(shot_of_frame[start]))
        for n, (start, end) in enumerate(zip(ordered, ordered[1:]))
        if end > start
    ]

    return shots, segments
