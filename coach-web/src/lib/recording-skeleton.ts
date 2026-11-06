type Point = { x: number; y: number; confidence: number };
type Frame = { timestampMs: number; points: Point[] };

// MediaPipe landmark indices, matching the Android replay overlay.
const bones = [[11, 12], [11, 23], [12, 24], [23, 24], [11, 13], [13, 15], [12, 14], [14, 16],
  [15, 19], [16, 20], [23, 25], [25, 27], [24, 26], [26, 28], [27, 31], [28, 32]] as const;

export function attachRecordingSkeleton(video: HTMLVideoElement, canvas: HTMLCanvasElement, toggle: HTMLInputElement, data: unknown): (() => void) | null {
  const review = data as { frames?: Frame[]; sampleIntervalMs?: number } | null;
  if (!Array.isArray(review?.frames)) return null;
  const frames = review.frames.filter(frame => frame && Number.isFinite(frame.timestampMs) && Array.isArray(frame.points))
    .sort((a, b) => a.timestampMs - b.timestampMs);
  const ctx = canvas.getContext("2d");
  if (!ctx || !frames.some(frame => frame.points.length === 33)) return null;
  const interval = review.sampleIntervalMs ?? 100;
  const maxGap = Math.min(150, (Number.isFinite(interval) && interval > 0 ? interval : 100) / 2 + 25);
  let animation = 0;
  const visible = (p: Point | undefined): p is Point => !!p && Number.isFinite(p.confidence) && p.confidence >= 0.5 &&
    Number.isFinite(p.x) && p.x >= 0 && p.x <= 1 && Number.isFinite(p.y) && p.y >= 0 && p.y <= 1;
  function draw() {
    if (!ctx) return;
    const width = canvas.clientWidth, height = canvas.clientHeight;
    const density = window.devicePixelRatio || 1;
    const pixelsWide = Math.round(width * density), pixelsHigh = Math.round(height * density);
    if (canvas.width !== pixelsWide) canvas.width = pixelsWide;
    if (canvas.height !== pixelsHigh) canvas.height = pixelsHigh;
    ctx.setTransform(density, 0, 0, density, 0, 0);
    ctx.clearRect(0, 0, width, height);
    if (!toggle.checked || video.seeking || !video.videoWidth || !video.videoHeight) return;
    const time = video.currentTime * 1000;
    let low = 0, high = frames.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (frames[mid]!.timestampMs < time) low = mid + 1;
      else high = mid;
    }
    const before = frames[low - 1], after = frames[low];
    const nearest = before && (!after || time - before.timestampMs < after.timestampMs - time) ? before : after;
    if (!nearest || nearest.points.length !== 33 || Math.abs(nearest.timestampMs - time) > maxGap) return;
    const scale = Math.min(width / video.videoWidth, height / video.videoHeight);
    const fittedWidth = video.videoWidth * scale, fittedHeight = video.videoHeight * scale;
    const x = (p: Point) => (width - fittedWidth) / 2 + p.x * fittedWidth;
    const y = (p: Point) => (height - fittedHeight) / 2 + p.y * fittedHeight;
    ctx.lineCap = "round";
    ctx.beginPath();
    for (const [a, b] of bones) {
      const start = nearest.points[a], end = nearest.points[b];
      if (!visible(start) || !visible(end)) continue;
      ctx.moveTo(x(start), y(start));
      ctx.lineTo(x(end), y(end));
    }
    ctx.strokeStyle = "#101828";
    ctx.lineWidth = 5;
    ctx.stroke();
    ctx.strokeStyle = "#65d9ef";
    ctx.lineWidth = 3;
    ctx.stroke();
    ctx.fillStyle = "#65d9ef";
    nearest.points.slice(11).forEach(point => {
      if (!visible(point)) return;
      ctx.beginPath();
      ctx.arc(x(point), y(point), 3, 0, Math.PI * 2);
      ctx.fill();
    });
  }
  const update = () => {
    window.cancelAnimationFrame(animation);
    draw();
    if (!video.paused && !video.ended) animation = window.requestAnimationFrame(update);
  };
  const events = ["loadedmetadata", "play", "pause", "seeking", "seeked", "ended"];
  events.forEach(event => video.addEventListener(event, update));
  toggle.addEventListener("change", update);
  const resize = new ResizeObserver(update);
  resize.observe(video);
  update();
  return () => {
    window.cancelAnimationFrame(animation);
    resize.disconnect();
    events.forEach(event => video.removeEventListener(event, update));
    toggle.removeEventListener("change", update);
    ctx.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
  };
}
