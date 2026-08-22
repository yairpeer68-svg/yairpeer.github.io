# Admin Console

The admin console is a React/TypeScript/Vite application using Material UI. It exposes operational views for dashboard/system status, users, devices, AI usage, quotas, feature flags, security events, audit logs, app versions, subscriptions, payments and notifications.

### Engineering controls

**Engineering projects** creates a project, imports its source ZIP through the browser file
picker, starts a run and cancels an active one.

**Engineering approvals** lists every run paused on a command decision and approves or rejects
it inline. Because `ENGINEERING_AUTO_EXECUTE_COMMANDS` is `false` by default, a run that requests
a command stops until someone decides; the panel refreshes every ten seconds and the run is
automatically re-queued once the last approval is decided. Approving never widens the execution
policy — a command that is not allow-listed stays blocked no matter who approves it.

An administrator decides approvals through `POST /admin/engineering/approvals/{id}/decision`,
which works for any user's run and records the decision against the administrator. The
user-scoped endpoint only reaches the run's owner.

Admin status is checked by the backend on every protected admin request. Frontend route visibility is not an authorization boundary.

## Authentication storage

Access and refresh tokens are held only in JavaScript memory and omitted from browser persistent storage. This means a page reload intentionally requires a fresh login. The API uses bearer authorization and `credentials: omit`; no authentication cookie is used, so cookie-CSRF protection is not the authentication mechanism.

## Build

```bash
npm ci
npm run lint
npm run test
npm run build
```

`package-lock.json` is committed, so `npm ci` gives a reproducible install.

The build sets `base: '/admin/'` because Nginx serves the console from that prefix. Changing the
mount point requires changing both `vite.config.ts` and the Nginx `location`; leaving them
mismatched produces a blank page, since the asset URLs will not resolve.

Set `VITE_API_BASE_URL` only when the API is on a different origin during development. In the container build, Nginx serves `/admin/` and proxies `/api/` on the same origin.
