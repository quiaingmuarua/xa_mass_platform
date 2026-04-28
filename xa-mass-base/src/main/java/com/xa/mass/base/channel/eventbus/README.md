# EventBus Package Baseline

Status: current local package orientation note.

This package contains the current lightweight event bus abstractions and platform event types used by the active runtime.

## Current Structure

- `core`
  - facades and dispatcher classes such as `EventBusFacade`, `StreamEventBusFacade`, and `MassEventDispatcher`
  - annotation and wrapper support such as `MassSubscribe` and `HandlerWrapper`
- `event`
  - platform event types grouped by domain, including task and worker events

## Current Mainline Use

- The active repository uses the current `channel.eventbus.core` and `channel.eventbus.event` namespaces.
- These classes support runtime notifications and listener wiring.
- They are infrastructure only. Task lifecycle truth still belongs to engine state and persisted models.

## Boundaries

- Do not treat this package as a separate architecture track.
- Do not reintroduce legacy compatibility packages or historical event bus layers.
- Keep examples and API notes close to code; keep lifecycle and runtime truth in `doc/STATE_MACHINE_BASELINE.md` and `doc/TRACE_CONTRACT.md`.
