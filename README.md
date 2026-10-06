# SRKB Unified

Attendance, registration and calling management for multi-centre programmes. Three parts that work together:

| Folder | What it is | Stack |
|---|---|---|
| `attendance-server/` | Attendance + registration server, web UI, Google Sheets mirror | Python, Flask, SQLite, Excel |
| `calling-backend/` | Calling campaigns, pools, follow-ups, realtime events (SSE) | Node, Express, Prisma, SQLite |
| `android-app/` | "SRKB Unified" app for callers, registerers and admins. Offline queue and background sync. | Kotlin, Jetpack Compose |
| `website/` | Download, terms, privacy and delete-account pages | Static HTML, deployable on Vercel |

This repository contains **no real data, secrets or credentials**. All of those come from environment variables, see each `.env.example`.

## Run the servers

```bash
# 1. calling backend
cd calling-backend
cp .env.example .env        # fill in the secrets (use long random values)
npm install
npx prisma migrate deploy && npx prisma generate
npm run migrate:bootstrap-host   # creates the first admin from BOOTSTRAP_HOST_*
npm run dev

# 2. attendance server
cd ../attendance-server
cp .env.example .env        # INTERNAL_TOKEN and JWT_SHARED_SECRET must match the calling backend
pip install -r requirements.txt
python app.py               # http://127.0.0.1:6061
```

Optional Google Sheets mirror: put a service-account key at `attendance-server/google_sheets_key.json` and set `GOOGLE_SHEET_ID`.

## Android app

```bash
cd android-app
./gradlew testSideloadDebugUnitTest      # unit tests
./gradlew assembleSideloadDebug          # installable debug APK
```

Before building, set your own server URLs in `app/src/main/java/com/example/data/AppData.kt` (`backendUrl`, `callingBackendUrl`; defaults are `*.example.com` placeholders) and your contact email in `AccountDeletion.kt`.
Release signing reads `STORE_PASSWORD` / `KEY_PASSWORD` environment variables and your own keystore. None is included.

## Website

`website/` is plain static files. Set `SERVER` in `website/app.js` to your attendance server URL, then deploy (for example `vercel deploy --prod`). The contact email and publisher name in the legal pages are the original author's, so replace them with your own.

## Notes

- This is a snapshot of a working system. Some comments describe the original deployment's incidents; centre and user names have been replaced with placeholders.
- No licence has been chosen yet; until one is added, all rights are reserved by the author.
