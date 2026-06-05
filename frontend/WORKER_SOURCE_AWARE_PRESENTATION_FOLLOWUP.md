# Worker Source-Aware Presentation Follow-Up

Status: frontend-local follow-up.

Backend `/api/v1/runtime/workers` remains a composite diagnostic route. The
real frontend adapter and type files now preserve backend `fieldSources`, so no
backend response-shape contract is deferred here.

This follow-up is only for page presentation polish:

- surface or internally preserve source-aware worker facts on worker list/detail
  pages
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
