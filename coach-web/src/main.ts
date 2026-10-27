import { onAuthStateChanged } from "firebase/auth";
import { auth, configError, initFirebase } from "./lib/firebase";
import { getUserRole } from "./lib/cloud";
import { go, parseRoute } from "./lib/router";
import "./styles/tokens.css";
import "./styles/main.css";
import "./styles/portal.css";
import "./styles/preferences.css";
import "./styles/coach-experience.css";
import { setPreferenceAccount, watchSystemPreferences } from "./lib/preferences";

const appRoot = document.querySelector<HTMLElement>("#app");
if (!appRoot) throw new Error("#app missing");
const root: HTMLElement = appRoot;

function setupScreen(missing: string[]): void {
  document.title = "Setup needed — Volleyball Lab Coach Portal";
  root.innerHTML = `<div class="wrap"><div class="auth-shell" style="max-width:640px"><div class="auth-card">
    <p class="eyebrow">Setup needed</p>
    <h1>Connect the backend first</h1>
    <p class="muted">The portal could not start because these Firebase keys are missing
      or still placeholders in <code>coach-web/.env</code>:</p>
    <ul>${missing.map((m) => `<li><code>${m}</code></li>`).join("")}</ul>
    <ol class="muted" style="line-height:1.7;padding-left:20px">
      <li>Firebase console → project <strong>volleyball-lab</strong> → Project settings → Add a Web app.</li>
      <li>Copy the SDK config values into <code>coach-web/.env</code> (see <code>.env.example</code>).</li>
      <li>Enable <strong>Authentication → Email/Password</strong> and create the <strong>Firestore</strong> database.</li>
      <li>Restart the dev server (<code>npm run dev</code>) — values are read at startup — then reload.</li>
    </ol>
    <p class="muted" style="font-size:13px">Athletes' training, roles and the review queue all live in that
      project, which is what makes this portal and the Android app work together.</p>
  </div></div></div>`;
}

function notCoachPage(): void {
  document.title = "Coach portal — Volleyball Lab";
  root.innerHTML = `<div class="wrap"><div class="auth-shell"><div class="auth-card">
    <p class="eyebrow">Coach portal</p>
    <h1>Players train in the app</h1>
    <p class="muted">This account is a player account, and the portal is for coaches.
      Training, scores and your own coach connection live in the Android app.</p>
    <button class="btn btn-primary" style="width:100%" id="not-coach-out">Sign out</button>
  </div></div></div>`;
  root.querySelector("#not-coach-out")?.addEventListener("click", async () => {
    await auth.signOut();
    go("/");
  });
}

function backendError(): void {
  root.innerHTML = `<div class="wrap"><div class="auth-shell"><div class="auth-card">
    <h1>Could not reach the backend</h1>
    <p class="muted">Check your connection and that Firestore is provisioned for this project.</p>
    <button class="btn btn-ghost" id="retry">Try again</button></div></div></div>`;
  root.querySelector("#retry")?.addEventListener("click", () => {
    current = null;
    void render();
  });
}

// Null until the first route has rendered — it must never equal a real hash,
// or the initial load exits before drawing anything.
let current: string | null = null;
let renderVersion = 0;
let disposeDashboard: (() => void) | undefined;

async function render(): Promise<void> {
  const hash = window.location.hash;
  if (hash === current) return;
  current = hash;
  const version = ++renderVersion;
  disposeDashboard?.();
  disposeDashboard = undefined;
  const route = parseRoute(hash);
  (await import("./pages/landing")).disposeLanding();

  if (version !== renderVersion) return;
  window.scrollTo(0, 0);
  const user = auth.currentUser;
  if (route.name === "app") {
    if (!user) {
      go("/login");
      return;
    }
    try {
      const role = await getUserRole(user.uid);
      if (version !== renderVersion) return;
      if (role !== "COACH") {
        notCoachPage();
        return;
      }
    } catch {
      if (version === renderVersion) backendError();
      return;
    }
    const dashboard = await import("./pages/dashboard");
    if (version !== renderVersion) return;
    const { renderDashboard } = dashboard;
    disposeDashboard = dashboard.disposeDashboard;
    await renderDashboard(root, user.uid, route.tab, route.playerUid, route.sessionId);
    return;
  }

  // Signed-in coaches going to auth pages land back in the app.
  if (user && (route.name === "login" || route.name === "register")) {
    try {
      const role = await getUserRole(user.uid);
      if (version !== renderVersion) return;
      if (role === "COACH") {
        go("/app");
        return;
      }
    } catch {
      /* fall through to the form */
    }
  }

  if (route.name === "login") {
    const { renderLogin } = await import("./pages/auth");
    if (version !== renderVersion) return;
    renderLogin(root);
  } else if (route.name === "register") {
    const { renderRegister } = await import("./pages/auth");
    if (version !== renderVersion) return;
    renderRegister(root);
  } else {
    const { renderLanding } = await import("./pages/landing");
    if (version !== renderVersion) return;
    renderLanding(root);
  }
}

function boot(): void {
  watchSystemPreferences();
  const missing = configError();
  if (missing) {
    setupScreen(missing);
    return;
  }
  try {
    initFirebase();
  } catch (e) {
    console.error(e);
    setupScreen(configError() ?? ["VITE_FIREBASE_API_KEY"]);
    return;
  }

  const safelyRender = () => void render().catch((e) => {
    console.error(e);
    backendError();
  });
  window.addEventListener("hashchange", safelyRender);

  // Auth state drives re-render: sign-in/out anywhere swaps the shell.
  onAuthStateChanged(auth, (user) => {
    setPreferenceAccount(user?.uid ?? null);
    const route = parseRoute(window.location.hash);
    if (current !== null && (route.name === "login" || route.name === "register")) return;
    current = null;
    safelyRender();
  });
}

boot();
