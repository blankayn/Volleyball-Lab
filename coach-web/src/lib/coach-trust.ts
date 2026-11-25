/* What makes a coach verified. Same checks and limits as the app's CoachTrust.kt;
   firestore.rules decides what may be published. Players see only Verified or Not verified. */

import { sendEmailVerification, verifyBeforeUpdateEmail, type User } from "firebase/auth";
import { collection, deleteDoc, doc, getDoc, getDocs, limit, query, serverTimestamp, setDoc, where } from "firebase/firestore";
import { db } from "./firebase";

export const MIN_VOUCHES = 2;
export const MIN_REVIEWERS = 3;
const YEAR_MS = 365 * 24 * 60 * 60 * 1000;
/** Written again at most this often, so opening the dashboard is not a write every time. */
const REFRESH_MS = 30 * 24 * 60 * 60 * 1000;

/** Restricted endings only. A person cannot simply buy one. */
const INSTITUTION = /^([a-z0-9-]+[.])+((edu|gov)([.][a-z]{2})?|(ac|sch)[.][a-z]{2})$/;

/** The school or government domain of an email, or "" for any other email. */
export function institutionDomain(email: string | null | undefined): string {
  const domain = (email ?? "").toLowerCase().split("@")[1] ?? "";
  return INSTITUTION.test(domain) ? domain : "";
}

export interface TrustRecord { institutionDomain: string; confirmedAt: number }

function asRecord(data: Record<string, unknown> | undefined): TrustRecord | null {
  if (!data) return null;
  const at = data["confirmedAt"] as { toMillis?: () => number } | undefined;
  return { institutionDomain: String(data["institutionDomain"] ?? ""), confirmedAt: at?.toMillis?.() ?? 0 };
}

export async function getTrustRecord(uid: string): Promise<TrustRecord | null> {
  return asRecord((await getDoc(doc(db, "coachTrust", uid))).data());
}

export const isCurrent = (record: TrustRecord | null, now = Date.now()): boolean => !!record && now - record.confirmedAt <= YEAR_MS;

/**
 * Publishes what this sign-in proves. Safe to call often and to fail: until it works,
 * players simply see "Not verified".
 */
export async function syncCoachTrust(user: User): Promise<TrustRecord | null> {
  const before = user.emailVerified;
  const address = user.email;
  await user.reload();
  // The rules read the sign-in token, which still says what was true when it was made.
  if (user.emailVerified !== before || user.email !== address) await user.getIdToken(true);
  const existing = await getTrustRecord(user.uid);
  if (!user.emailVerified) return existing;
  const domain = institutionDomain(user.email);
  if (existing && existing.institutionDomain === domain && Date.now() - existing.confirmedAt < REFRESH_MS) return existing;
  const write = () => setDoc(doc(db, "coachTrust", user.uid), { institutionDomain: domain, confirmedAt: serverTimestamp() });
  try { await write(); } catch { await user.getIdToken(true); await write(); }
  return { institutionDomain: domain, confirmedAt: Date.now() };
}

export interface CoachOption { uid: string; name: string; organisation: string }
export interface TrustView { record: TrustRecord | null; vouches: number; reviewers: number; vouchedFor: string[]; coaches: CoachOption[]; verifiedByTeam: boolean }

export async function loadTrustView(uid: string): Promise<TrustView> {
  const record = await getTrustRecord(uid);
  const [received, given, reviews, team] = await Promise.all([
    getDocs(query(collection(db, "coachVouches"), where("coachUid", "==", uid), limit(20))),
    getDocs(query(collection(db, "coachVouches"), where("voucherUid", "==", uid))),
    getDocs(query(collection(db, "coachReviews"), where("coachUid", "==", uid), limit(100))),
    getDoc(doc(db, "coachVerified", uid)).then((d) => d.data()?.["verified"] === true).catch(() => false),
  ]);
  const vouchers = [...new Set(received.docs.map((d) => String(d.data()["voucherUid"] ?? "")).filter((id) => id && id !== uid))];
  const records = await Promise.all(vouchers.map((id) => getTrustRecord(id)));
  const eligible = isCurrent(record) && !!record?.institutionDomain;
  const coaches = eligible
    ? (await getDocs(collection(db, "coachDirectory"))).docs.filter((d) => d.id !== uid)
        .map((d) => ({ uid: d.id, name: String(d.data()["name"] ?? ""), organisation: String(d.data()["organisation"] ?? "") }))
        .filter((c) => c.name).sort((a, b) => a.name.localeCompare(b.name))
    : [];
  return {
    record,
    vouches: records.filter((r) => isCurrent(r) && !!r?.institutionDomain).length,
    reviewers: new Set(reviews.docs.map((d) => String(d.data()["playerUid"] ?? ""))).size,
    vouchedFor: given.docs.map((d) => String(d.data()["coachUid"] ?? "")),
    coaches,
    verifiedByTeam: team,
  };
}

export interface CoachCheck { label: string; passed: boolean; help: string }

/** The five checks, with how to pass each one. */
export function coachChecks(view: TrustView, name: string, organisation: string): CoachCheck[] {
  const email = isCurrent(view.record);
  const domain = email ? view.record?.institutionDomain ?? "" : "";
  return [
    { label: "Email confirmed", passed: email, help: "Open the link we email you, then come back to this page." },
    { label: domain ? `School or government email (${domain})` : "School or government email", passed: !!domain, help: "Optional. Use your school or government address if you have one." },
    { label: "Full name and school or club", passed: name.trim().split(/\s+/).filter(Boolean).length >= 2 && !!organisation.trim(), help: "Add both in your profile." },
    { label: `Known by ${MIN_VOUCHES} coaches with an institution email`, passed: view.vouches >= MIN_VOUCHES, help: `${view.vouches} of ${MIN_VOUCHES} so far. Ask coaches you work with to do this from their Account page.` },
    { label: `Reviewed by ${MIN_REVIEWERS} different players`, passed: view.reviewers >= MIN_REVIEWERS, help: `${view.reviewers} of ${MIN_REVIEWERS} so far. Players review you after you give them feedback.` },
  ];
}

export type CoachStatus = "team" | "institution" | "passed" | "none";
export function coachStatus(checks: CoachCheck[], verifiedByTeam = false): CoachStatus {
  if (verifiedByTeam) return "team";
  const [email, institution, details, vouches, players] = checks.map((c) => c.passed);
  if (!email || !details) return "none";
  return institution ? "institution" : vouches || players ? "passed" : "none";
}

export const STATUS_LABEL: Record<CoachStatus, string> = {
  team: "Verified", institution: "Verified", passed: "Verified", none: "Not verified",
};

export const vouchFor = (voucherUid: string, coachUid: string): Promise<void> =>
  setDoc(doc(db, "coachVouches", `${coachUid}_${voucherUid}`), { coachUid, voucherUid, createdAt: serverTimestamp() });
export const withdrawVouch = (voucherUid: string, coachUid: string): Promise<void> =>
  deleteDoc(doc(db, "coachVouches", `${coachUid}_${voucherUid}`));

export const sendConfirmation = (user: User): Promise<void> => sendEmailVerification(user);
/** The address only changes once the coach opens the link sent to the new one. */
export const changeEmail = (user: User, email: string): Promise<void> => verifyBeforeUpdateEmail(user, email);
