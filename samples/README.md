# Samples

This directory holds third-party worker samples that are intended to stay runnable,
black-box verifiable, and transport-contract focused.

Rules:

- samples are external worker references, not embedded SDK demos
- samples must speak public transport contracts instead of reusing dev-app mock clients
- integration tests may launch these samples as external processes
- `examples/` remains for lightweight documentation examples and quick snippets

Current mainline:

- `worker-polling/node` is the public polling worker sample
- `worker-polling/java` is the public Java polling worker sample
- `worker-websocket/node` is the public realtime WebSocket worker sample
- `worker-websocket/java` is the public Java realtime WebSocket worker sample

Planned next additions:

- `worker-socket/java`
