# Dispatcher Context Package Baseline

This package contains small context interfaces used by gateway dispatch code.

## Current Interfaces

- `SessionContext`
- `TransportContext`
- `CodecContext`
- `HandlerRegistryContext`
- `MiddlewareContext`
- `DispatchRuntimeContext`

These interfaces separate dispatcher concerns without redefining platform architecture.

## Current Role

- They exist to keep gateway dispatch code modular and testable.
- `DispatchRuntimeContext` is the combined view used by internal dispatcher components.
- These interfaces are local gateway abstractions, not platform-wide extension points.

## Boundaries

- Do not turn this package into a second architecture spec.
- Keep semantics of task execution, assignment, and lifecycle outside this package.
- If a context interface stops being useful, remove it rather than preserving historical restructuring notes.
