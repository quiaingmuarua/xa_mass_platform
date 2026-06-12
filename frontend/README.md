# XA Mass Control Console

Status: current frontend owner README.

Lightweight Vue 3 + Vite frontend for the XA Mass worker orchestration platform.

## Principles

- Observation, validation, and lightweight operator console, not the main edit
  surface
- Control-plane UI, not a generic CRUD admin
- Backend remains the source of truth for route behavior, auth, DTOs, and
  runtime/control-plane truth
- SDK remains the main external edit, integration, and automation surface
- Permission-aware from day one
- Route-driven navigation with typed route meta
- Low-magic structure for future agent sessions
- Clear information architecture, accurate state presentation, explicit
  mock/backend mode, meaningful dashboards, useful task/worker debug detail,
  and professional loading/empty/error states

## Commands

- `corepack pnpm install`
- `corepack pnpm dev`
- `corepack pnpm lint`
- `corepack pnpm format:check`
- `corepack pnpm typecheck`
- `corepack pnpm test:run`
- `corepack pnpm build`

## Backend-Hosted Shell

The backend serves the built SPA shell directly from `frontend/dist`.

Recommended local loop:

- terminal 1: start backend on `http://localhost:8088`
- terminal 2: run `corepack pnpm build` inside `frontend/`
- open [http://localhost:8088/](http://localhost:8088/)

Entry-point meaning:

- `http://localhost:8088` is the real backend-hosted console and should be treated as the primary local integration URL
- `http://localhost:5174` is only the Vite dev server when you explicitly run `corepack pnpm dev`
- both URLs serve the same Vue app shell, so similar layout is expected; the meaningful difference is whether the app is running in mock mode or backend mode

Default mode behavior:

- `corepack pnpm dev` defaults to mock API + mock auth unless you override `VITE_USE_MOCK_*`
- `corepack pnpm build` defaults to real backend API + backend auth so the backend-hosted shell on `http://localhost:8088/` uses live server data

## Vercel Preview

Use Vercel as a frontend-only review surface. This is for UI / route / mock data
review, not for hosting the Java server, SQLite control-plane storage, Redis
runtime, or external worker registration.

Recommended Vercel project setup:

- Root Directory: `frontend`
- Build Command: read from `frontend/vercel.json`
- Output Directory: `dist`
- Preview env:
  - `VITE_USE_MOCK_API=true`
  - `VITE_USE_MOCK_AUTH=true`

`frontend/vercel.json` defaults preview builds to mock API + mock auth and adds
an SPA rewrite so direct links such as `/resources/projects` load correctly. It
also uses `ignoreCommand` to skip Git-triggered builds when the frontend has no
changes.
For a real backend-connected deployment, set `VITE_API_BASE_URL` to the backend
origin and override both mock flags to `false`.
Set `VITE_API_DOCS_URL` when the preview should link to a reachable backend
`doc.html` endpoint.

## Real Backend Mode

Use mock mode for independent frontend CI. For local backend integration, set:

- `VITE_API_BASE_URL=""`
- `VITE_API_DOCS_URL="/doc.html#/home"`
- `VITE_DEV_PROXY_TARGET="http://localhost:8088"`
- `VITE_USE_MOCK_API="false"`
- `VITE_USE_MOCK_AUTH="false"`

The backend JSON APIs are versioned under `/api/v1/**`, `/worker-api/v1/**`,
and `/internal/v1/**`. For local `vite dev`, prefer the proxy target above so
browser CORS does not block backend integration. For deployed environments,
leave `VITE_DEV_PROXY_TARGET` unset and configure `VITE_API_BASE_URL` for the
served origin as needed.

The operator console exposes `System -> API Reference`, which embeds
`VITE_API_DOCS_URL`. In the backend-hosted shell this defaults to the same-origin
server docs at `/doc.html#/home`. In Vercel/mock preview, configure
`VITE_API_DOCS_URL` to an externally reachable backend docs URL or the page will
show an unavailable state instead of copying API documentation into frontend
source.

## Important Paths

- `src/router/routes.ts`: route tree and typed meta
- `src/router/modules/*`: domain route modules
- `src/app/config.ts`: runtime config and mock flags
- `src/utils/permissions.ts`: permission helpers
- `src/auth/*`: auth provider boundary and permission bridge
- `src/api/*`: domain API modules
- `src/pages/*`: page-level view logic
- `WORKER_SOURCE_AWARE_PRESENTATION_FOLLOWUP.md`: historical worker page
  presentation handoff absorbed by WES-4; current pages consume backend-owned
  source-labeled worker fields and preserve `fieldSources`
- `AGENTS.md`: extension rules for future agents
