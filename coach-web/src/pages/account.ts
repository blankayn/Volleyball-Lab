import { getProfile, getRoster, saveProfile } from "../lib/cloud";
import { profileHeader } from "../lib/profile-header";
import { sendEmailVerification } from "firebase/auth";
import { auth } from "../lib/firebase";
import { loadPreferences, savePreferences } from "../lib/account-settings";
import { esc, initials } from "../lib/format";
import { icon, type IconName } from "../lib/icons";
import { getPreferences, updatePreferences, validPhoto, validBanner, type PortalPreferences } from "../lib/preferences";

function heading(id: string, number: string, name: string, symbol: IconName): string {
  return `<div class="settings-card-heading"><span class="settings-symbol">${icon(symbol)}</span><div><span class="settings-index">${number}</span><h2 id="${id}">${name}</h2></div></div>`;
}

function toggle(key: keyof PortalPreferences, title: string, description: string, checked: boolean): string {
  return `<label class="settings-toggle-row" for="setting-${key}"><span><strong>${title}</strong><small>${description}</small></span><span class="settings-switch"><input id="setting-${key}" type="checkbox" data-preference="${key}"${checked ? " checked" : ""} /><span aria-hidden="true"></span></span></label>`;
}

export async function renderAccount(coachUid: string): Promise<string> {
  const profile = await getProfile(coachUid);
  const prefs = getPreferences();
  const specOptions = ["AllAround", "Serving", "Receiving", "Setting", "Spiking", "Blocking"]
    .map((name) => `<option value="${name}"${(profile.specialisation ?? "AllAround") === name ? " selected" : ""}>${name === "AllAround" ? "All-around" : name}</option>`).join("");
  return `<div class="account-settings">
    <div class="settings-intro"><div><p class="eyebrow">Make it yours</p><h2>Your account &amp; preferences</h2><p class="muted">Your coaching profile, your workspace, your way.</p></div><span class="settings-save-status" id="preferences-status" role="status" aria-live="polite"></span></div>
    <nav class="settings-nav" aria-label="Account sections"><a href="#profile-settings">Profile &amp; team</a><a href="#appearance-settings">Appearance</a><a href="#accessibility-settings">Accessibility</a><a href="#notification-settings">Notifications</a></nav>
    <div class="settings-columns">
      <section class="settings-card settings-profile" aria-labelledby="profile-settings">
        ${heading("profile-settings", "01", "Profile &amp; team", "user")}
        <form id="profile-form">
          ${profileHeader({ name: profile.name || "Coach", subtitle: profile.teamName || "Your coaching profile", uid: coachUid, photo: prefs.photo, banner: prefs.banner, editable: true })}
          <input class="visually-hidden" id="profile-photo" type="file" accept="image/jpeg,image/png,image/webp" />
          <input class="visually-hidden" id="profile-banner" type="file" accept="image/jpeg,image/png,image/webp" />
          <div class="profile-media-toolbar"><button class="photo-remove" type="button" id="remove-profile-photo"${prefs.photo ? "" : " hidden"}>Remove profile picture</button><button class="photo-remove" type="button" id="remove-profile-banner"${prefs.banner ? "" : " hidden"}>Remove cover photo</button></div>
          <div id="photo-status" role="status" aria-live="polite"></div><div id="banner-status" role="status" aria-live="polite"></div>
          <div class="form-grid"><div class="field"><label for="p-name">Display name</label><input id="p-name" required maxlength="60" value="${esc(profile.name)}" autocomplete="name" /></div><div class="field"><label for="p-team">Team name</label><input id="p-team" maxlength="48" value="${esc(profile.teamName)}" /></div></div>
          <div class="field"><label for="p-org">School or club</label><input id="p-org" maxlength="48" value="${esc(profile.organisation)}" autocomplete="organization" /></div>
          <div class="form-grid"><div class="field"><label for="p-spec">Coaching specialization</label><select id="p-spec">${specOptions}</select></div><div class="field"><label for="p-years">Years coaching</label><input id="p-years" type="number" min="0" max="70" step="1" value="${profile.yearsExperience ?? ""}" /></div></div>
          <div class="field"><label for="p-certifications">Certifications</label><textarea id="p-certifications" maxlength="1200" rows="3" placeholder="Coaching qualifications, courses or accreditations">${esc(prefs.certifications)}</textarea></div>
          <div class="field"><label for="p-licence">Certification / licence number</label><input id="p-licence" maxlength="40" value="${esc(profile.licenceNumber)}" /></div>
          <div class="settings-form-footer"><button class="btn btn-primary" type="submit">Save profile ${icon("check")}</button><span id="profile-saved" role="status" aria-live="polite"></span></div>
        </form>
      </section>
      <div class="settings-stack">
        <section class="settings-card" aria-labelledby="appearance-settings">
          ${heading("appearance-settings", "02", "Appearance", "palette")}
          <fieldset class="settings-fieldset"><legend>Theme</legend><div class="theme-options">${([ ["light", "Light", "sun"], ["dark", "Dark", "moon"], ["system", "System", "monitor"] ] as const).map(([value, label, symbol]) => `<label class="theme-option"><input type="radio" name="theme" value="${value}" data-preference="theme"${prefs.theme === value ? " checked" : ""} /><span>${icon(symbol)}${label}</span></label>`).join("")}</div></fieldset>
          <fieldset class="settings-fieldset"><legend>Interface color</legend><div class="accent-options">${([ ["rust", "Terracotta"], ["forest", "Forest"], ["plum", "Plum"] ] as const).map(([value, label]) => `<label class="accent-option accent-${value}"><input type="radio" name="accent" value="${value}" data-preference="accent"${prefs.accent === value ? " checked" : ""} /><span><i aria-hidden="true"></i>${label}</span></label>`).join("")}</div></fieldset>
          <div class="settings-select-row"><label for="setting-density">Layout density</label><select id="setting-density" data-preference="density"><option value="comfortable"${prefs.density === "comfortable" ? " selected" : ""}>Comfortable</option><option value="compact"${prefs.density === "compact" ? " selected" : ""}>Compact</option></select></div>
        </section>
        <section class="settings-card" aria-labelledby="accessibility-settings">
          ${heading("accessibility-settings", "03", "Accessibility", "accessibility")}
          <div class="settings-select-row"><label for="setting-textSize">Text size</label><select id="setting-textSize" data-preference="textSize"><option value="normal"${prefs.textSize === "normal" ? " selected" : ""}>Default · 100%</option><option value="large"${prefs.textSize === "large" ? " selected" : ""}>Large · 110%</option><option value="larger"${prefs.textSize === "larger" ? " selected" : ""}>Larger · 120%</option></select></div>
          ${toggle("highContrast", "High contrast", "Stronger text and clearer borders", prefs.highContrast)}
          ${toggle("reduceMotion", "Reduce animations", "Keep movement to a minimum", prefs.reduceMotion)}
          ${toggle("enhancedFocus", "Enhanced keyboard focus", "A clearer outline as you use Tab", prefs.enhancedFocus)}
        </section>
        <section class="settings-card" aria-labelledby="notification-settings">
          ${heading("notification-settings", "04", "Notifications", "bell")}
          ${toggle("submissionAlerts", "Player submissions", "New sessions ready for your review", prefs.submissionAlerts)}
          ${toggle("deadlineReminders", "Assignment deadlines", "Drills due within the next 24 hours", prefs.deadlineReminders)}
          ${toggle("trainingNotifications", "Training sessions", "New training shared with your team", prefs.trainingNotifications)}
          ${toggle("emailNotifications", "Email notifications", "Send enabled alerts to your account email", prefs.emailNotifications)}
          ${auth.currentUser?.emailVerified ? "" : '<div class="settings-email-verification"><button class="btn btn-ghost btn-sm" type="button" id="verify-email">Verify email</button><span id="email-verification-status" role="status" aria-live="polite">Verify your email to receive alerts.</span></div>'}
        </section>
      </div>
    </div>
    <section class="settings-signout"><div><strong>Finished for now?</strong><span class="muted">You can pick up where you left off.</span></div><button class="btn btn-ghost btn-sm" data-action="sign-out">Sign out</button></section>
  </div>`;
}

async function photoData(file: File, banner = false): Promise<string> {
  if (!["image/jpeg", "image/png", "image/webp"].includes(file.type)) throw new Error("Choose a JPG, PNG or WebP image.");
  if (file.size > 5 * 1024 * 1024) throw new Error("Choose a photo smaller than 5 MB.");
  const bitmap = await createImageBitmap(file);
  try {
    const canvas = document.createElement("canvas");
    canvas.width = banner ? 1024 : 256; canvas.height = banner ? 360 : 256;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Your browser could not process this image.");
    context.fillStyle = "#fff";
    context.fillRect(0, 0, canvas.width, canvas.height);
    const scale = Math.max(canvas.width / bitmap.width, canvas.height / bitmap.height);
    const width = canvas.width / scale, height = canvas.height / scale;
    context.drawImage(bitmap, (bitmap.width - width) / 2, (bitmap.height - height) / 2, width, height, 0, 0, canvas.width, canvas.height);
    let quality = .84; let result = canvas.toDataURL("image/jpeg", quality);
    const valid = banner ? validBanner : validPhoto;
    while (!valid(result) && quality > .34) { quality -= .1; result = canvas.toDataURL("image/jpeg", quality); }
    if (!valid(result)) throw new Error("Choose a simpler or smaller image.");
    return result;
  } finally { bitmap.close(); }
}

export function wireAccount(root: HTMLElement, coachUid: string, refreshIdentity: () => void): () => void {
  const account = root.querySelector<HTMLElement>(".account-settings");
  if (!account) return () => {};
  const status = account.querySelector<HTMLElement>("#preferences-status")!;
  let timer = 0;
  let revision = 0;
  let saves = Promise.resolve();
  let photoRevision = 0;
  let pendingPhoto = getPreferences().photo;
  let portraitChanged = false;
  let pendingBanner = getPreferences().banner;
  let bannerChanged = false;
  let bannerRevision = 0;
  let processingBanner = false;
  let processingPhoto = false;
  const setStatus = (text: string, error = false) => {
    if (!account.isConnected) return;
    status.textContent = text;
    status.classList.toggle("is-error", error);
  };
  const persist = () => {
    timer = 0;
    const version = revision;
    const snapshot = getPreferences();
    saves = saves.catch(() => undefined).then(async () => {
      try {
        await savePreferences(coachUid, snapshot);
        if (version === revision) setStatus("Preferences saved");
      } catch {
        if (version === revision) setStatus("Applied here. Cloud sync failed — change a setting to retry.", true);
      }
    });
  };
  const verify = account.querySelector<HTMLButtonElement>("#verify-email");
  verify?.addEventListener("click", async () => {
    if (!auth.currentUser) return;
    const message = account.querySelector<HTMLElement>("#email-verification-status")!;
    verify.disabled = true;
    try { await sendEmailVerification(auth.currentUser); message.textContent = "Verification email sent. Check your inbox."; }
    catch { message.textContent = "Could not send verification. Try again shortly."; verify.disabled = false; }
  });
  account.querySelectorAll<HTMLInputElement | HTMLSelectElement>("[data-preference]").forEach((field) => {
    field.addEventListener("change", () => {
      const key = field.dataset.preference as keyof PortalPreferences;
      const value = field instanceof HTMLInputElement && field.type === "checkbox" ? field.checked : field.value;
      updatePreferences(coachUid, { ...getPreferences(), [key]: value });
      setStatus("Saving preferences…");
      revision++;
      window.clearTimeout(timer);
      timer = window.setTimeout(persist, 350);
    });
  });
  account.querySelectorAll<HTMLAnchorElement>(".settings-nav a").forEach((link) => {
    link.addEventListener("click", (event) => {
      event.preventDefault();
      const target = account.querySelector<HTMLElement>(link.getAttribute("href")!);
      target?.scrollIntoView({ behavior: getPreferences().reduceMotion || window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
      target?.setAttribute("tabindex", "-1");
      target?.focus({ preventScroll: true });
    });
  });
  const preview = account.querySelector<HTMLElement>("#profile-photo-preview")!;
  const photoStatus = account.querySelector<HTMLElement>("#photo-status")!;
  const remove = account.querySelector<HTMLButtonElement>("#remove-profile-photo")!;
  const photoInput = account.querySelector<HTMLInputElement>("#profile-photo")!;
  const bannerInput = account.querySelector<HTMLInputElement>("#profile-banner")!;
  const bannerPreview = account.querySelector<HTMLElement>("#profile-banner-preview")!;
  const bannerStatus = account.querySelector<HTMLElement>("#banner-status")!;
  const bannerRemove = account.querySelector<HTMLButtonElement>("#remove-profile-banner")!;
  account.querySelector<HTMLButtonElement>("[data-edit-cover]")!.addEventListener("click", () => bannerInput.click());
  account.querySelector<HTMLButtonElement>("[data-edit-profile-photo]")!.addEventListener("click", () => photoInput.click());
  bannerInput.addEventListener("change", async () => {
    const file = bannerInput.files?.[0]; if (!file) return;
    const attempt = ++bannerRevision; processingBanner = true; submit.disabled = true;
    bannerStatus.textContent = "Preparing banner…";
    try {
      const image = await photoData(file, true);
      if (attempt !== bannerRevision || !account.isConnected) return;
      pendingBanner = image; bannerChanged = true;
      bannerPreview.style.backgroundImage = `url("${image}")`; bannerRemove.hidden = false;
      bannerStatus.textContent = "Banner ready. Save your profile to sync it.";
    } catch (error) { if (attempt === bannerRevision) bannerStatus.textContent = error instanceof Error ? error.message : "This image could not be opened."; }
    finally { if (attempt === bannerRevision) { processingBanner = false; submit.disabled = processingPhoto; } bannerInput.value = ""; }
  });
  bannerRemove.addEventListener("click", () => {
    bannerRevision++; processingBanner = false; pendingBanner = ""; bannerChanged = true;
    bannerPreview.style.backgroundImage = ""; bannerRemove.hidden = true; submit.disabled = processingPhoto;
    bannerStatus.textContent = "Save your profile to remove the banner.";
  });
  const form = account.querySelector<HTMLFormElement>("#profile-form")!;
  const submit = form.querySelector<HTMLButtonElement>('button[type="submit"]')!;
  const showPhoto = () => {
    preview.innerHTML = pendingPhoto ? `<img src="${esc(pendingPhoto)}" alt="Your profile picture" />` : `<span>${esc(initials((account.querySelector<HTMLInputElement>("#p-name")?.value || "Coach")))}</span>`;
    remove.hidden = !pendingPhoto;
  };
  photoInput.addEventListener("change", async () => {
    const file = photoInput.files?.[0];
    if (!file) return;
    const attempt = ++photoRevision;
    processingPhoto = true;
    submit.disabled = true;
    photoStatus.textContent = "Preparing photo…";
    photoStatus.classList.remove("is-error");
    try {
      const photo = await photoData(file);
      if (attempt !== photoRevision || !account.isConnected) return;
      pendingPhoto = photo;
      portraitChanged = true;
      showPhoto();
      photoStatus.textContent = "Photo ready. Save to update your coach profile picture.";
    } catch (error) {
      if (attempt !== photoRevision) return;
      photoStatus.textContent = error instanceof Error ? error.message : "This image could not be opened.";
      photoStatus.classList.add("is-error");
    } finally {
      if (attempt === photoRevision) { processingPhoto = false; submit.disabled = processingBanner; }
      photoInput.value = "";
    }
  });
  remove.addEventListener("click", () => {
    photoRevision++;
    processingPhoto = false;
    submit.disabled = false;
    pendingPhoto = "";
    portraitChanged = true;
    photoInput.value = "";
    showPhoto();
    photoStatus.textContent = "Save your profile to remove the photo.";
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (processingPhoto || processingBanner) return;
    const result = account.querySelector<HTMLElement>("#profile-saved")!;
    const value = (id: string) => (form.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(`#${id}`)?.value ?? "").trim();
    if (!value("p-name")) { result.textContent = "Enter your display name."; return; }
    submit.disabled = true;
    photoInput.disabled = true;
    bannerInput.disabled = true; bannerRemove.disabled = true;
    remove.disabled = true;
    const preferenceFields = account.querySelectorAll<HTMLInputElement | HTMLSelectElement>("[data-preference]");
    preferenceFields.forEach((field) => { field.disabled = true; });
    const profileFields = form.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>("input, select, textarea");
    profileFields.forEach((field) => { field.disabled = true; });
    result.textContent = "Saving…";
    result.classList.remove("is-error", "is-warning");
    try {
      window.clearTimeout(timer);
      timer = 0;
      await saves;
      const [profile, roster] = await Promise.all([getProfile(coachUid), getRoster(coachUid)]);
      const years = Number.parseInt(value("p-years"), 10);
      const next = { ...getPreferences(), photo: pendingPhoto, banner: pendingBanner, certifications: value("p-certifications") };
      const outcome = await saveProfile(coachUid, { ...profile, name: value("p-name"), teamName: value("p-team"), organisation: value("p-org"), specialisation: value("p-spec") || null, yearsExperience: Number.isFinite(years) ? Math.min(70, Math.max(0, years)) : null, licenceNumber: value("p-licence"), notifySubmissions: next.submissionAlerts }, roster.filter((member) => member.status === "Active").length, next, portraitChanged, bannerChanged);
      if (outcome.preferencesSaved) { portraitChanged = false; bannerChanged = false; }
      updatePreferences(coachUid, next);
      result.textContent = outcome.preferencesSaved
        ? "Profile saved"
        : "Profile saved. Photo and certifications are kept on this browser; cloud sync is unavailable.";
      result.classList.toggle("is-warning", !outcome.preferencesSaved);
      photoStatus.textContent = "";
      bannerStatus.textContent = "";
      refreshIdentity();
    } catch (error) {
      const code = error && typeof error === "object" && "code" in error ? String(error.code) : "";
      result.textContent = code.includes("permission-denied")
        ? "Firebase blocked the profile update. Your edits are still here."
        : "Could not save. Your edits are still here; check your connection and try again.";
      result.classList.add("is-error");
    } finally { submit.disabled = false; photoInput.disabled = false; remove.disabled = false; bannerInput.disabled = false; bannerRemove.disabled = false; preferenceFields.forEach((field) => { field.disabled = false; }); profileFields.forEach((field) => { field.disabled = false; }); }
  });
  return () => { if (timer) { window.clearTimeout(timer); persist(); } photoRevision++; bannerRevision++; };
}

export async function hydrateAccountPreferences(uid: string): Promise<void> {
  try { await loadPreferences(uid); } catch { /* Cached settings remain usable offline. */ }
}
