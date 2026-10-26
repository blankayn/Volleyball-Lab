/* Shared-backend access layer. Field-for-field with
   docs/coach-cloud-contract.md and the app's data/cloud/CloudSchema.kt —
   changing one side without the other breaks cross-client reads. */

import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
  type DocumentData,
  type Query,
  type Unsubscribe,
} from "firebase/firestore";
import { db } from "./firebase";

export type MembershipStatus = "Pending" | "Active" | "Ended";
export type AssignmentStatus = "Pending" | "InProgress" | "Completed";
export type Skill = "Serving" | "Receiving" | "Setting" | "Spiking" | "Blocking";

export interface CoachProfile {
  name: string;
  teamName: string;
  organisation: string;
  specialisation: string | null;
  yearsExperience: number | null;
  licenceNumber: string;
  notifySubmissions: boolean;
  notifyWeeklyDigest: boolean;
  notifySafetyAlerts: boolean;
}

export interface CoachPreview extends CoachProfile {
  coachUid: string;
  rosterSize: number;
}

export interface RosterMember {
  playerUid: string;
  playerName: string;
  position: string | null;
  status: MembershipStatus;
  requestedAt: number;
  decidedAt: number | null;
}

export interface Assignment {
  id: string;
  coachUid: string;
  playerUid: string;
  title: string;
  skill: Skill;
  level: string;
  summary: string;
  instructions: string;
  targetSets: number;
  targetReps: number;
  dueAt: number | null;
  status: AssignmentStatus;
  createdAt: number;
  libraryDrillId: string | null;
}

export interface Submission {
  sessionId: string;
  coachUid: string;
  playerUid: string;
  submittedAt: number;
  reviewedAt: number | null;
}

export interface CoachNote {
  id: string;
  coachUid: string;
  playerUid: string;
  sessionId: string | null;
  text: string;
  createdAt: number;
}

export interface CueScore {
  cueId: string;
  measured: number | null;
  score: number | null;
}

export interface RepResult {
  index: number;
  startMs: number;
  contactMs: number;
  endMs: number;
  cueScores: CueScore[];
}

export interface TrainingSession {
  id: string;
  ownerUid: string;
  skill: Skill;
  drillId: string;
  startedAt: number;
  durationMs: number;
  repCount: number;
  formScore: number | null;
  weakestCue: string | null;
  reps: RepResult[];
}

export const SKILLS: Skill[] = ["Serving", "Receiving", "Setting", "Spiking", "Blocking"];

export const SPECIALISATIONS = [
  "AllAround",
  "Serving",
  "Receiving",
  "Setting",
  "Spiking",
  "Blocking",
] as const;

const str = (d: DocumentData, key: string): string =>
  typeof d[key] === "string" ? (d[key] as string) : "";
const strOrNull = (d: DocumentData, key: string): string | null =>
  typeof d[key] === "string" ? (d[key] as string) : null;
const num = (d: DocumentData, key: string): number =>
  typeof d[key] === "number" ? (d[key] as number) : 0;
const numOrNull = (d: DocumentData, key: string): number | null =>
  typeof d[key] === "number" ? (d[key] as number) : null;
const bool = (d: DocumentData, key: string, fallback = true): boolean =>
  typeof d[key] === "boolean" ? (d[key] as boolean) : fallback;

export function asProfile(d: DocumentData): CoachProfile {
  return {
    name: str(d, "name"),
    teamName: str(d, "teamName"),
    organisation: str(d, "organisation"),
    specialisation: strOrNull(d, "specialisation"),
    yearsExperience: numOrNull(d, "yearsExperience"),
    licenceNumber: str(d, "licenceNumber"),
    notifySubmissions: bool(d, "notifySubmissions"),
    notifyWeeklyDigest: bool(d, "notifyWeeklyDigest"),
    notifySafetyAlerts: bool(d, "notifySafetyAlerts"),
  };
}

export function asMember(d: DocumentData): RosterMember {
  const status = str(d, "status");
  return {
    playerUid: str(d, "playerUid"),
    playerName: str(d, "playerName"),
    position: strOrNull(d, "position"),
    status: status === "Active" || status === "Ended" ? status : "Pending",
    requestedAt: num(d, "requestedAt"),
    decidedAt: numOrNull(d, "decidedAt"),
  };
}

const SKILL_SET = new Set<string>(SKILLS);

export function asAssignment(d: DocumentData): Assignment | null {
  const skill = str(d, "skill");
  if (!SKILL_SET.has(skill)) return null;
  const status = str(d, "status");
  return {
    id: str(d, "id"),
    coachUid: str(d, "coachUid"),
    playerUid: str(d, "playerUid"),
    title: str(d, "title"),
    skill: skill as Skill,
    level: str(d, "level") || "Beginner",
    summary: str(d, "summary"),
    instructions: str(d, "instructions"),
    targetSets: Math.max(1, num(d, "targetSets")),
    targetReps: Math.max(1, num(d, "targetReps")),
    dueAt: numOrNull(d, "dueAt"),
    status:
      status === "InProgress" || status === "Completed" ? status : "Pending",
    createdAt: num(d, "createdAt"),
    libraryDrillId: strOrNull(d, "libraryDrillId"),
  };
}

export function asSubmission(d: DocumentData): Submission {
  return {
    sessionId: str(d, "sessionId"),
    coachUid: str(d, "coachUid"),
    playerUid: str(d, "playerUid"),
    submittedAt: num(d, "submittedAt"),
    reviewedAt: numOrNull(d, "reviewedAt"),
  };
}

export function asNote(d: DocumentData): CoachNote {
  return {
    id: str(d, "id"),
    coachUid: str(d, "coachUid"),
    playerUid: str(d, "playerUid"),
    sessionId: strOrNull(d, "sessionId"),
    text: str(d, "text"),
    createdAt: num(d, "createdAt"),
  };
}

export function asSession(d: DocumentData): TrainingSession | null {
  const skill = str(d, "skill");
  if (!SKILL_SET.has(skill)) return null;
  const reps: RepResult[] = Array.isArray(d["reps"])
    ? (d["reps"] as DocumentData[]).map((r) => ({
        index: num(r, "index"),
        startMs: num(r, "startMs"),
        contactMs: num(r, "contactMs"),
        endMs: num(r, "endMs"),
        cueScores: Array.isArray(r["cueScores"])
          ? (r["cueScores"] as DocumentData[]).map((c) => ({
              cueId: str(c, "cueId"),
              measured: numOrNull(c, "measured"),
              score: numOrNull(c, "score"),
            }))
          : [],
      }))
    : [];
  return {
    id: str(d, "id"),
    ownerUid: str(d, "ownerUid"),
    skill: skill as Skill,
    drillId: str(d, "drillId"),
    startedAt: num(d, "startedAt"),
    durationMs: num(d, "durationMs"),
    repCount: num(d, "repCount"),
    formScore: numOrNull(d, "formScore"),
    weakestCue: strOrNull(d, "weakestCue"),
    reps,
  };
}

// ---- One-shot reads ---------------------------------------------------------
//
// Every list filters on one field and sorts client-side — the same rule the
// Android app follows, so neither client needs composite indexes and both run
// the same queries.

export async function getUserRole(uid: string): Promise<"PLAYER" | "COACH" | null> {
  const snap = await getDoc(doc(db, "users", uid));
  const role = snap.data()?.["role"];
  return role === "COACH" || role === "PLAYER" ? role : null;
}

/**
 * Completes a coach registration in one Firestore commit. Firebase Auth creates
 * the user before these documents can be written, so this is deliberately safe
 * to call again when a previous registration stopped between those two steps.
 */
export async function provisionCoachAccount(
  uid: string,
  email: string,
  profile: CoachProfile,
): Promise<void> {
  const now = Date.now();
  const existingHome = await getDoc(doc(db, "users", uid));
  const batch = writeBatch(db);
  batch.set(
    doc(db, "users", uid),
    {
      role: "COACH",
      displayName: profile.name,
      email,
      createdAt: existingHome.exists() ? num(existingHome.data(), "createdAt") : now,
      updatedAt: now,
    },
    { merge: true },
  );
  batch.set(
    doc(db, "coaches", uid),
    { ...profile, updatedAt: now },
    { merge: true },
  );
  batch.set(
    doc(db, "coachDirectory", uid),
    directoryProjection(profile, 0, now),
  );
  await batch.commit();
}

/** Restore the account-home row for an Auth user whose registration was cut short. */
export async function repairMissingCoachRole(
  uid: string,
  email: string,
  displayName: string,
): Promise<void> {
  const now = Date.now();
  const existingHome = await getDoc(doc(db, "users", uid));
  await setDoc(
    doc(db, "users", uid),
    {
      role: "COACH",
      displayName,
      email,
      createdAt: existingHome.exists() ? num(existingHome.data(), "createdAt") : now,
      updatedAt: now,
    },
    { merge: true },
  );
}

export async function getProfile(uid: string): Promise<CoachProfile> {
  const snap = await getDoc(doc(db, "coaches", uid));
  return snap.exists() ? asProfile(snap.data()) : emptyProfile();
}

export function emptyProfile(): CoachProfile {
  return {
    name: "",
    teamName: "",
    organisation: "",
    specialisation: null,
    yearsExperience: null,
    licenceNumber: "",
    notifySubmissions: true,
    notifyWeeklyDigest: true,
    notifySafetyAlerts: true,
  };
}

export async function saveProfile(
  uid: string,
  profile: CoachProfile,
  rosterSize: number,
): Promise<void> {
  const now = Date.now();
  const batch = writeBatch(db);
  batch.set(
    doc(db, "coaches", uid),
    { ...profile, updatedAt: now },
    { merge: true },
  );
  batch.set(
    doc(db, "coachDirectory", uid),
    directoryProjection(profile, rosterSize, now),
  );
  await batch.commit();
}

/** Public fields only; preferences and licence remain in the private profile. */
function directoryProjection(profile: CoachProfile, rosterSize: number, updatedAt: number) {
  return { name: profile.name, teamName: profile.teamName, organisation: profile.organisation,
    specialisation: profile.specialisation, yearsExperience: profile.yearsExperience, rosterSize, updatedAt };
}

export async function getRoster(coachUid: string): Promise<RosterMember[]> {
  const snap = await getDocs(collection(db, "roster", coachUid, "members"));
  return snap.docs
    .map((d) => asMember(d.data()))
    .sort((a, b) => b.requestedAt - a.requestedAt);
}

export async function setMemberStatus(
  coachUid: string,
  playerUid: string,
  status: MembershipStatus,
): Promise<void> {
  await updateDoc(doc(db, "roster", coachUid, "members", playerUid), {
    status,
    decidedAt: Date.now(),
  });
  const roster = await getRoster(coachUid);
  await setDoc(
    doc(db, "coachDirectory", coachUid),
    {
      rosterSize: roster.filter((m) => m.status === "Active").length,
      updatedAt: Date.now(),
    },
    { merge: true },
  );
}

export async function getAssignments(coachUid: string): Promise<Assignment[]> {
  const snap = await getDocs(
    query(collection(db, "assignments"), where("coachUid", "==", coachUid)),
  );
  return snap.docs
    .map((d) => asAssignment(d.data()))
    .filter((a): a is Assignment => a !== null)
    .sort((a, b) => b.createdAt - a.createdAt);
}

export async function saveAssignment(a: Assignment): Promise<void> {
  await setDoc(doc(db, "assignments", a.id), { ...a });
}

export async function deleteAssignment(id: string): Promise<void> {
  await deleteDoc(doc(db, "assignments", id));
}

export async function getSubmissions(coachUid: string): Promise<Submission[]> {
  const snap = await getDocs(
    query(collection(db, "submissions"), where("coachUid", "==", coachUid)),
  );
  return snap.docs
    .map((d) => asSubmission(d.data()))
    .sort((a, b) => b.submittedAt - a.submittedAt);
}

export async function getSessionsFor(
  playerUid: string,
  coachUid: string,
): Promise<TrainingSession[]> {
  // One filter (array-contains) so no composite index is needed; the owner
  // match happens client-side. The rules already restrict these rows to what
  // this coach was given.
  const snap = await getDocs(
    query(collection(db, "sessions"), where("sharedWith", "array-contains", coachUid)),
  );
  return snap.docs
    .filter((d) => d.data()["ownerUid"] === playerUid)
    .map((d) => asSession(d.data()))
    .filter((s): s is TrainingSession => s !== null)
    .sort((a, b) => b.startedAt - a.startedAt);
}

export async function getNotesForPlayer(
  coachUid: string,
  playerUid: string,
): Promise<CoachNote[]> {
  const snap = await getDocs(
    query(
      collection(db, "notes"),
      where("coachUid", "==", coachUid),
      where("playerUid", "==", playerUid),
    ),
  );
  return snap.docs
    .map((d) => asNote(d.data()))
    .sort((a, b) => b.createdAt - a.createdAt);
}

export async function getAssignmentsForPlayer(
  coachUid: string,
  playerUid: string,
): Promise<Assignment[]> {
  const all = await getAssignments(coachUid);
  return all.filter((a) => a.playerUid === playerUid);
}

/** Post a note and stamp the queue row reviewed — one action, in that order. */
export async function postNote(note: CoachNote): Promise<void> {
  const batch = writeBatch(db);
  batch.set(doc(db, "notes", note.id), { ...note });
  if (note.sessionId) {
    batch.update(
      doc(db, "submissions", `${note.coachUid}_${note.sessionId}`),
      { reviewedAt: Date.now() },
    );
  }
  await batch.commit();
}

export function uid(): string {
  return (
    crypto.randomUUID?.() ??
    `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  );
}

export { serverTimestamp };

// ---- Live subscriptions (dashboard freshness) --------------------------------

export function subscribe(
  q: Query,
  onData: () => void,
  onError: (e: unknown) => void,
): Unsubscribe {
  return onSnapshot(
    q,
    () => onData(),
    (e) => onError(e),
  );
}

export function queueQuery(coachUid: string): Query {
  return query(collection(db, "submissions"), where("coachUid", "==", coachUid));
}

export function rosterQuery(coachUid: string): Query {
  return query(collection(db, "roster", coachUid, "members"));
}
