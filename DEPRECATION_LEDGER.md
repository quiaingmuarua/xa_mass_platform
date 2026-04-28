# Deprecation Ledger

Last updated: 2026-04-27

Status: current repo-level deprecation and compatibility index.

This is the single repo-level index for deprecated, compatibility, and legacy seams that still exist in active paths.

Use it to answer three questions quickly:

- is this seam still allowed to grow?
- what is the identified mainline replacement or source of truth?
- what has to happen before the old seam can be removed?

Rules:

- list only seams that are either explicitly deprecated or intentionally constrained compatibility paths
- update this ledger when a seam is newly deprecated, gains a clearer replacement, or materially drops in call-site count
- use this ledger for staged breaking refactors immediately when `@Deprecated` is introduced; deprecated seams should move toward caller migration and removal, not remain as long-lived parallel mainlines
- websocket-adapter-local class-by-class migration detail lives in [transport/refactor/WEBSOCKET_ADAPTER_CURRENT_INVENTORY.md](./transport/refactor/WEBSOCKET_ADAPTER_CURRENT_INVENTORY.md)
- call-site counts below are grep-based repo counts captured on 2026-04-26; treat them as migration-tracking numbers, not exact semantic reachability proofs

| Symbol or seam | Current location | Mainline replacement / source of truth | Current in-repo call-site count | Removal condition |
| --- | --- | --- | --- | --- |

Current tracked seams: none.
