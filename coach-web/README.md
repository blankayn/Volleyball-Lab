# Volleyball Lab — Coach Portal

The coach website for Volleyball Lab, built with TypeScript, Vite, Firebase, and GSAP.

## Run locally

Requires Node 20 or newer.

```sh
cd coach-web
npm ci
cp .env.example .env
# Fill in the Firebase Web app configuration in .env.
npm run dev
```

Create a production build with `npm run build`. Run `npm run preview` to inspect it.

## Features

- Landing page with responsive photography, SVG pose illustration, and an interactive coaching demo.
- GSAP entrances and scroll reveals, with reduced-motion support and route cleanup.
- Coach registration and sign-in through Firebase Authentication.
- Dashboard, review queue, roster, player profiles, drill assignments, and account settings through Firestore.
- IBM Plex Sans interface text, Barlow Condensed headings, and consistent SVG icons.

The landing page's pose overlay and example scores are illustrations, not live MediaPipe detections. Production dashboard data comes from Firebase.

Firebase configuration, Authentication providers, and deployed Firestore rules must be provisioned for the connected project. This frontend branch does not deploy or modify the backend.

Photograph provenance is recorded in `public/assets/PHOTO-SOURCES.md`. Local `.env`, dependencies, generated builds, and visual-check fixtures are excluded from Git.

## Verification

`npm run build` passes TypeScript checks and production bundling. Live account writes require an authenticated coach and the configured Firebase backend; this design update does not claim those flows were retested end to end.
