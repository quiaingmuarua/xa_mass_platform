# Worker Source-Aware Presentation Follow-Up

Status: absorbed by
`roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_ROADMAP.md` WES-4.

Backend `/api/v1/runtime/workers` remains a composite diagnostic route. The
real frontend adapter and type files consume backend-owned source-labeled
worker fields (`runtimeStatus`, `reachability`, `reachable`) and preserve
backend `fieldSources`, so no backend response-shape contract is deferred here.

This file is retained only as the historical frontend-local handoff that WES-4
is closing:

- surface or internally preserve source-aware worker facts on worker
  list/detail/dashboard/project pages
- keep `Worker.eventBindings` as WorkerGroup capability truth
- keep `supportedEventCodes` and `supportedProjects` as derived display/filter
  helpers
- do not create frontend-only worker truth, permission names, or route aliases

Verification target when implemented:

```powershell
cd frontend
corepack pnpm test:run -- workers
corepack pnpm typecheck
corepack pnpm build
```
