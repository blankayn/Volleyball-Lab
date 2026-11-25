/* Account → Verification: whether this coach is verified, and how to become so. */

import { auth } from "../lib/firebase";
import { changeEmail, coachChecks, coachStatus, loadTrustView, sendConfirmation, STATUS_LABEL, syncCoachTrust, vouchFor, withdrawVouch, type TrustView } from "../lib/coach-trust";
import { getProfile } from "../lib/cloud";
import { esc } from "../lib/format";
import { icon } from "../lib/icons";

export function trustSection(heading: string): string {
  return `<section class="settings-card trust-card" aria-labelledby="trust-settings">${heading}
    <div id="trust-body" aria-live="polite"><p class="muted">Checking…</p></div></section>`;
}

function body(view: TrustView, name: string, organisation: string): string {
  const checks = coachChecks(view, name, organisation);
  const status = coachStatus(checks, view.verifiedByTeam);
  const email = auth.currentUser?.email ?? "";
  const verified = auth.currentUser?.emailVerified === true;
  const canVouch = view.coaches.length > 0;
  const mine = view.coaches.filter((c) => view.vouchedFor.includes(c.uid));
  return `<p class="trust-status trust-${status}">${icon(status === "none" ? "shield" : "check")}${STATUS_LABEL[status]}</p>
    <p class="muted trust-intro">Players see only Verified or Not verified.${status === "none" ? " Confirm your email, complete your profile, then pass one of the other checks." : ""}</p>
    ${status === "team" ? "" : `<ul class="trust-list">${checks.map((c) => `<li class="${c.passed ? "is-passed" : ""}"><span class="trust-mark" aria-hidden="true">${c.passed ? icon("check") : ""}</span><span><strong>${esc(c.label)}</strong><small>${c.passed ? "Passed" : esc(c.help)}</small></span></li>`).join("")}</ul>`}
    ${verified ? "" : `<div class="trust-row"><button class="btn btn-ghost btn-sm" type="button" id="trust-confirm">Send confirmation email</button></div>`}
    <form class="trust-row" id="trust-email-form"><div class="field"><label for="trust-email">Change account email</label><input id="trust-email" type="email" autocomplete="email" placeholder="you@school.edu.ph" required aria-describedby="trust-email-now" /><small id="trust-email-now" class="muted">Now: ${esc(email)}. You also sign in with this.</small></div><button class="btn btn-ghost btn-sm" type="submit">Send link</button></form>
    ${canVouch ? `<form class="trust-row" id="trust-vouch-form"><div class="field"><label for="trust-vouch">Say you know another coach</label><select id="trust-vouch" required><option value="">Choose a coach</option>${view.coaches.filter((c) => !view.vouchedFor.includes(c.uid)).map((c) => `<option value="${esc(c.uid)}">${esc(c.name)}${c.organisation ? ` · ${esc(c.organisation)}` : ""}</option>`).join("")}</select></div><button class="btn btn-ghost btn-sm" type="submit">I know them</button></form>` : ""}
    ${mine.length ? `<ul class="trust-mine">${mine.map((c) => `<li>${esc(c.name)} <button class="photo-remove" type="button" data-withdraw="${esc(c.uid)}">Take back</button></li>`).join("")}</ul>` : ""}
    <p class="trust-message" id="trust-message" role="status"></p>`;
}

export function wireTrust(root: HTMLElement, coachUid: string): void {
  const target = root.querySelector<HTMLElement>("#trust-body");
  if (!target) return;
  const draw = async (message = "") => {
    try {
      const user = auth.currentUser;
      if (user) await syncCoachTrust(user).catch(() => null);
      const [view, profile] = await Promise.all([loadTrustView(coachUid), getProfile(coachUid)]);
      if (!target.isConnected) return;
      target.innerHTML = body(view, profile.name, profile.organisation);
      const line = target.querySelector<HTMLElement>("#trust-message");
      if (line) line.textContent = message;
    } catch {
      if (!target.isConnected) return;
      target.innerHTML = `<p class="muted">Could not load your checks.</p><button class="btn btn-ghost btn-sm" type="button" id="trust-retry">Try again</button>`;
    }
  };
  const run = async (button: HTMLButtonElement | null, work: () => Promise<string>) => {
    if (button) button.disabled = true;
    const line = target.querySelector<HTMLElement>("#trust-message");
    try { const message = await work(); await draw(message); }
    catch (failure) {
      const code = String((failure as { code?: string }).code ?? "");
      if (line) line.textContent = code.includes("requires-recent-login") ? "Sign out, sign in again, then retry. This protects your account."
        : code.includes("email-already-in-use") ? "Another account already uses that email."
        : code.includes("too-many-requests") ? "Too many emails sent. Wait a few minutes, then retry."
        : code.includes("invalid") ? "That does not look like an email address." : "That did not work. Check your connection and try again.";
      if (button) button.disabled = false;
    }
  };
  target.addEventListener("click", (event) => {
    const button = (event.target as HTMLElement).closest<HTMLButtonElement>("button");
    const user = auth.currentUser;
    if (!button) return;
    if (button.id === "trust-retry") void draw();
    else if (button.id === "trust-confirm" && user) void run(button, async () => { await sendConfirmation(user); return "Email sent. Open the link, then reload this page."; });
    else if (button.dataset["withdraw"]) void run(button, async () => { await withdrawVouch(coachUid, button.dataset["withdraw"]!); return "Taken back."; });
  });
  target.addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const button = form.querySelector<HTMLButtonElement>('button[type="submit"]');
    const user = auth.currentUser;
    if (form.id === "trust-email-form" && user) {
      const email = form.querySelector<HTMLInputElement>("#trust-email")!.value.trim();
      void run(button, async () => { await changeEmail(user, email); return `Link sent to ${email}. Your email changes when you open it. You may need to sign in again.`; });
    } else if (form.id === "trust-vouch-form") {
      const other = form.querySelector<HTMLSelectElement>("#trust-vouch")!.value;
      if (other) void run(button, async () => { await vouchFor(coachUid, other); return "Recorded. Their players can see one more coach knows them."; });
    }
  });
  void draw();
}
