# admin-ui: WUMIKA Engage console

Angular 22, standalone, zoneless, signals + `httpResource`, Signal Forms, Tailwind 4. Talks only to `admin-api` (`/api`).

Screens:
- **Sign-in:** password, then TOTP or a recovery code. First-time MFA enrolment with a QR code shows ten recovery codes once. Set-password link.
- **Every page:** kill switches in the header. Halting needs CAMPAIGN_SEND or CONFIG_ADMIN, a reason, and the scope name typed; releasing needs CONFIG_ADMIN.
- **Dashboard:** spend vs budget, 24 h sends and block reasons, journeys, opt-ins and opt-outs, push and WhatsApp health. Polls every 30 s.
- **Campaigns:** composer → dry run → approval (never the author) → arm (only with a dry run under 30 minutes old) → live progress.
- **Segments:** builder with a live count; JSON for deeper nesting.
- **Journey inspector:** ANALYST; exact phone and a reason, logged.
- **Customers:** lookup and 360 view, masked; one field revealed at a time, with a reason.
- **Settings:** generated from the config registry, with the proposals inbox.
- **Templates, consent copy, payment failures, ingest health.**
- **Exports:** ANALYST; 3 a day, 15-minute download link.
- **Operators:** OWNER.
- **Account:** new recovery codes.

Money uses the same lakh grouping as `Paise.toRupeeString` (`₹1,49,999`, paise only when non-zero); times are IST.

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
