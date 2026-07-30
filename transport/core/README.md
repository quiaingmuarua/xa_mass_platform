# Transport Core

`transport:core` is the Java 11 local contract shared by Worker transport
implementations and platform-specific network clients.

It owns:

- Worker event definitions, parameter resolution, dispatch, and error
  classification.
- The transport-neutral `WorkerCommandExecutor`.
- String-only point, text WebSocket, and line socket client interfaces.

It does not own network implementations, Worker transport state machines,
Adapter behavior, process lifecycle, Redis access, or Kernel scheduling.

The WebSocket client contract requires serialized listener callbacks, stale
connection isolation, thread-safe non-blocking `send`, idempotent lifecycle,
and no Worker business-message cache.
