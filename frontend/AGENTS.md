# Frontend Agent Notes

Status: current frontend owner handoff.

This frontend is a lightweight control console for the orchestration platform. Keep it explicit, domain-shaped, and permission-aware.

## Rules

- Add new pages under domain folders in `src/pages`.
- Add route groups in `src/router/modules/*`, then compose them in `src/router/routes.ts`.
- Every route must define full typed `meta` fields: `title`, `icon`, `order`, `hidden`, `keepAlive`, `requiresAuth`, `permissions`, `menuVisible`.
- Use stable permission strings in `domain:action` form. Do not invent frontend-only aliases.
- Keep API access inside `src/api/*`. Pages should call domain functions, not inline `fetch`.
- Keep runtime flags in `src/app/config.ts`. Do not read `import.meta.env` throughout the app.
- When an API needs mock support, split it into `*.mock.ts`, `*.real.ts`, and a thin public module that selects the implementation.
- SDK/platform discovery catalog must go through `src/api/catalog.ts`; do not duplicate project/event catalogs in pages.
- Treat `Worker.eventBindings` as the capability truth exposed to the UI.
  `supportedEventCodes` and `supportedProjects` are derived convenience fields
  for filtering/display, not separate capability owners.
- Task-create starter examples live in `src/utils/task-starters.ts`. Extend them with explicit project/event cases; do not replace them with a schema-driven form engine.
- Keep page-level view logic inside page SFCs. Do not introduce schema-driven page DSLs or generic CRUD wrappers.
- Extract reusable UI into `src/components` only after repetition is clear.
- Frontend permission checks are UX only. Backend authorization remains the real enforcement boundary.

## Adding A Page

1. Create the page SFC in the correct domain folder.
2. Add or update the domain API module in `src/api`.
3. Add the route in the matching `src/router/modules/*` file with complete `meta`.
4. Use route `permissions` plus `v-permission` for button-level controls when needed.
5. Add focused tests for route visibility, page loading, or permission behavior when the new page changes those areas.
