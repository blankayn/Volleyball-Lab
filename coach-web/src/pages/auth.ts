import {
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  updateProfile,
} from "firebase/auth";
import { auth } from "../lib/firebase";
import {
  emptyProfile,
  getUserRole,
  provisionCoachAccount,
  repairMissingCoachRole,
} from "../lib/cloud";
import { SPECIALISATIONS } from "../lib/cloud";
import { esc } from "../lib/format";
import { go } from "../lib/router";
import { footer, topbar } from "../lib/ui";

/* Firebase's raw errors are written for developers. These are for coaches. */
function friendly(code: string): string {
  if (code.includes("email-already-in-use"))
    return "That email already has an account. Try signing in instead.";
  if (code.includes("invalid-credential") || code.includes("wrong-password"))
    return "That email or password is not right.";
  if (code.includes("invalid-email")) return "That does not look like an email address.";
  if (code.includes("weak-password")) return "Passwords need at least 6 characters.";
  if (code.includes("network-request-failed"))
    return "No connection. Check your internet and try again.";
  if (code.includes("operation-not-allowed"))
    return "Email sign-in is switched off for this project. Enable Email/Password in the Firebase console.";
  if (code.includes("permission-denied"))
    return "Firebase signed you in, but Firestore access is blocked. Deploy the project's firestore.rules, then try again.";
  if (code.includes("unavailable") || code.includes("deadline-exceeded"))
    return "Firebase is taking too long to respond. Check your connection and try again.";
  return "Something went wrong. Please try again.";
}

export function renderLogin(root: HTMLElement): void {
  document.title = "Sign in — Volleyball Lab Coach Portal";
  root.innerHTML = `${topbar(false, "portal")}
  <div class="wrap"><div class="auth-shell"><div class="auth-card">
    <p class="eyebrow">Coach portal</p>
    <h1>Welcome back</h1>
    <p class="muted">Your queue, roster and assignments are where you left them.</p>
    <div id="form-error"></div>
    <form id="login-form">
      <div class="field"><label for="email">Email</label>
        <input id="email" type="email" autocomplete="email" required placeholder="you@school.edu" /></div>
      <div class="field"><label for="password">Password</label>
        <input id="password" type="password" autocomplete="current-password" required placeholder="••••••••" /></div>
      <button class="btn btn-primary" style="width:100%" type="submit">Sign in</button>
    </form>
    <p class="auth-switch">New here? <a href="#/register">Register as a coach</a></p>
  </div></div></div>${footer()}`;

  const form = root.querySelector<HTMLFormElement>("#login-form");
  const err = root.querySelector<HTMLElement>("#form-error");
  form?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const button = form.querySelector("button");
    if (button) button.disabled = true;
    if (err) err.innerHTML = "";
    try {
      const email = (form.querySelector("#email") as HTMLInputElement).value.trim();
      const password = (form.querySelector("#password") as HTMLInputElement).value;
      const cred = await signInWithEmailAndPassword(auth, email, password);
      let role = await getUserRole(cred.user.uid);
      if (role === "PLAYER") {
        await auth.signOut();
        throw { code: "portal/player-account" };
      }
      // Auth may have succeeded during registration before Firestore rejected
      // the account documents. Once the backend is fixed, the first login
      // completes that interrupted registration automatically.
      if (role === null) {
        await repairMissingCoachRole(
          cred.user.uid,
          cred.user.email ?? email,
          cred.user.displayName ?? email.split("@")[0] ?? "Coach",
        );
        role = "COACH";
      }
      go("/app");
    } catch (failure) {
      const code = failure instanceof Error ? failure.message : String((failure as { code?: string }).code ?? failure);
      if (err) {
        err.innerHTML = `<div class="form-error" role="alert">${esc(
          code === "portal/player-account"
            ? "That account is a player account. The portal is for coaches — players train in the Android app."
            : friendly(code),
        )}</div>`;
      }
      if (button) button.disabled = false;
    }
  });
}

const FIELD_ERROR = "Please fill in your name, a valid email and a password of 6+ characters.";

export function renderRegister(root: HTMLElement): void {
  document.title = "Register — Volleyball Lab Coach Portal";
  const specOptions = SPECIALISATIONS.map(
    (s) => `<option value="${s}">${s === "AllAround" ? "All-around" : s}</option>`,
  ).join("");

  root.innerHTML = `${topbar(false, "portal")}
  <div class="wrap"><div class="auth-shell" style="max-width:640px"><div class="auth-card">
    <p class="eyebrow">Coach registration</p>
    <h1>Set up your team</h1>
    <p class="muted">This creates your real coach account — the same one players find in
      their app's Coach tab. Your coaching details appear in the player directory. Your email, password, and
      licence number are never shown to athletes.</p>
    <div id="form-error"></div>
    <form id="register-form">
      <div class="form-grid">
        <div class="field"><label for="name">Your name</label>
          <input id="name" autocomplete="name" required maxlength="60" placeholder="Coach Rivera" /></div>
        <div class="field"><label for="team">Team name</label>
          <input id="team" maxlength="48" placeholder="UC Junior Spikers" /></div>
      </div>
      <div class="form-grid">
        <div class="field"><label for="email">Email</label>
          <input id="email" type="email" autocomplete="email" required placeholder="you@school.edu" /></div>
        <div class="field"><label for="password">Password</label>
          <input id="password" type="password" autocomplete="new-password" required minlength="6" placeholder="6+ characters" /></div>
      </div>
      <div class="form-grid">
        <div class="field"><label for="org">School or club</label>
          <input id="org" maxlength="48" placeholder="University of Cebu" /></div>
        <div class="field"><label for="licence">Certification / licence no.</label>
          <input id="licence" maxlength="40" placeholder="Optional" />
          <span class="hint">Private — stored on your profile, never shown to players.</span></div>
      </div>
      <div class="form-grid">
        <div class="field"><label for="spec">Specialisation</label>
          <select id="spec">${specOptions}</select></div>
        <div class="field"><label for="years">Years coaching</label>
          <input id="years" type="number" min="0" max="70" placeholder="e.g. 6" /></div>
      </div>
      <button class="btn btn-primary" style="width:100%" type="submit">Create coach account</button>
      <p class="hint" style="font-size:12.5px;color:var(--ink-faint);margin-top:12px">
        Players discover you by name, team and specialisation. Only sessions they
        explicitly submit are ever shared — nothing uploads automatically.</p>
    </form>
    <p class="auth-switch">Already registered? <a href="#/login">Sign in</a></p>
  </div></div></div>${footer()}`;

  const form = root.querySelector<HTMLFormElement>("#register-form");
  const err = root.querySelector<HTMLElement>("#form-error");
  const val = (id: string): string =>
    (form?.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)?.value ?? "").trim();

  form?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const button = form.querySelector("button");
    if (button) button.disabled = true;
    if (err) err.innerHTML = "";
    const name = val("name");
    const email = val("email");
    const password = (form.querySelector("#password") as HTMLInputElement).value;
    if (!name || !email.includes("@") || password.length < 6) {
      if (err) err.innerHTML = `<div class="form-error" role="alert">${FIELD_ERROR}</div>`;
      if (button) button.disabled = false;
      return;
    }
    try {
      // A failed Firestore write leaves Firebase Auth signed in. Reuse that
      // account on retry so the coach is not trapped by email-already-in-use.
      const signedIn = auth.currentUser;
      const user = signedIn?.email?.toLowerCase() === email.toLowerCase()
        ? signedIn
        : (await createUserWithEmailAndPassword(auth, email, password)).user;
      const uid = user.uid;
      await updateProfile(user, { displayName: name }).catch(() => undefined);
      const years = Number.parseInt(val("years"), 10);
      const profile = {
        ...emptyProfile(),
        name,
        teamName: val("team"),
        organisation: val("org"),
        specialisation: val("spec") || null,
        yearsExperience: Number.isFinite(years) ? Math.min(70, Math.max(0, years)) : null,
        licenceNumber: val("licence"),
      };
      await provisionCoachAccount(uid, email, profile);
      go("/app");
    } catch (failure) {
      const code = failure instanceof Error ? failure.message : String((failure as { code?: string }).code ?? failure);
      if (err) err.innerHTML = `<div class="form-error" role="alert">${esc(friendly(code))}</div>`;
      if (button) button.disabled = false;
    }
  });
}
