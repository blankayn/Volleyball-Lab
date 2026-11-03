import { doc, getDoc, writeBatch } from "firebase/firestore";
import { db } from "./firebase";
import { getPreferences, normalizePreferences, updatePreferences, validPhoto, validBanner, type PortalPreferences } from "./preferences";

export const preferencesPath = (uid: string) => doc(db, "coaches", uid, "webSettings", "preferences");
let hydratedUid: string | null = null;

export async function loadPreferences(uid: string): Promise<PortalPreferences> {
  if (hydratedUid === uid) return getPreferences();
  const snap = await getDoc(preferencesPath(uid));
  const settings = snap.exists() ? normalizePreferences(snap.data()) : getPreferences();
  const [media, legacy] = await Promise.all([getDoc(doc(db, "profileMedia", uid)), getDoc(doc(db, "coachPhotos", uid))]);
  const photo = media.data()?.photo ?? legacy.data()?.photo;
  const banner = media.data()?.banner;
  if (validPhoto(photo)) settings.photo = photo;
  if (validBanner(banner)) settings.banner = banner;
  if (!snap.exists()) {
    const profile = await getDoc(doc(db, "coaches", uid));
    settings.submissionAlerts = profile.data()?.notifySubmissions !== false;
  }
  updatePreferences(uid, settings);
  hydratedUid = uid;
  return settings;
}

export async function savePreferences(uid: string, preferences: PortalPreferences): Promise<void> {
  const data = normalizePreferences(preferences);
  const batch = writeBatch(db);
  batch.set(preferencesPath(uid), { ...data, updatedAt: Date.now() });
  batch.update(doc(db, "coaches", uid), { notifySubmissions: data.submissionAlerts, updatedAt: Date.now() });
  await batch.commit();
}
