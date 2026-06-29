# XA Mass Engine Starter

Status: current module owner note.

`xa-mass-engine-starter` is the containment module between embedded SDK
assembly and `xa-mass-engine`.

It owns engine process assembly, engine configuration construction, lifecycle
start/stop, and the narrow behavior handles that SDK/starter callers need while
TROM moves task-runtime ownership.

It is not a task-runtime owner, worker-runtime evidence owner, transport
assigned-delivery owner, adapter lifecycle owner, or result reliability owner.
Internal engine-specific wiring may remain here temporarily, but raw
`MassEngine.getConfig()` and `MassApplication.getEngine()` backdoors must not
return as SDK/server reachable surfaces.

Current guard and inventory source:

- [../roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md](../roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md)
- [../roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](../roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md)
