import { doc, onSnapshot } from "firebase/firestore";
import { onAuthStateChanged } from "firebase/auth";
import { auth, db } from "./firebase";
import { validPhoto, validBanner } from "./preferences";

export function wireProfileMedia(root: HTMLElement): () => void {
  const watchers = new Map<string, () => void>();
  const values = new Map<string, { photo: string; banner: string | undefined }>();
  const viewer = auth.currentUser?.uid;
  let disposed = false;
  function paint(uid: string) {
    const media = values.get(uid);
    root.querySelectorAll<HTMLElement>("[data-profile-avatar]").forEach(element => {
      if (element.dataset.profileAvatar !== uid) return;
      if (!element.dataset.initials) element.dataset.initials = element.textContent ?? "";
      element.replaceChildren();
      if (media?.photo) {
        const img = document.createElement("img"); img.src = media.photo; img.alt = "Profile photo";
        img.addEventListener("error", () => { if (element.isConnected && img.parentElement === element) element.textContent = element.dataset.initials ?? ""; });
        img.style.cssText = "width:100%;height:100%;object-fit:cover;border-radius:inherit"; element.append(img);
      } else element.textContent = element.dataset.initials;
    });
    root.querySelectorAll<HTMLElement>("[data-profile-banner]").forEach(element => {
      if (element.dataset.profileBanner !== uid) return;
      if (element.hasAttribute("data-profile-optional-banner")) element.hidden = !media?.banner;
      const image = element.querySelector<HTMLImageElement>("[data-cover-image]");
      if (image && media?.banner !== undefined) image.src = media.banner || image.dataset.defaultCover || "";
      if (!image) { element.style.backgroundImage = media?.banner ? `linear-gradient(#0006,#0006),url("${media.banner}")` : ""; }
    });
  }
  const initialize = () => {
    if (disposed || auth.currentUser?.uid !== viewer) return;
    root.querySelectorAll<HTMLElement>("[data-profile-avatar], [data-profile-banner]").forEach(element => {
      const uid = element.dataset.profileAvatar || element.dataset.profileBanner;
      if (!uid || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) return;
      if (!watchers.has(uid)) watchers.set(uid, onSnapshot(doc(db, "profileMedia", uid), snapshot => {
        const data = snapshot.data();
        values.set(uid, { photo: validPhoto(data?.photo) ? data.photo : "", banner: validBanner(data?.banner) ? data.banner : undefined });
        if (!disposed && auth.currentUser?.uid === viewer) paint(uid);
      }, () => { values.delete(uid); if (!disposed) paint(uid); }));
      if (values.has(uid)) paint(uid);
    });
  };
  const clear = () => {
    watchers.forEach(stop => stop()); watchers.clear(); values.clear();
    root.querySelectorAll<HTMLImageElement>("[data-profile-avatar] img").forEach(img => img.remove());
    root.querySelectorAll<HTMLElement>("[data-profile-banner]").forEach(element => {
      element.style.backgroundImage = "";
      if (element.hasAttribute("data-profile-optional-banner")) element.hidden = true;
      const image = element.querySelector<HTMLImageElement>("[data-cover-image]");
      if (image) image.src = image.dataset.defaultCover || "";
    });
  };
  const unsubscribe = onAuthStateChanged(auth, user => { if (user?.uid !== viewer) clear(); });
  // Lazy session templates are mounted as their details open.
  const observer = new MutationObserver(records => { if (records.some(record => [...record.addedNodes].some(node => node instanceof HTMLElement && (node.matches("[data-profile-avatar], [data-profile-banner]") || node.querySelector("[data-profile-avatar], [data-profile-banner]"))))) initialize(); });
  observer.observe(root, { childList: true, subtree: true });
  initialize();
  return () => { disposed = true; observer.disconnect(); unsubscribe(); clear(); };
}
