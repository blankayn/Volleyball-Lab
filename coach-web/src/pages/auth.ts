import {
  browserLocalPersistence,
  browserSessionPersistence,
  createUserWithEmailAndPassword,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  setPersistence,
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
import { icon } from "../lib/icons";

function authLayout(kind: "login" | "register", content: string): string {
  const registering = kind === "register";
  const header = registering ? topbar(false, "auth") : `<header class="topbar"><div class="topbar-inner">
    <a class="brand" href="#/" aria-label="Volleyball Lab home"><img class="brand-mark" src="/assets/volleyball-lab-logo.png" alt="" /><span>Volleyball Lab <small>Coach</small></span></a>
    <nav aria-label="Coach account"><a class="auth-header-link" href="#/register">Become a coach</a></nav>
  </div></header>`;
  const pageFooter = registering ? footer() : `<footer class="footer auth-login-footer"><div class="wrap">
    <span>© 2026 Volleyball Lab</span><nav aria-label="Portal footer"><a href="#/">Home</a><a href="#/register">Create account</a></nav>
  </div></footer>`;
  return `<div class="auth-page">${header}
    <main class="auth-layout auth-layout-${kind}">
      ${registering ? `
      <aside class="auth-story" aria-label="Coaching with Volleyball Lab">
        <div class="auth-story-top"><span class="auth-edition">THE COACH’S CORNER</span><span class="auth-court-mark">${icon("court")}</span></div>
        <div class="auth-story-copy"><p class="eyebrow">${registering ? "Start something stronger" : "Make the next session count"}</p><h2>${registering ? "Build the team.<br /><em>Shape the game.</em>" : "Back to the court.<br /><em>Eyes on progress.</em>"}</h2></div>
        <figure class="auth-story-visual">
          <img src="/assets/${registering ? "auth-court-line.jpg" : "auth-sunset-net.jpg"}" alt="${registering ? "Volleyball resting on a court line in a softly lit indoor gym" : "Close view of a beach volleyball net in warm evening light"}" width="900" height="${registering ? "1350" : "1599"}" decoding="async" />
          <span class="auth-photo-corner auth-photo-corner-top" aria-hidden="true"></span><span class="auth-photo-corner auth-photo-corner-bottom" aria-hidden="true"></span>
          <figcaption><span>${registering ? "01 / The first point" : "02 / The next rep"}</span><strong>${registering ? "Great teams start here." : "Every detail matters."}</strong></figcaption>
        </figure>
        <div class="auth-story-bottom"><span>Train<span aria-hidden="true">/</span>Review<span aria-hidden="true">/</span>Improve</span>${icon("arrow")}</div>
      </aside>` : ""}
      <section class="auth-form-panel" aria-label="${registering ? "Create coach account" : "Coach sign in"}">${content}</section>
    </main>${pageFooter}</div>`;
}

function wirePasswordVisibility(root: HTMLElement): void {
  const field = root.querySelector<HTMLInputElement>("#password");
  const toggle = root.querySelector<HTMLButtonElement>("#password-toggle");
  toggle?.addEventListener("click", () => {
    if (!field) return;
    const visible = field.type === "password";
    field.type = visible ? "text" : "password";
    toggle.setAttribute("aria-pressed", String(visible));
    toggle.setAttribute("aria-label", visible ? "Hide password" : "Show password");
    toggle.innerHTML = icon(visible ? "eyeOff" : "eye");
  });
}

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
  root.innerHTML = authLayout("login", `<div class="auth-card">
    <img class="login-card-logo" src="/assets/volleyball-lab-logo.png" alt="Volleyball Lab" width="48" height="48" />
    <p class="eyebrow">Your coaching workspace</p>
    <h1>Welcome back</h1>
    <p class="muted">Your queue, roster and assignments are where you left them.</p>
    <div id="form-error" aria-live="assertive"></div>
    <form id="login-form" novalidate>
      <div class="field"><label for="email">Email</label>
        <div class="input-affix">${icon("mail")}<input id="email" type="email" autocomplete="email" required autofocus placeholder="you@school.edu" aria-describedby="email-error" /></div>
        <span class="field-error" id="email-error" hidden>${icon("shield")}<span></span></span></div>
      <div class="field"><label for="password">Password</label>
        <div class="password-input"><span class="password-leading-icon">${icon("lock")}</span><input id="password" type="password" autocomplete="current-password" required minlength="6" placeholder="Your password" aria-describedby="password-error" /><button id="password-toggle" type="button" aria-label="Show password" aria-pressed="false" aria-controls="password">${icon("eye")}</button></div>
        <span class="field-error" id="password-error" hidden>${icon("shield")}<span></span></span></div>
      <div class="login-options"><label><input id="remember-me" type="checkbox" checked />Remember me</label><a href="#/forgot-password" id="forgot-password">Forgot password?</a></div>
      <button class="btn btn-primary auth-submit" type="submit"><span class="auth-submit-label">Sign in</span><span class="auth-submit-icon">${icon("arrow")}</span></button>
    </form>
    <p class="auth-switch">New here? <a href="#/register">Register as a coach</a></p>
  </div>`);

  wirePasswordVisibility(root);
  const form = root.querySelector<HTMLFormElement>("#login-form");
  const err = root.querySelector<HTMLElement>("#form-error");
  const emailField = root.querySelector<HTMLInputElement>("#email");
  const passwordField = root.querySelector<HTMLInputElement>("#password");
  const validate = (field: HTMLInputElement | null): boolean => {
    if (!field) return false;
    const message = field.id === "email"
      ? (!field.value.trim() ? "Enter your email address." : !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(field.value.trim()) ? "Enter a valid email address." : "")
      : (!field.value ? "Enter your password." : field.value.length < 6 ? "Password must be at least 6 characters." : "");
    const fieldError = root.querySelector<HTMLElement>(`#${field.id}-error`);
    if (fieldError) {
      fieldError.hidden = !message;
      const text = fieldError.querySelector("span");
      if (text) text.textContent = message;
    }
    field.setAttribute("aria-invalid", String(Boolean(message)));
    return !message;
  };
  emailField?.addEventListener("blur", () => validate(emailField));
  passwordField?.addEventListener("blur", () => validate(passwordField));
  root.querySelector<HTMLAnchorElement>("#forgot-password")?.addEventListener("click", async (event) => {
    event.preventDefault();
    if (!validate(emailField)) { emailField?.focus(); return; }
    const link = event.currentTarget as HTMLAnchorElement;
    link.setAttribute("aria-disabled", "true");
    if (err) err.innerHTML = '<div class="login-status" role="status">Sending a password reset link…</div>';
    try {
      await sendPasswordResetEmail(auth, emailField!.value.trim());
      if (err) err.innerHTML = '<div class="login-status" role="status">If an account exists for this email, a password reset link is on its way.</div>';
    } catch {
      if (err) err.innerHTML = '<div class="login-status" role="status">If an account exists for this email, a password reset link is on its way.</div>';
    } finally { link.removeAttribute("aria-disabled"); }
  });
  form?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const validEmail = validate(emailField);
    const validPassword = validate(passwordField);
    if (!validEmail || !validPassword) {
      (validEmail ? passwordField : emailField)?.focus();
      return;
    }
    const button = form.querySelector<HTMLButtonElement>('button[type="submit"]');
    const label = button?.querySelector<HTMLElement>(".auth-submit-label");
    const arrow = button?.querySelector<HTMLElement>(".auth-submit-icon");
    if (button) {
      button.disabled = true;
      button.setAttribute("aria-busy", "true");
      if (label) label.textContent = "Signing in…";
      if (arrow) arrow.innerHTML = '<span class="auth-spinner" aria-hidden="true"></span>';
    }
    if (err) err.innerHTML = "";
    try {
      const email = emailField!.value.trim();
      const password = passwordField!.value;
      const remember = root.querySelector<HTMLInputElement>("#remember-me")?.checked ?? true;
      await setPersistence(auth, remember ? browserLocalPersistence : browserSessionPersistence);
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
        const message = code === "portal/player-account"
          ? "That account is a player account. The portal is for coaches — players train in the Android app."
          : code.includes("invalid-credential") || code.includes("wrong-password") || code.includes("user-not-found")
            ? "Incorrect email or password."
            : friendly(code);
        err.innerHTML = `<div class="form-error login-error-banner" role="alert"><span>${esc(message)}</span><button type="button" aria-label="Dismiss error">×</button></div>`;
        err.querySelector("button")?.addEventListener("click", () => { err.innerHTML = ""; });
      }
      if (button) {
        button.disabled = false;
        button.removeAttribute("aria-busy");
        if (label) label.textContent = "Sign in";
        if (arrow) arrow.innerHTML = icon("arrow");
      }
    }
  });
}

const FIELD_ERROR = "Please fill in your name, a valid email and a password of 6+ characters.";

export function renderRegister(root: HTMLElement): void {
  document.title = "Register — Volleyball Lab Coach Portal";
  const specOptions = SPECIALISATIONS.map(
    (s) => `<option value="${s}">${s === "AllAround" ? "All-around" : s}</option>`,
  ).join("");

  root.innerHTML = authLayout("register", `<div class="auth-card">
    <p class="eyebrow">Coach registration</p>
    <h1>Set up your team</h1>
    <p class="muted">Create your profile, connect your athletes, and plan their next session.</p>
    <div id="form-error"></div>
    <form id="register-form">
      <div class="form-section-title"><span>01</span><h2>Your account &amp; team</h2></div>
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
          <div class="password-input"><input id="password" type="password" autocomplete="new-password" required minlength="6" placeholder="6+ characters" /><button id="password-toggle" type="button" aria-label="Show password" aria-pressed="false" aria-controls="password">${icon("eye")}</button></div></div>
      </div>
      <div class="form-section-title"><span>02</span><h2>Your coaching profile</h2></div>
      <div class="form-grid">
        <div class="field"><label for="org">School or club</label>
          <input id="org" maxlength="48" placeholder="University of Cebu" /></div>
        <div class="field"><label for="licence">Certification / licence no.</label>
          <input id="licence" maxlength="40" placeholder="Optional" /></div>
      </div>
      <div class="form-grid">
        <div class="field"><label for="spec">Specialisation</label>
          <select id="spec">${specOptions}</select></div>
        <div class="field"><label for="years">Years coaching</label>
          <input id="years" type="number" min="0" max="70" placeholder="e.g. 6" /></div>
      </div>
      <button class="btn btn-primary auth-submit" type="submit">Create coach account ${icon("arrow")}</button>
    </form>
    <p class="auth-switch">Already registered? <a href="#/login">Sign in</a></p>
  </div>`);

  wirePasswordVisibility(root);
  const form = root.querySelector<HTMLFormElement>("#register-form");
  const err = root.querySelector<HTMLElement>("#form-error");
  const val = (id: string): string =>
    (form?.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)?.value ?? "").trim();

  form?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const button = form.querySelector<HTMLButtonElement>('button[type="submit"]');
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
