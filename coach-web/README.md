# Volleyball Lab — Coach Portal

The coach half of Volleyball Lab as a real website: registration, roster,
review queue, drill assignments and written feedback — on the **same Firebase
backend the Android app uses**, so the two work together instead of past each
other.

- **Landing page** with a procedural Three.js hero (no model files).
- **Coach registration** — the account home the app's README always said had to
  exist somewhere: Firebase Auth + `users/{uid}` (`role: "COACH"`), profile and
  directory listing. No test accounts, no plus-tag conventions.
- **Dashboard** — sidebar navigation, review queue, roster + join requests, per-athlete review with
  cue breakdowns, assignments, account settings. Live-updating queue and roster.

The contract both clients build against is
[`docs/coach-cloud-contract.md`](../docs/coach-cloud-contract.md); the
enforcement is [`firestore.rules`](../firestore.rules) at the repo root.

## Run it

Requires Node 20+.

```bash
cd coach-web
cp .env.example .env   # fill from Firebase console → Project settings → web app config
npm install
npm run dev            # http://localhost:5173
```

Production build and preview:

```bash
npm run build
npm run preview
```

Deploy hosting + rules with the Firebase CLI from the repo root:

```bash
firebase deploy
```

## First-time backend setup

1. Firebase console → project **`volleyball-lab`** (the one
   `app/google-services.json` already points at).
2. **Authentication** → Sign-in method → enable **Email/Password**.
3. **Firestore Database** → Create database (production mode) → Rules tab →
   paste `firestore.rules` → Publish. (Or `firebase deploy --only firestore:rules`.)
4. **Project settings** → Add a **Web app** → copy the config into `.env`.
5. Register at `#/register`. The new coach immediately appears in the Android
   app's coach directory, and a fresh app install signs them straight into the
   coach shell — the role comes from `users/{uid}.role`, not the device.

## How the two halves connect

| Flow | App | Portal |
|---|---|---|
| Coach registers | — (no coach sign-up on mobile, by design) | Writes `users`, `coaches`, `coachDirectory` rows |
| Player browses coaches | Reads `coachDirectory` | Keeps its row fresh on profile/roster changes |
| Player asks to join | Creates `roster/{coach}/members/{me}` as `Pending` | Sees the request live |
| Coach approves | Roster row → `Active` (or via portal) | Same row, same write |
| Player submits | Writes `submissions/{coach_session}` + shares the session | Queue updates live |
| Coach reviews | Reads session + writes note (or via portal) | Session breakdown + note composer |
| Coach assigns | Reads `assignments` for me | Writes the assignment |
| Player finishes | Marks `Completed` | Sees the status |

Pose landmarks and video never cross the network on either side — only scores.
The app's camera-permission promise holds for the cloud build.

## Design notes

Tokens mirror the Android app (`ui/theme/Color.kt`): rust `#CC5500`, Alice Blue
`#F0F8FF`, ink `#141C33`, Hanken Grotesk, 24dp cards with soft shadow, one dark
navy feature card per screen, and the fixed accent assignment (rust = people,
indigo = review, green = sessions, violet = written material).

Empty states are real: with no roster, queue or sessions, every screen says so
and names the fix. There is no sample data anywhere in this portal.


## Verification (September 12, 2026)

- `npm run build`: TypeScript checks and production bundling pass.
- Browser checked landing, registration, and sign-in at desktop and 390px phone width.
- Required registration fields block empty submission; mobile registration has no horizontal overflow.
- Signed-out dashboard navigation redirects to sign-in. No browser console errors during these checks.
- Real account creation, roster changes, assignments, and notes still need an authenticated coach test against the configured Firebase project.

Dashboard route cleanup cancels stale renders and live listeners. Live updates preserve edited forms. Save failures retain entries and show a visible error. Profile/directory writes and note/review updates use atomic Firestore batches.

## Sidebar verification (October 1, 2026)

- Overview, Review queue, Roster, Assignments and Account use a persistent desktop sidebar with icons and an active-page indicator. An opened player has a contextual sidebar link.
- At widths of 800px and below, the Pages button expands the navigation; choosing a page or pressing Escape closes it. Sign out remains available in the sidebar while data loads.
- TypeScript checks and production bundling pass. Authenticated browser checks passed for all five page links and their `aria-current` states.
- At 390px, the menu opens, navigation closes it, Escape works, and the roster layout has no horizontal overflow. The temporary viewport override was reset after verification.
- Local browser preview: [`outputs/coach-dashboard-sidebar.jpg`](../outputs/coach-dashboard-sidebar.jpg). This verification did not publish the hosting build or change backend data.

## Sidebar publication

- Published the verified production build to [Volleyball Lab Coach Portal](https://volleyball-lab.web.app/#/app/overview) after the user's request to publish.
- Firebase Hosting deployment completed successfully for project `volleyball-lab` (11 files). The published dashboard bundle is `dashboard-COeKZBNE.js`.
- Signed in with the existing coach test account on the live site and verified all five sidebar routes and their active-page indicators. The overview loaded the coach profile and current roster/queue data.
- Live preview: [`outputs/coach-dashboard-sidebar-live.jpg`](../outputs/coach-dashboard-sidebar-live.jpg).
