# Roadmaps

Status: current roadmap directory entry.

This directory owns cross-module roadmap, inventory, direction, and deferred
decision documents that have not been archived. Files here are not all active
implementation work. The root `doc/README.md` intentionally does not index every
roadmap: roadmap documents change too often, and stale root indexes mislead new
agents.

## Use

- Use this directory when the task explicitly touches a planned convergence,
  future direction, inventory, or decision record.
- Prefer `rg` or filename search over maintaining a hand-curated list in
  `doc/README.md`.
- Treat roadmap status lines as planning context until verified against code,
  tests, guards, and owner README files.
- Treat `current direction`, `target direction`, and `active deferred decision`
  as constraints or decisions, not as proof of implemented behavior.
- Roadmaps that touch server profiles, `@Configuration`, component-scanned
  beans, constructor `@Value`/`Environment` injection, startup guards,
  seed/import, fail-closed infra checks, or `XaMassServerApplication` assembly
  must include startup/context proof in acceptance. Unit tests that instantiate
  classes directly are not enough for Spring production wiring.
- Move completed records to `doc/archive/` with a date prefix after residue
  scan and link cleanup.

## Classification

- Active implementation roadmap: proposed or accepted slices still need code,
  tests, guards, or owner-doc updates.
- Direction document: current boundary guidance only; it must not be cited as
  current implementation behavior.
- Deferred decision: a recorded owner decision that remains relevant but does
  not start implementation by itself.
- Inventory/checkpoint: keep beside its active parent roadmap; archive it when
  the parent roadmap is archived.

## What Stays Out

- Current global contracts and baselines stay in `doc/`.
- Module-local current truth stays in the owning module README or owner docs.
- Archived records stay in `doc/archive/` and should not be linked from active
  README files.
