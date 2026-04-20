# Channel Package Baseline

This package contains shared communication primitives used by the current repository.

Treat this README as a local orientation note, not as the system-level source of truth. For platform semantics, start from:

- [../../../../../../../../../AGENTS.md](../../../../../../../../../AGENTS.md)
- [../../../../../../../../../doc/AGENT_BASELINE.md](../../../../../../../../../doc/AGENT_BASELINE.md)
- [../../../../../../../../../doc/TRACE_CONTRACT.md](../../../../../../../../../doc/TRACE_CONTRACT.md)

## Scope

Current subpackages:

- `messaging`: queue and stream abstractions plus in-memory implementations.
- `eventbus`: lightweight event bus facade and platform event types.
- `tranporter`: transport-oriented abstractions used by lower-level runtime code.
- `example`: small package-local examples only.

## Current Role In Mainline

- `eventbus` is used by the active runtime and is still part of the mainline.
- `messaging` provides in-memory communication primitives used in embedded/runtime tests and runtime composition.
- These packages are infrastructure. They do not define task lifecycle semantics.

## Working Rule

- Keep package docs short and current.
- Do not keep refactor stories, migration narratives, or historical comparisons here.
- If runtime behavior changes, update the relevant baseline doc under `doc/` first, then update this package note only if local orientation changed.
