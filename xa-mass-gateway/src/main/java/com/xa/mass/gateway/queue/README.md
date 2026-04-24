# Gateway Queue Package Baseline

This package contains gateway-side message codec and transport helpers.

## Current Files

- `Envelope`: wrapper used by gateway transport flows.
- `MessageCodec`, `GsonMessageCodec`, `MessageCodecFactory`, `MessageParser`: payload serialization and parsing helpers.
- `RedisEnvelopeQueue`: placeholder for non-memory queue work; not part of the verified mainline.

## Current Mainline Reality

- The verified runtime path is still the in-memory/embedded path.
- This package helps bridge WebSocket gateway traffic with internal envelope-based processing.
- It does not define task lifecycle, assignment, or result semantics.
- `Envelope.eventCode` is optional capability metadata derived from the frame payload when available.
- `Envelope.eventCode` is not a connection/session routing key; connection dispatch still keys off `workerId + connRole`, and `connRole` now defaults to the current task-dispatch lane when omitted by the WebSocket frame.
- `Envelope.project` is optional scope metadata only; do not inject a synthetic default project in transport helpers.

## Working Rule

- Keep this package note limited to current files and active role.
- Do not preserve refactor summaries or migration stories here.
- If queue backends become real runtime paths, document that in code and in the runbook, not via historical design notes.
