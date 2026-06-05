# Server Frontend Static API Docs Inventory

Status: initial current-code inventory for
`SERVER_FRONTEND_STATIC_API_DOCS_ROADMAP.md`. Implementation slices must update
this file from verified code, not from assumptions.

## Surfaces

| Surface | Current Owner | Current Behavior | Classification | Target |
| --- | --- | --- | --- | --- |
| `/v3/api-docs` | `xa-mass-server` | Springdoc OpenAPI JSON export | live OpenAPI source | source for generated static snapshot; exposure decision required |
| `/doc.html` | `xa-mass-server` | Knife4j browser UI | live docs UI | local/operator live docs or explicit public demo; not iframe dependency |
| `frontend/src/pages/system/api-reference/ApiReferencePage.vue` | `frontend` | embeds `VITE_API_DOCS_URL` in iframe except default mock-local case | console docs entry | static docs snapshot entry, no backend iframe |
| `frontend/src/app/config.ts` `VITE_API_DOCS_URL` | `frontend` | docs URL config defaults to `/doc.html#/home` | external docs link config | fallback link only or remove after static snapshot convergence |
| `frontend/public/api-docs/` | `frontend` | not implemented | static review artifact | generated static docs target |
| `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` | `xa-mass-server` | narrative Markdown route reference plus docs handoff text | server-local route reference | owner/category narrative; field-level detail comes from OpenAPI/static docs |
| `doc/FRONTEND_BACKEND_CONTRACT.md` | global contract | server/frontend ownership and API change rules | cross-owner boundary | record static snapshot rules and no frontend API dictionary rule |
| Vercel preview | `frontend` | static SPA/mock review surface | demo/review surface | serve static API docs snapshot without live backend |

## Open Decisions

1. Live `/doc.html` and `/v3/api-docs` exposure:
   public demo, operator-only, profile-gated, or local-only.
2. Static snapshot exposure:
   public/review-safe single snapshot, authenticated-console-only snapshot, or
   split public/internal snapshots.
3. Route categories included in the Vercel static snapshot.
4. Whether `VITE_API_DOCS_URL` remains as an external fallback link after the
   static snapshot exists.
5. Generator location and command ownership:
   frontend script consuming backend OpenAPI, server script writing frontend
   assets, or Maven task with explicit invocation.

## Initial Boundary Decisions

- Server/OpenAPI remains API contract owner.
- Frontend may present generated static docs but must not define route/DTO/auth
  truth.
- Vercel docs are review/demo snapshots, not live backend proof.
- Generation must be explicit. Ordinary Java compile and ordinary frontend
  build should not require a live backend.
- If public/static docs are intended for chatbot/external review, internal
  debug and sensitive operational endpoints need explicit inclusion/exclusion
  before generation.
