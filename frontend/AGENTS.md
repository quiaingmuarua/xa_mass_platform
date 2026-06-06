# Frontend Agent Notes

Status: current frontend owner handoff.

This frontend is a lightweight control console for the orchestration platform. Keep it explicit, domain-shaped, and permission-aware.

Use
[CONSOLE_FRONTEND_PRODUCTIONIZATION_ROADMAP.md](./CONSOLE_FRONTEND_PRODUCTIONIZATION_ROADMAP.md)
when the task touches frontend console shell, UX, page maturity, or
productionization direction. Treat it as frontend-local planning context, not
server, SDK, engine, transport, runtime, or trace truth.

Use
[../doc/FRONTEND_BACKEND_CONTRACT.md](../doc/FRONTEND_BACKEND_CONTRACT.md)
when the task touches backend API calls, auth mode handling, permissions,
server DTO shapes, submitter viewer credentials, or frontend mock/real adapter
alignment. The project is server + SDK first; frontend consumes backend
contracts and does not define replacement platform truth.

Positioning:

- kernel/core owns correctness, lifecycle semantics, scheduling correctness,
  and runtime truth boundaries.
- server owns the reference host, product shell, auth/session/CSRF, API
  boundary, server-local control-plane resources, API docs exposure, and
  backend-hosted console assembly.
- SDK owns the main external edit, integration, and automation surface.
- frontend owns presentation, observation, validation, and a lightweight
  operator console.

Frontend work should optimize information architecture, state accuracy,
mock/backend clarity, auth/session UX closure, meaningful dashboards,
debuggable task/worker detail pages, mature audit/API-key/user/role surfaces,
and professional loading/empty/error states. It should not become a broad
editing console or a generic CRUD admin.

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
- Before adding a frontend capability, decide whether it is presentation,
  observation, or validation. If it defines new platform behavior, route shape,
  auth rule, permission, DTO, task/worker truth, or scan-heavy data access, move
  the decision back to the backend contract, SDK, or kernel owner first.
- If a page needs data or an action that the backend does not expose, stop and
  define the backend contract requirement first. Do not add frontend-only route
  aliases, permission names, production mock behavior, or inline `fetch`
  workarounds.

## Adding A Page

1. Create the page SFC in the correct domain folder.
2. Add or update the domain API module in `src/api`.
3. Add the route in the matching `src/router/modules/*` file with complete `meta`.
4. Use route `permissions` plus `v-permission` for button-level controls when needed.
5. Add focused tests for route visibility, page loading, or permission behavior when the new page changes those areas.
