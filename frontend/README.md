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

## Important Paths

- `src/router/routes.ts`: route tree and typed meta
- `src/router/modules/*`: domain route modules
- `src/app/config.ts`: runtime config and mock flags
- `src/utils/permissions.ts`: permission helpers
- `src/auth/*`: auth provider boundary and permission bridge
- `src/api/*`: domain API modules
- `src/pages/*`: page-level view logic
- `AGENTS.md`: extension rules for future agents
