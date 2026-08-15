# CLAUDE.md

Guidance for AI assistants working in this repository.

## What this repo is

A **GitHub Pages static site** hosting a small portfolio of independent
**Hebrew, right-to-left (RTL)** consumer web apps. It is served from
`https://yairpeer68-svg.github.io/yairpeer.github.io/` (note the doubled path —
the repo name is itself part of the URL).

There is **no build step for the website**: HTML/CSS/JS are committed as-is and
served directly. `.nojekyll` at the root disables Jekyll processing. The root
`README.md` is intentionally minimal.

The root `index.html` is a landing page linking to the four apps.

### Product language & direction

Everything user-facing is **Hebrew and RTL** (`<html lang="he" dir="rtl">`).
Product copy, commit messages, and the backend READMEs are written in Hebrew.
Keep new user-facing strings in Hebrew and preserve RTL layout. Code
identifiers, this file, and technical comments may be in English.

## The four apps

Each app is a self-contained installable **PWA** in its own top-level directory.
Note the naming quirk: the subscription app is branded **מנוימטר / minuymeter**
but lives in `subs/`, while its Android module is `minuymeter-android`.

| App (Hebrew) | Meaning | Web dir | Android module | Backend | What it does |
|---|---|---|---|---|---|
| 🕵️ צל דיגיטלי | "Digital Shadow" | `tzel/` | `tzel-android/` | `tzel-backend/` | Personal breach / digital-exposure check (HIBP), with a self-defense toolbox |
| 🏛️ פקיד | "Clerk" | `pakid/` | — | `pakid-backend/` | Photograph an official letter, get a plain-Hebrew AI explanation |
| 🛡️ תוקף | "Validity" | `tokef/` | `tokef-android/` | — | Track bureaucratic expiry dates (car test, passport, insurance…) with reminders |
| 💸 מנוימטר | "Meter of subscriptions" | `subs/` | `minuymeter-android/` | — | Track subscriptions and monthly spend, with renewal reminders |

### Anatomy of a web app directory

```
<app>/
  index.html            # the entire app: inline CSS + inline JS, single file
  sw.js                 # service worker (offline cache + notifications)
  manifest.webmanifest  # PWA manifest (name, colors, icons, RTL)
  privacy.html          # privacy policy (required for Play Store)
  icons/                # icon-192.png, icon-512.png, apple-touch-icon.png
  lp.html               # (tzel only) marketing landing page
```

## Frontend conventions

Follow the existing patterns — they are consistent across all four apps:

- **Single-file apps.** Each `index.html` contains all markup, a `<style>`
  block, and a `<script>` block. There are no external JS/CSS files, no bundler,
  no framework. Vanilla DOM APIs only. Files run 400–1300 lines.
- **Theming via CSS custom properties.** A `:root { --bg, --card, --text,
  --muted, --border, --radius, … }` block at the top of each app defines a dark
  palette; the same accent color is reused for the manifest `theme_color`,
  `<meta name="theme-color">`, and the Android WebView background. Keep these in
  sync when changing a color.
- **Font:** Google Fonts **Rubik**, preconnected in `<head>`, with a system
  font stack fallback.
- **Client-side storage:** state lives in `localStorage`, with keys namespaced
  per app (e.g. `tzel.premium`, `tzel.lang`, `K_SUBS`, `K_HIST`). Helper
  `loadJSON`/`save` wrappers are defined near the top of each script. `subs/`
  also mirrors data into **IndexedDB** so its service worker can fire renewal
  notifications while the app is closed.
- **Freemium model.** Every app has a free tier and a `premium` flag. The
  premium state is currently client-side (`settings.premium` /
  `localStorage["<app>.premium"]`) and gates features via `openPremium(...)`.
  Preserve the "free taste, premium unlock" structure when adding features.
- **Service worker:** registered from `index.html`
  (`navigator.serviceWorker.register("sw.js")`). Uses a versioned cache name
  (e.g. `const CACHE = "minuymeter-v3"`) with a stale-while-revalidate fetch
  handler. **Bump the cache version constant** in `sw.js` whenever cached assets
  change, or clients will keep serving stale files.

### Server-backed apps (tzel, pakid)

`tzel/` and `pakid/` can run in two modes, chosen at runtime from a `CONFIG`
object near the top of the `<script>`:

```js
const CONFIG = { SUPABASE_URL: "", SUPABASE_ANON_KEY: "" /*, TURNSTILE_SITE_KEY */ };
const SERVER_MODE = !!(CONFIG.SUPABASE_URL && CONFIG.SUPABASE_ANON_KEY);
```

- **Demo mode** (empty `CONFIG`): the app returns sample/mock results so it works
  with no backend. `pakid/` currently ships in demo mode; `tzel/` has live
  Supabase credentials filled in.
- **Server mode** (filled `CONFIG`): the app dynamically imports
  `@supabase/supabase-js` from `esm.sh`, signs the user in **anonymously**, and
  calls a Supabase **Edge Function** (`scan` for tzel, `analyze-letter` for
  pakid).
- **Secrets rule:** only public values (Supabase URL, anon key, Turnstile *site*
  key) ever go in the client. Secret keys (`HIBP_API_KEY`, `ANTHROPIC_API_KEY`,
  `TURNSTILE_SECRET_KEY`) live only as Edge Function secrets on the server.
  Never put a secret key in an `index.html`.

## Backends (Supabase)

`tzel-backend/` and `pakid-backend/` are **not deployed by CI** — they are
source + setup guides for a Supabase project you provision by hand. Each holds:

- `schema.sql` — tables, Row Level Security, quota/rate-limit logic. Run it in
  the Supabase SQL editor. (`tzel-backend` also has `schema-monitoring.sql`.)
- `functions/<name>/index.ts` — **Deno / TypeScript** Edge Functions, deployed
  via the Supabase dashboard. `tzel` has `scan` and `monitor-sweep`; `pakid` has
  `analyze-letter` (calls the Anthropic API).
- `README.md` (Hebrew) — the step-by-step deployment guide. **`MONITORING.md`**
  in `tzel-backend` covers the continuous-monitoring cron.

Design principle to preserve (stated in `tzel-backend/README.md`): tzel is a
**self-defense** tool — it only ever checks the *user's own* details. Do not add
capabilities that check third parties.

## Android wrappers

`tzel-android/`, `tokef-android/`, `minuymeter-android/` are thin **WebView**
shells (Java, single `MainActivity`) packaging each web app as an installable
Android app under the `io.github.yairpeer.<app>` namespace.

- **`tzel-android`** loads the **live site URL** in the WebView, so web updates
  reach the app automatically; external hosts open in the system browser.
- The release workflows for `tokef`/`minuymeter` instead **copy the web app into
  `app/src/main/assets/`** at build time (offline-bundled). Check the specific
  module before assuming which strategy it uses.
- Standard config: `compileSdk`/`targetSdk` 35, `minSdk` 24, Java 17, Gradle 8.9.
  `versionCode`/`versionName` are derived from `GITHUB_RUN_NUMBER`. Release
  signing is driven entirely by env vars (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`); with no keystore present the build stays unsigned
  (debug). App display name is in `app/src/main/res/values/strings.xml`.

## CI / GitHub Actions (`.github/workflows/`)

There are two workflow shapes, one per app that has an Android build:

- **`build-<app>-apk.yml`** — on push to `main` (path-filtered to the app's
  Android dir) or manual dispatch. Builds an unsigned **debug APK** and, on
  `main`, publishes it as a `<app>-latest` GitHub Release for direct download.
- **`release-<app>-aab.yml`** — **manual dispatch only**. Builds a **signed AAB +
  APK** for the Play Store. Requires repo secrets `KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (the keystore is base64-decoded
  at build time).

> **Stale file:** `.github/workflows/build-apk.yml` ("Build Sherlock APK") points
> at a `sherlock-android/` directory that no longer exists. It is dead and can be
> ignored or removed.

## Working in this repo

- **Deploy = merge to `main`.** GitHub Pages serves `main` directly; there is no
  website build or release step. A push to `main` is live within a minute.
- **Test locally** by serving the app directory over HTTP (service workers and
  ES-module imports need `http://`/`https://`, not `file://`), e.g.
  `python3 -m http.server` from the app folder, then open the app path.
- **Match the neighbors.** When editing an app, mirror the idioms already in that
  file (naming, helper functions, CSS-variable usage). When touching color/theme,
  update the manifest, the `theme-color` meta, and the Android background
  together. When changing cached assets, bump the `sw.js` cache version.
- **Keep secrets server-side** and keep user-facing text Hebrew/RTL.

### Git / branch workflow

Feature work happens on `claude/*` branches and lands via PR to `main` (commit
history and PR titles are in Hebrew). Do not create a PR unless explicitly asked.
