# Gateway Queue Package Baseline

This package contains gateway-side message codec and transport helpers.

## Current Files

- `Envelope`: wrapper used by gateway transport flows.
- `MessageCodec`, `GsonMessageCodec`, `MessageCodecFactory`, `MessageParser`: payload serialization and parsing helpers.
- `EnvelopeMessageTransporter`: queue-backed transporter used by the current gateway/runtime composition.
- `RedisEnvelopeQueue`: placeholder for non-memory queue work; not part of the verified mainline.

## Current Mainline Reality

- The verified runtime path is still the in-memory/embedded path.
- This package helps bridge WebSocket gateway traffic with internal envelope-based processing.
- It does not define task lifecycle, assignment, or result semantics.

## Working Rule

- Keep this package note limited to current files and active role.
- Do not preserve refactor summaries or migration stories here.
- If queue backends become real runtime paths, document that in code and in the runbook, not via historical design notes.
