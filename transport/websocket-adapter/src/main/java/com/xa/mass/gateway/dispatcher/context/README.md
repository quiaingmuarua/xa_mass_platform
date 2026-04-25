# Dispatcher Context Baseline

This package keeps the single internal runtime context used by gateway dispatch code:

- `WebSocketDispatchRuntimeContext`

Current role:

- it is a local adapter/runtime seam for gateway internals
- it is not a platform-wide abstraction model
- it should stay as one explicit runtime view rather than being fragmented into many tiny context interfaces

Boundaries:

- do not turn this package into a second architecture spec
- keep task lifecycle and capability semantics outside this package
- if a field stops being useful, remove it instead of rebuilding interface fragmentation
