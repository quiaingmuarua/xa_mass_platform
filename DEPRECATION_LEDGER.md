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
- websocket-adapter-local boundary truth now lives in [transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](./transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md) and [transport/TRANSPORT_BOUNDARY_BASELINE.md](./transport/TRANSPORT_BOUNDARY_BASELINE.md)
- call-site counts below are grep-based repo counts captured on 2026-04-26; treat them as migration-tracking numbers, not exact semantic reachability proofs

| Symbol or seam | Current location | Mainline replacement / source of truth | Current in-repo call-site count | Removal condition |
| --- | --- | --- | --- | --- |
| `TaskCompatibilityQueryService` compatibility read seam | `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskCompatibilityQueryService.java` | Runtime truth stays in `TaskWorkRuntime`; outer modules assemble their own DTOs via visitor callbacks instead of importing engine-owned compatibility residue models | 14 grep hits for the current compatibility service seam on 2026-05-08 | SDK/server and any remaining outer callers stop depending on engine-owned compatibility queries at all; compatibility reads become engine-internal residue only |

Current tracked seams:

- engine compatibility query callbacks are temporary migration residue and must not grow into a richer public query model
