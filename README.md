# Bedtime Parental MVP

Android Parent + Child prototype with a Cloudflare Worker backend.

## What it does

- Parent app sends Bedtime ON/OFF for a Child ID.
- Child app polls the backend and shows/removes a full-screen Android overlay.
- It does **not** disable or control Android shutdown/restart.
- If bedtime is still active after the phone/app comes back, the child monitor can restore the overlay.
- GitHub Actions builds Parent and Child debug APKs automatically.

## Project folders

- `android/parentapp` - parent Android app
- `android/childapp` - child Android app / bedtime overlay
- `cloudflare` - Cloudflare Worker API using Workers KV
- `.github/workflows/build-android.yml` - cloud APK build
- `.github/workflows/deploy-cloudflare.yml` - Worker deploy

## Cloudflare backend

1. Create a Workers KV namespace named `BEDTIME_STATE`.
2. Put its namespace ID into `cloudflare/wrangler.toml`.
3. Optional but recommended: create Worker secret `PARENT_API_KEY`.
4. Add GitHub secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
5. Run the `Deploy Cloudflare Worker` GitHub Action.
6. Use the Worker URL in both Android apps, for example `https://bedtime-parental-api.<subdomain>.workers.dev`.

API:

- `GET /health`
- `GET /api/children/<childId>/bedtime`
- `POST /api/children/<childId>/bedtime` with JSON `{ "active": true }`

## GitHub cloud APK build

The `Build Android APKs` workflow creates two downloadable artifacts:

- `bedtime-parent-apk`
- `bedtime-child-apk`

A local Android Studio install is not required just to produce test APKs.

## Important MVP note

This is a prototype. Before real family use, add proper authenticated parent/child pairing, signed release APKs, backend authorization, tamper handling, privacy controls, and clear emergency access behavior.
