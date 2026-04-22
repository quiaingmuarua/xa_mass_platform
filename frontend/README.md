# XA Mass Control Console

Lightweight Vue 3 + Vite frontend for the XA Mass worker orchestration platform.

## Principles

- Control-plane UI, not a generic CRUD admin
- Backend remains the source of truth
- Permission-aware from day one
- Route-driven navigation with typed route meta
- Low-magic structure for future agent sessions

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

## Real Backend Mode

Use mock mode for independent frontend CI. For local backend integration, set:

- `VITE_API_BASE_URL=""`
- `VITE_DEV_PROXY_TARGET="http://localhost:8088"`
- `VITE_USE_MOCK_API="false"`
- `VITE_USE_MOCK_AUTH="false"`

The backend currently exposes first-slice JSON APIs under both `/api/*` and
`/status/api/*`. For local `vite dev`, prefer the proxy target above so browser
CORS does not block backend integration. For deployed environments, leave
`VITE_DEV_PROXY_TARGET` unset and configure `VITE_API_BASE_URL` for the served
origin as needed.

## Important Paths

- `src/router/routes.ts`: route tree and typed meta
- `src/router/modules/*`: domain route modules
- `src/app/config.ts`: runtime config and mock flags
- `src/utils/permissions.ts`: permission helpers
- `src/auth/*`: auth provider boundary and permission bridge
- `src/api/*`: domain API modules
- `src/pages/*`: page-level view logic
- `AGENTS.md`: extension rules for future agents
