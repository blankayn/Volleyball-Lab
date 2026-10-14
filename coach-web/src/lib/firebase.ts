import { initializeApp, type FirebaseApp, type FirebaseOptions } from "firebase/app";
import { getAuth, type Auth } from "firebase/auth";
import { getFirestore, type Firestore } from "firebase/firestore";

/* Firebase is initialised lazily via initFirebase() — never at module load.
   A missing .env must land on the setup screen that names the missing keys,
   not on a blank page. Callers import { auth, db } as before; boot() in
   main.ts assigns them before any page module runs. */

export const REQUIRED_VARS = [
  "VITE_FIREBASE_API_KEY",
  "VITE_FIREBASE_AUTH_DOMAIN",
  "VITE_FIREBASE_PROJECT_ID",
] as const;

/** Names of required keys with no value, or null when configured. */
export function configError(): string[] | null {
  const missing = REQUIRED_VARS.filter((name) => {
    const value = import.meta.env[name] as string | undefined;
    return !value || value.trim().length === 0 || value.startsWith("your-");
  });
  return missing.length > 0 ? [...missing] : null;
}

// Definite assignment: boot() assigns these before rendering anything.
export let auth!: Auth;
export let db!: Firestore;

let app: FirebaseApp | null = null;

export function initFirebase(): void {
  if (app) return;
  const missing = configError();
  if (missing) {
    throw new Error(`Missing Firebase config: ${missing.join(", ")}`);
  }
  const options: FirebaseOptions = {
    apiKey: import.meta.env["VITE_FIREBASE_API_KEY"] as string,
    authDomain: import.meta.env["VITE_FIREBASE_AUTH_DOMAIN"] as string,
    projectId: import.meta.env["VITE_FIREBASE_PROJECT_ID"] as string,
  };
  const bucket = import.meta.env["VITE_FIREBASE_STORAGE_BUCKET"] as string | undefined;
  if (bucket) options.storageBucket = bucket;
  const senderId = import.meta.env["VITE_FIREBASE_MESSAGING_SENDER_ID"] as string | undefined;
  if (senderId) options.messagingSenderId = senderId;
  const appId = import.meta.env["VITE_FIREBASE_APP_ID"] as string | undefined;
  if (appId) options.appId = appId;
  app = initializeApp(options);
  auth = getAuth(app);
  db = getFirestore(app);
}
