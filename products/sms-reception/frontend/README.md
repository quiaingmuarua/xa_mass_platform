# SMS Reception frontend

Status: current independent SMS product frontend.

Vue 3, TypeScript and Element Plus provide the workbench, paginated listener
records and business metrics. The product configuration in the composed Server serves its built assets and
the same-origin `/api/v1/sms/*` API. `/sms`, `/sms/listeners` and `/sms/metrics`
are the only SMS SPA pages forwarded there; `/` remains the platform entry.
Unknown APIs remain errors. The `sms-reception` profile gates pages and assets.

This project has its own dependencies, lockfile and build. It keeps the existing
product layout and a small copy of the platform's base theme styles; it does not
load Runtime stores, platform configuration or platform polling. Host controls
remain on the simulator's own HTML page.

With Node 22.19+ (below 25) and Corepack, run from this directory:

```text
corepack pnpm@11.9.0 install --frozen-lockfile
corepack pnpm@11.9.0 lint
corepack pnpm@11.9.0 typecheck
corepack pnpm@11.9.0 test
corepack pnpm@11.9.0 build
```

Output is `dist/` with the `/sms/` asset base, embedded by `distribution/server`
under a dedicated classpath resource location. `pnpm dev` uses
local port 5174 and sends product requests to the local composed Server at 18390.
The production browser uses only same-origin requests. There is no platform
proxy, shared login or cross-backend CORS contract.

See the [product Owner](../README.md) for the business states, cancellation and
uncertainty rules, one-command startup, simulator controls and real-path proof.
