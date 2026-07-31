# XA Mass Worker Runtime Viewer

Standalone Vue 3 frontend for the read-only Worker Runtime preview. It is
derived from Pure Admin Thin 6.2.0 but deliberately contains no login, token,
dynamic-permission, fake-user, or example-business path.

## Requirements

- Node 22.19.x
- pnpm 11.9.0
- `server_jvm` on `127.0.0.1:18082` for API mode

## Local run

```text
pnpm install --frozen-lockfile
copy .env.example .env.local
pnpm dev --host 127.0.0.1
```

The default data source is the real API. Vite proxies relative `/api` requests
to `VITE_RUNTIME_PROXY_TARGET`; Server CORS is not enabled.

Use the fixed Mock data only when explicitly requested:

```text
pnpm dev:mock
```

Mock mode never activates as a fallback after a real API failure.

## Verification

```text
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

The page is an unstable, incomplete sample. It does not expose or infer Worker
totals, online state, score, lease, transport sessions, history, or complete
matching results.
