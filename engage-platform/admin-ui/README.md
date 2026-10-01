# admin-ui: WUMIKA Engage console (v0)

Angular 22, standalone, zoneless, signals + `httpResource`, Signal Forms, Tailwind 4. Talks only to `admin-api` (`/api`).

Screens: sign-in (password, TOTP, first-time MFA enrolment with a QR code, set-password link), ingest health, customer lookup and 360 view (masked; Reveal for ANALYST, audited), payment failures, consent copy registry (register for CONFIG_ADMIN).

## Run locally

Needs **Node 22.22+ or 24** (this machine keeps Node 20 as its default; a portable Node 24 lives in `%LOCALAPPDATA%\engage-tools\node-v24.21.0-win-x64`):

```sh
export PATH="$(cygpath -u "$LOCALAPPDATA")/engage-tools/node-v24.21.0-win-x64:$PATH"
npm ci
npm start          # http://localhost:4200, proxies /api to admin-api on 8083
npm test -- --watch=false
npm run build      # dist/admin-ui/browser
```

Start admin-api first (`./gradlew.bat :admin-api:run`, see LOCAL-SETUP.md "Admin API").

## Security choices

- The access token lives in memory only (never `localStorage`); the refresh token is an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/auth`. A page load restores the session with one silent refresh.
- The interceptor retries a 401 once after a single shared refresh, however many requests failed together (`auth.interceptor.spec.ts`).
- Route guards are a convenience; admin-api enforces every role on every endpoint.
