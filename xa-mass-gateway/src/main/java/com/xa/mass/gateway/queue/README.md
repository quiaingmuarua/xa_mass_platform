# Gateway Queue Package Baseline

This package contains gateway-side message codec and transport helpers.

## Current Files

- `MessageCodec`, `GsonMessageCodec`, `MessageCodecFactory`: payload serialization and parsing helpers.
- `RedisEnvelopeQueue`: placeholder for non-memory queue work; not part of the verified mainline.

## Current Mainline Reality

- The verified runtime path is still the in-memory/embedded path.
- This package helps bridge WebSocket gateway traffic with the current raw-json adapter pipeline.
- It does not define task lifecycle, assignment, or result semantics.
- Canonical `eventCode` diagnostics are derived only from explicit frame payload fields when available; transport decoding must not inspect task payload internals to recover it.
- `eventCode` is not a connection/session routing key; connection dispatch still keys off `workerId + connRole`, and `connRole` now defaults to the current task-dispatch lane when omitted by the WebSocket frame.
- `project` is optional scope metadata only; do not inject a synthetic default project in transport helpers.

## Working Rule

- Keep this package note limited to current files and active role.
- Do not preserve refactor summaries or migration stories here.
- If queue backends become real runtime paths, document that in code and in the runbook, not via historical design notes.
