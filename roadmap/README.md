# Roadmaps

Status: current roadmap directory entry.

This directory owns active and proposed roadmap, inventory, and decision
documents. The root `doc/README.md` intentionally does not index every roadmap:
roadmap documents change too often, and stale root indexes mislead new agents.

## Use

- Use this directory when the task explicitly touches a planned convergence,
  future direction, inventory, or decision record.
- Prefer `rg` or filename search over maintaining a hand-curated list in
  `doc/README.md`.
- Treat roadmap status lines as planning context until verified against code,
  tests, guards, and owner README files.
- Move completed records to `doc/archive/` with a date prefix after residue
  scan and link cleanup.

## What Stays Out

- Current global contracts and baselines stay in `doc/`.
- Module-local current truth stays in the owning module README or owner docs.
- Archived records stay in `doc/archive/` and should not be linked from active
  README files.
