/* Account → Plan: what Free and Pro include for a coach, and the checkout. */

import { auth } from "../lib/firebase";
import { esc } from "../lib/format";
import { CHECKOUT_ERRORS, createCheckout, isPro, loadOffer, loadPlan, offerText, PLAN_ROWS, type Offer } from "../lib/plan";

export function planSection(heading: string): string {
  return `<section class="settings-card plan-card" aria-labelledby="plan-settings">${heading}<div id="plan-body" aria-live="polite"><p class="muted">Loading your plan…</p></div></section>`;
}

function body(offer: Offer | null): string {
  const pro = isPro();
  const verified = auth.currentUser?.emailVerified === true;
  const upgrade = pro ? "" : offer?.enabled
    ? `<p class="plan-price">${esc(offerText(offer))}</p>${verified ? "" : `<p class="muted">Confirm your email before paying.</p>`}<button class="btn btn-primary" type="button" id="plan-checkout"${verified ? "" : " disabled"}>Get Pro</button>`
    : `<p class="muted">GCash checkout is currently unavailable.</p>`;
  return `<p class="plan-now">You are on <strong>${pro ? "Pro" : "Free"}</strong>.</p>
    <div class="plan-table-wrap"><table class="plan-table"><thead><tr><th scope="col">Feature</th><th scope="col"${pro ? "" : ' class="is-current"'}>Free</th><th scope="col"${pro ? ' class="is-current"' : ""}>Pro</th></tr></thead>
    <tbody>${PLAN_ROWS.map(([feature, free, paid]) => `<tr><th scope="row">${esc(feature)}</th><td${free === "Not included" ? ' class="is-off"' : ""}>${esc(free)}</td><td>${esc(paid)}</td></tr>`).join("")}</tbody></table></div>
    ${upgrade}<p class="plan-message" id="plan-message" role="status"></p>`;
}

export function wirePlan(root: HTMLElement, coachUid: string): void {
  const target = root.querySelector<HTMLElement>("#plan-body");
  if (!target) return;
  let offer: Offer | null = null;
  void (async () => {
    const [, loaded] = await Promise.all([loadPlan(coachUid), loadOffer().catch(() => null)]);
    offer = loaded;
    if (target.isConnected) target.innerHTML = body(offer);
  })();
  target.addEventListener("click", async (event) => {
    const button = (event.target as HTMLElement).closest<HTMLButtonElement>("#plan-checkout");
    if (!button || button.disabled || !offer) return;
    const message = target.querySelector<HTMLElement>("#plan-message")!;
    button.disabled = true;
    button.textContent = "Opening checkout…";
    message.textContent = "";
    try {
      const url = await createCheckout(offer);
      if (url) { window.location.assign(url); return; }
      message.textContent = "You already have Pro. Reload to see it.";
    } catch (failure) {
      message.textContent = CHECKOUT_ERRORS[failure instanceof Error ? failure.message : ""] ?? "Could not open GCash checkout. Please try again.";
    }
    button.disabled = false;
    button.textContent = "Get Pro";
  });
}
