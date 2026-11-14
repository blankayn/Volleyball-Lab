# Volleyball Lab

Volleyball Lab is an Android volleyball training app with a companion coach web
portal. Players practice five skills, review camera-based form feedback, track
training progress, and share sessions with a coach.

BSIT capstone project · University of Cebu, College of Computer Studies.

## Project overview

- **Player app:** serving, receiving, setting, spiking, and blocking practice;
  guided drills; pose capture; session results; and progress history.
- **Coach workspace:** player connections, submitted sessions, feedback,
  questions, and assigned training through the web portal and mobile coach mode.
- **Shared services:** Firebase authentication and cloud data, private recording
  transfers, optional detailed AI coaching, and payment integration.

Form feedback is training guidance. Research accuracy targets and deployment
readiness are documented separately from implemented features.

## Start here

| What you want to review | Where to look |
| --- | --- |
| Android app source and tests | [`app/`](app/) |
| Coach web portal | [`coach-web/`](coach-web/) |
| Architecture and pose pipeline | [Development guide](docs/development-guide.md#architecture) |
| Feature checklist and evidence | [Manuscript checklist](docs/manuscript-gap-audit.md) |
| Verification and release limitations | [Completion status](docs/app-completion-status.md) |
| Setup, research, and design documents | [Documentation index](docs/README.md) |

## Build the Android app

Use Android Studio with the SDK versions pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml), a JDK supported by the
Gradle wrapper, and a physical Android 8.0 (API 26) or newer phone for camera
verification.

```powershell
.\gradlew.bat :app:assemblePreview
```

The installable preview APK is written to
`app/build/outputs/apk/preview/app-preview.apk`. The first online build downloads
and verifies the pose model. See the [model setup](app/src/main/assets/README.md)
and [preview build instructions](docs/android-preview-builds.md).

## Run the coach portal

```sh
cd coach-web
cp .env.example .env
npm ci
npm run dev
```

Fill in the Firebase web configuration in the local `.env` file. To create a
production bundle, run `npm run build`. See the
[shared cloud contract](docs/coach-cloud-contract.md) for roles and data access.

## Repository structure

| Folder or file | Purpose |
| --- | --- |
| `app/` | Android player app and mobile coach workspace |
| `coach-web/` | Browser-based coach portal |
| `ai-worker/` | Optional AI coaching and private recording service |
| `auth-worker/` | Optional emailed-code password recovery service |
| `payment-worker/` | Hosted payment webhook integration |
| `functions/` | Firebase server functions and backend tests |
| `tools/` | Model training, pose evaluation, and verification tools |
| `docs/` | Architecture, setup, design references, and research evidence |
| `gradle/`, `gradlew*`, `*.gradle.kts`, `gradle.properties` | Reproducible Android build configuration |
| `firebase*.json`, `firestore.*`, `storage.rules`, `.firebaserc` | Firebase configuration, access rules, indexes, and emulator setup |
| `GLOSSARY.md`, `AGENTS.md` | Shared terminology and repository contribution instructions |

## Verification

Run the Android unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Portal checks live in `coach-web/checks/`; backend tests live alongside their
services. [`tools/verify-app.ps1`](tools/verify-app.ps1) runs the broader project
verification. Dated test results are recorded in the documentation rather than
presented as a current pass count here.

## Deployment notes

- Development checkout uses simulated payments. Live GCash payments require
  merchant activation, server configuration, and live verification. See
  [payment setup](docs/gcash-checkout-setup.md).
- Normal Android builds use Firebase password-reset emails. Emailed reset codes
  require the separate sender setup described in [auth-worker](auth-worker/README.md).
- Research metrics, dataset coverage, and remaining validation are described in
  the [pose evaluation guide](tools/pose-pipeline/README.md) and
  [AI coaching status](docs/ai-coach-system-status.md).

Keep local credentials, signing keys, generated builds, and personal editor
settings out of version control.
