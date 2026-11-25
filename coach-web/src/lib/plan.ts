/* The coach's plan. Same limits as the app's CoachPlan.kt and ProPlanCard; firestore.rules
   enforces the student limit, feedback photos and rep marks. Everyday coaching stays free. */

import { doc, getDoc } from "firebase/firestore";
import { auth, db } from "./firebase";

export const FREE_STUDENTS = 3;
const CHECKOUT = "https://volleyball-lab-payments.volleyball-lab-session-ai.workers.dev/createGcashCheckout";
const CHECKOUT_HOSTS = new Set(["checkout.xendit.co", "xen.to", "checkout-staging.xendit.co", "dev.xen.to"]);
/** The project owner, as in firestore.rules hasPro(). */
const OWNER_UID = "4j06UlYqbrbLzujmfi8VW5yEIOq2";

export const PLAN_ROWS: [feature: string, free: string, pro: string][] = [
  ["Students", `${FREE_STUDENTS}`, "Unlimited"],
  ["Session review and written feedback", "Included", "Included"],
  ["Drills and chat", "Included", "Included"],
  ["Photos in feedback", "Not included", "Included"],
  ["Right / Wrong marks on reps", "Not included", "Included"],
  ["AI session explanations", "Not included", "Included"],
];

let pro = false;
/** The plan as last loaded. Free until then, and whenever it cannot be read. */
export const isPro = (): boolean => pro;

export function planOf(uid: string, data: Record<string, unknown> | undefined, now = Date.now()): boolean {
  if (uid === OWNER_UID) return true;
  if (data?.["status"] !== "active") return false;
  const expires = data["expiresAtEpochMs"];
  return typeof expires === "number" && (expires === 0 || expires > now);
}

export async function loadPlan(uid: string): Promise<boolean> {
  pro = await getDoc(doc(db, "entitlements", uid)).then((d) => planOf(uid, d.data())).catch(() => false);
  return pro;
}

export const canAccept = (activeStudents: number): boolean => pro || activeStudents < FREE_STUDENTS;

/** See CoachPlan.slots in the app. Returns null when nothing has to be recorded. */
export function nextSlots(held: string[] | null, active: string[], accepting: boolean): string[] | null {
  if (held === null) return accepting ? [...new Set(active)] : null;
  const leaving = new Set(held.filter((id) => !active.includes(id)).slice(0, 5));
  const kept = held.filter((id) => !leaving.has(id));
  return [...new Set(accepting ? [...kept, ...active] : kept)];
}

export interface Offer { enabled: boolean; priceCentavos: number; durationDays: number }
export async function loadOffer(): Promise<Offer | null> {
  const data = (await getDoc(doc(db, "billingConfig", "pro"))).data();
  if (!data) return null;
  const price = Number(data["priceCentavos"]), days = Number(data["durationDays"]);
  return { enabled: data["enabled"] === true && Number.isInteger(price) && price >= 100 && Number.isInteger(days) && days >= 0, priceCentavos: price, durationDays: days };
}

export const offerText = (offer: Offer): string =>
  `₱${(offer.priceCentavos / 100).toLocaleString("en-PH", { minimumFractionDigits: offer.priceCentavos % 100 ? 2 : 0 })} · ${offer.durationDays === 0 ? "lifetime access" : `${offer.durationDays} days`} · one-time GCash payment`;

export const CHECKOUT_ERRORS: Record<string, string> = {
  offer_changed: "The Pro offer changed. Reload the page and try again.",
  payment_processing: "Xendit is still confirming your previous checkout. Check again shortly.",
  previous_checkout_open: "The offer changed while your previous checkout was open. Try again after it expires.",
  checkout_needs_review: "Your previous checkout needs review. Contact support before trying to pay again.",
  verify_email_required: "Confirm your email before paying.",
  checkout_not_configured: "GCash checkout is currently unavailable.",
};

/** The hosted checkout address, or null when the account already has Pro. */
export async function createCheckout(offer: Offer): Promise<string | null> {
  const user = auth.currentUser;
  if (!user) throw new Error("sign_in_required");
  const response = await fetch(CHECKOUT, {
    method: "POST", redirect: "error", cache: "no-store",
    headers: { Authorization: `Bearer ${await user.getIdToken(true)}`, "Content-Type": "application/json" },
    body: JSON.stringify({ priceCentavos: offer.priceCentavos, durationDays: offer.durationDays }),
  });
  const result = (await response.json().catch(() => ({}))) as { error?: string; status?: string; checkoutUrl?: string };
  if (!response.ok) throw new Error(result.error ?? "checkout_unavailable");
  if (result.status === "pro_active") return null;
  const url = new URL(result.checkoutUrl ?? "");
  if (url.protocol !== "https:" || !CHECKOUT_HOSTS.has(url.hostname) || url.username || url.port) throw new Error("invalid_checkout_url");
  return url.href;
}
