import type { RepReport } from "./rep-notes";

/* A moment in a session's own recording that a coach's note points at.
   Mirrors Android CoachNote.momentLabel and VideoReview.repAt. */

export type NoteMoment = { videoMs: number; repNumber: number | null };
export const MAX_VIDEO_MS = 3_600_000;

const reportsByVideo = new WeakMap<object, RepReport[]>();
export function setRecordingReports(video: object, reports: RepReport[]): void { reportsByVideo.set(video, reports); }

export function momentAt(timeMs: number, reports: RepReport[]): NoteMoment {
  const videoMs = Math.min(MAX_VIDEO_MS, Math.max(0, Math.round(timeMs)));
  const rep = reports.find((r) => videoMs >= r.startMs && videoMs <= r.endMs);
  return { videoMs, repNumber: rep && rep.repNumber >= 1 && rep.repNumber <= 1000 ? rep.repNumber : null };
}

/** The moment showing in a loaded recording, or null when none is loaded. */
export function recordingMoment(video: HTMLVideoElement | null): NoteMoment | null {
  if (!video || video.hidden || !video.src || !Number.isFinite(video.currentTime)) return null;
  return momentAt(video.currentTime * 1000, reportsByVideo.get(video) ?? []);
}

export function momentLabel(moment: NoteMoment): string {
  const seconds = Math.floor(moment.videoMs / 1000);
  const time = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
  return moment.repNumber === null ? `At ${time}` : `Rep ${moment.repNumber} · ${time}`;
}

/** Unusable attachment values drop the attachment, never the written note. */
export function momentOf(data: Record<string, unknown>): NoteMoment | null {
  const { sessionId, videoMs, repNumber } = data;
  if (typeof sessionId !== "string" || typeof videoMs !== "number" || !Number.isInteger(videoMs) || videoMs < 0 || videoMs > MAX_VIDEO_MS) return null;
  return { videoMs, repNumber: typeof repNumber === "number" && Number.isInteger(repNumber) && repNumber >= 1 && repNumber <= 1000 ? repNumber : null };
}
