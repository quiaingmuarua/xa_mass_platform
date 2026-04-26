# Java WebSocket Worker Sample

This directory is reserved for the third-party Java WebSocket worker sample.

Planned contract:

- external worker registration through `/worker-api/workers/register`
- realtime presence established by WebSocket handshake identity
- canonical task-dispatch frame ingest
- canonical task-result frame write-back
- local execution keyed by `eventCode`
