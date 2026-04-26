# Java Socket Worker Sample

This directory is reserved for the third-party Java socket worker sample.

Planned contract:

- external worker registration with `adapterId=socket`
- realtime presence established through the socket adapter contract
- canonical task-dispatch/task-result semantics without WebSocket fallback
- local execution keyed by `eventCode`
