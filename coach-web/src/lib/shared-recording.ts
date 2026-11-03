import { onAuthStateChanged } from "firebase/auth";
import { auth } from "./firebase";
import { esc } from "./format";

const DEFAULT_ENDPOINT = "https://volleyball-lab-session-ai.volleyball-lab-session-ai.workers.dev";

export function sharedRecordingPanel(sessionId: string): string {
  return `<section data-recording-panel="${esc(sessionId)}" aria-label="Player recording" style="margin:16px 0"><h4>Player recording</h4><p class="muted">Watch the video the player sent with this session.</p><button class="btn btn-ghost btn-sm" type="button" data-load-recording>Load video</button><p data-recording-status role="status" aria-live="polite"></p><video data-recording-video controls playsinline preload="metadata" hidden style="width:100%;max-height:560px;background:#111;border-radius:12px"></video></section>`;
}

export function wireSharedRecordings(root: HTMLElement): () => void {
  const viewer = auth.currentUser?.uid;
  const urls = new Set<string>();
  const requests = new Set<AbortController>();
  let disposed = false;
  const clear = () => {
    requests.forEach(request => request.abort());
    requests.clear();
    root.querySelectorAll<HTMLVideoElement>("[data-recording-video]").forEach(video => {
      video.pause(); video.removeAttribute("src"); video.load(); video.hidden = true;
    });
    urls.forEach(url => URL.revokeObjectURL(url)); urls.clear();
  };
  const unsubscribe = onAuthStateChanged(auth, user => { if (user?.uid !== viewer) clear(); });
  const onClick = async (event: Event) => {
    const button = (event.target as Element).closest<HTMLButtonElement>("[data-load-recording]");
    const panel = button?.closest<HTMLElement>("[data-recording-panel]");
    if (!button || !panel || button.disabled || disposed) return;
    const status = panel.querySelector<HTMLElement>("[data-recording-status]")!;
    const video = panel.querySelector<HTMLVideoElement>("video")!;
    const controller = new AbortController(); requests.add(controller);
    const timeout = window.setTimeout(() => controller.abort(), 120_000);
    button.disabled = true;
    status.textContent = "Loading private recording…";
    try {
      const user = auth.currentUser;
      if (!viewer || user?.uid !== viewer) throw new Error("Sign in again to view this recording.");
      const token = await user.getIdToken();
      const endpoint = new URL((import.meta.env.VITE_RECORDING_STORAGE_URL as string | undefined)?.trim() || DEFAULT_ENDPOINT);
      if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) throw new Error("Recording storage is not configured.");
      const sessionId = panel.dataset.recordingPanel!;
      if (!/^[A-Za-z0-9_-]{1,128}$/.test(sessionId)) throw new Error("This session is unavailable.");
      if (auth.currentUser?.uid !== viewer) throw new Error("Your account changed. Sign in again.");
      const response = await fetch(`${endpoint.href.replace(/\/$/, "")}/recordings/${sessionId}/video.mp4`, {
        headers: { Authorization: `Bearer ${token}` }, signal: controller.signal, redirect: "error", cache: "no-store",
      });
      if (!response.ok) throw new Error(response.status === 404
        ? "No cloud recording was sent for this session. The saved measurements are available below."
        : response.status === 403 ? "You no longer have access to this recording. Check your active player connection."
          : "The video could not be loaded. Please try again.");
      if (response.headers.get("content-type") !== "video/mp4" || Number(response.headers.get("content-length")) > 90 * 1024 * 1024)
        throw new Error("This recording has an unsupported format or size.");
      const blob = await response.blob();
      if (blob.size < 1 || blob.size > 90 * 1024 * 1024) throw new Error("This recording has an invalid size.");
      if (disposed || !panel.isConnected || auth.currentUser?.uid !== viewer) return;
      const url = URL.createObjectURL(blob); urls.add(url);
      video.src = url; video.hidden = false; button.hidden = true;
      status.textContent = "Private recording ready to play.";
    } catch (error) {
      if (!disposed && panel.isConnected) status.textContent = error instanceof Error && error.name !== "AbortError" ? error.message : "Loading timed out. Please try again.";
    } finally {
      window.clearTimeout(timeout); requests.delete(controller);
      if (!disposed && panel.isConnected) button.disabled = false;
    }
  };
  root.addEventListener("click", onClick);
  return () => { disposed = true; root.removeEventListener("click", onClick); unsubscribe(); clear(); };
}
