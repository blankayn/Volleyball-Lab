import { deleteDoc, doc, getDoc } from "firebase/firestore";
import { auth, db } from "./firebase";
import { isPro } from "./plan";
import { esc } from "./format";
import { momentLabel, recordingMoment, type NoteMoment } from "./note-moment";
import type { CoachNote } from "./cloud";

/* Photo and recording-moment attachments on coach feedback. A photo is one small
   JPEG in noteMedia/{noteId}, readable only by the note's coach and player. */

const MAX_PHOTO = 300_000;
const MAX_FILE = 15 * 1024 * 1024;
const MAX_SIDE = 1280;

export function validNotePhoto(value: unknown): value is string {
  return typeof value === "string" && value.length <= MAX_PHOTO && /^data:image\/jpeg;base64,[A-Za-z0-9+/]+=*$/.test(value);
}

/** Shrinks a picked image to a small JPEG. Redrawing drops the original's metadata, including any location. */
export async function encodeNotePhoto(file: File): Promise<string> {
  if (!file.type.startsWith("image/")) throw new Error("Choose a photo.");
  if (file.size > MAX_FILE) throw new Error("Choose a photo smaller than 15 MB.");
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" }).catch(() => { throw new Error("That photo could not be opened."); });
  try {
    let scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
    for (let attempt = 0; attempt < 3; attempt++, scale *= 0.7) {
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      const context = canvas.getContext("2d");
      if (!context) throw new Error("That photo could not be prepared.");
      context.fillStyle = "#fff";
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      for (const quality of [0.82, 0.7, 0.58, 0.46]) {
        const value = canvas.toDataURL("image/jpeg", quality);
        if (validNotePhoto(value)) return value;
      }
    }
    throw new Error("That photo is too detailed to attach. Try another one.");
  } finally { bitmap.close(); }
}

// ---- Draft ------------------------------------------------------------------

export type NoteDraft = { moment: NoteMoment | null; photo: string | null; message: string; preparing: boolean };
const drafts = new Map<string, NoteDraft>();
const draftOf = (sessionId: string): NoteDraft => drafts.get(sessionId) ?? { moment: null, photo: null, message: "", preparing: false };
export const draftAttachments = draftOf;
export function clearDraftAttachments(sessionId?: string): void { if (sessionId === undefined) drafts.clear(); else drafts.delete(sessionId); }

export function attachmentComposer(sessionId: string): string {
  return `<div class="note-attach" data-note-attach="${esc(sessionId)}">
    <div class="note-attach-actions"><button type="button" class="btn btn-ghost btn-sm" data-attach-moment>Link this moment of the video</button>
      ${isPro() ? `<label class="btn btn-ghost btn-sm"><input type="file" accept="image/*" data-attach-photo class="note-attach-file" /><span>Add a photo</span></label>` : `<a class="plan-hint" href="#/app/account">Photos are part of Pro</a>`}</div>
    <div data-attach-preview></div><p class="muted" data-attach-message role="status" aria-live="polite"></p></div>`;
}

// ---- Sent notes ---------------------------------------------------------------

export function noteAttachments(note: CoachNote, watchable: boolean): string {
  const moment = note.moment
    ? watchable
      ? `<button type="button" class="note-moment" data-note-moment="${note.moment.videoMs}">▶ ${esc(momentLabel(note.moment))}</button>`
      : `<span class="note-moment">${esc(momentLabel(note.moment))}</span>`
    : "";
  return moment + (note.hasPhoto ? `<div class="note-photo" data-note-photo="${esc(note.id)}"></div>` : "");
}

export function wireNoteAttachments(root: HTMLElement): () => void {
  const viewer = auth.currentUser?.uid;
  const loaded = new Map<string, Promise<string | null>>();
  let disposed = false;
  const current = () => !disposed && auth.currentUser?.uid === viewer;

  const paintDraft = (host: HTMLElement) => {
    const draft = draftOf(host.dataset.noteAttach!);
    const preview = host.querySelector<HTMLElement>("[data-attach-preview]")!;
    preview.replaceChildren();
    if (draft.moment) {
      const chip = document.createElement("button");
      chip.type = "button"; chip.className = "note-moment"; chip.dataset.dropMoment = "";
      chip.textContent = `${momentLabel(draft.moment)} ×`;
      chip.setAttribute("aria-label", `Remove the linked moment, ${momentLabel(draft.moment)}`);
      preview.append(chip);
    }
    if (draft.photo) {
      const img = document.createElement("img");
      img.src = draft.photo; img.alt = "Photo to send"; img.className = "note-photo-img";
      const remove = document.createElement("button");
      remove.type = "button"; remove.className = "btn btn-quiet btn-sm"; remove.dataset.dropPhoto = ""; remove.textContent = "Remove photo";
      const consent = document.createElement("p");
      consent.className = "muted";
      consent.textContent = "Only this player and you can see it. Send photos only of people who have agreed to it, and a guardian for anyone under 18.";
      preview.append(img, remove, consent);
    }
    host.querySelector<HTMLElement>("[data-attach-message]")!.textContent = draft.preparing ? "Preparing photo…" : draft.message;
  };
  const setDraft = (sessionId: string, change: Partial<NoteDraft>) => {
    drafts.set(sessionId, { ...draftOf(sessionId), message: "", ...change });
    root.querySelectorAll<HTMLElement>("[data-note-attach]").forEach((host) => { if (host.dataset.noteAttach === sessionId) paintDraft(host); });
  };

  const paintPhoto = (host: HTMLElement) => {
    const id = host.dataset.notePhoto!;
    if (host.dataset.painted || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) return;
    host.dataset.painted = "true";
    host.textContent = "Loading photo…";
    const request = loaded.get(id) ?? getDoc(doc(db, "noteMedia", id)).then((snapshot) => {
      const photo = snapshot.data()?.["photo"];
      return validNotePhoto(photo) ? photo : null;
    });
    loaded.set(id, request);
    request.then((photo) => {
      if (!current() || !host.isConnected) return;
      host.replaceChildren();
      if (!photo) { host.textContent = "Photo removed"; return; }
      const open = document.createElement("button");
      open.type = "button"; open.className = "note-photo-open"; open.dataset.openPhoto = ""; open.setAttribute("aria-label", "Enlarge photo");
      const img = document.createElement("img");
      img.src = photo; img.alt = "Photo sent with this feedback"; img.className = "note-photo-img";
      open.append(img);
      const remove = document.createElement("button");
      remove.type = "button"; remove.className = "btn btn-quiet btn-sm"; remove.dataset.removePhoto = id; remove.textContent = "Remove photo";
      host.append(open, remove);
    }).catch(() => {
      loaded.delete(id);
      if (!current() || !host.isConnected) return;
      host.innerHTML = `The photo could not be loaded. <button type="button" class="btn btn-quiet btn-sm" data-retry-photo>Retry</button>`;
    });
  };

  const onClick = (event: Event) => {
    const target = event.target as Element;
    const host = target.closest<HTMLElement>("[data-note-attach]");
    const details = target.closest<HTMLElement>("[data-session-details]");
    const video = details?.querySelector<HTMLVideoElement>("[data-recording-video]") ?? null;
    if (host && target.closest("[data-attach-moment]")) {
      const moment = recordingMoment(video);
      if (moment) { video!.pause(); setDraft(host.dataset.noteAttach!, { moment }); }
      else setDraft(host.dataset.noteAttach!, { message: "Load the player's video above and pause where you want to comment." });
    } else if (host && target.closest("[data-drop-moment]")) setDraft(host.dataset.noteAttach!, { moment: null });
    else if (host && target.closest("[data-drop-photo]")) setDraft(host.dataset.noteAttach!, { photo: null });
    const seek = target.closest<HTMLElement>("[data-note-moment]");
    if (seek && details) {
      if (video && recordingMoment(video)) {
        video.dispatchEvent(new CustomEvent("repseek"));
        video.pause(); video.currentTime = Number(seek.dataset.noteMoment) / 1000;
        video.scrollIntoView({ block: "center", behavior: "auto" });
      } else {
        const status = details.querySelector<HTMLElement>("[data-recording-status]");
        if (status) { status.textContent = "Load the video, then choose this moment again."; status.scrollIntoView({ block: "center", behavior: "auto" }); }
      }
    }
    const retry = target.closest("[data-retry-photo]")?.closest<HTMLElement>("[data-note-photo]");
    if (retry) { delete retry.dataset.painted; paintPhoto(retry); }
    const image = target.closest("[data-open-photo]")?.querySelector("img");
    if (image) {
      const dialog = document.createElement("dialog");
      dialog.className = "note-photo-dialog";
      const full = document.createElement("img");
      full.src = image.src; full.alt = image.alt;
      const close = document.createElement("button");
      close.type = "button"; close.className = "btn btn-primary btn-sm"; close.textContent = "Close photo";
      close.onclick = () => dialog.close();
      dialog.append(full, close);
      dialog.addEventListener("close", () => dialog.remove());
      root.append(dialog); dialog.showModal();
    }
    const remove = target.closest<HTMLButtonElement>("[data-remove-photo]");
    if (remove && window.confirm("Remove this photo? The player will no longer see it. Your written feedback stays. This cannot be undone.")) {
      const id = remove.dataset.removePhoto!;
      remove.disabled = true;
      deleteDoc(doc(db, "noteMedia", id)).then(() => {
        loaded.set(id, Promise.resolve(null));
        root.querySelectorAll<HTMLElement>("[data-note-photo]").forEach((item) => { if (item.dataset.notePhoto === id) { delete item.dataset.painted; paintPhoto(item); } });
      }).catch(() => { remove.disabled = false; remove.textContent = "Could not remove. Try again."; });
    }
  };
  const onChange = (event: Event) => {
    const input = event.target as HTMLInputElement;
    const host = input.closest<HTMLElement>("[data-note-attach]");
    if (!host || input.dataset.attachPhoto === undefined) return;
    const sessionId = host.dataset.noteAttach!;
    const file = input.files?.[0];
    input.value = "";
    if (!file || draftOf(sessionId).preparing) return;
    setDraft(sessionId, { preparing: true });
    encodeNotePhoto(file).then((photo) => { if (current()) setDraft(sessionId, { photo, preparing: false }); },
      (error: unknown) => { if (current()) setDraft(sessionId, { preparing: false, message: error instanceof Error ? error.message : "That photo could not be attached." }); });
  };
  // Session details mount their content when opened.
  const paint = () => {
    root.querySelectorAll<HTMLElement>("[data-note-attach]").forEach((host) => { if (!host.dataset.painted) { host.dataset.painted = "true"; paintDraft(host); } });
    root.querySelectorAll<HTMLElement>("[data-note-photo]").forEach(paintPhoto);
  };
  const observer = new MutationObserver(paint);
  observer.observe(root, { childList: true, subtree: true });
  root.addEventListener("click", onClick);
  root.addEventListener("change", onChange);
  paint();
  return () => {
    disposed = true; observer.disconnect();
    root.removeEventListener("click", onClick); root.removeEventListener("change", onChange);
    loaded.clear();
  };
}
