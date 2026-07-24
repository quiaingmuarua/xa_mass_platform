# Legacy Trace Design Assets

Status: historical design asset only.

This document records transferable trace practices from the superseded Java
platform. It is not an active event schema or runtime contract. Complete source
is preserved by `legacy-java-platform-final-2026-07-24`.

## Transferable Practices

- Keep trace evidence append-only, bounded, and non-authoritative.
- Separate the canonical event writer from operator query and diagnosis tools.
- Emit explicit correlation coordinates at the owner boundary that creates
  them; do not reconstruct lifecycle identity from log text.
- Keep overflow behavior explicit so tracing cannot block scheduling truth.
- Query trace artifacts through a structured backend rather than ad hoc grep.
  The historical implementation used JSONL plus DuckDB effectively for local
  bounded analysis.
- Pair scenario analyzers with named executable scenarios. Analyzer output is
  evidence about a scenario, not proof of runtime ownership.
- Validate event shape before running timeline, statistics, or sequence
  analysis.
- Keep trace queries bounded by identity, time, event type, or result limit.

## Do Not Reuse Directly

- Historical event names, lifecycle states, attempt models, and assignment
  vocabulary.
- Scenario analyzers tied to the superseded Java scheduling mechanism.
- Trace fields that mirror owner truth or become scheduling inputs.
- Module boundaries created only by the old Maven reactor.

Future trace work must derive a new event registry from the Kotlin owner
contracts after those contracts pass parity against the Python executable
specification.
