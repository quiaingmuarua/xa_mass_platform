# xa-mass-engine archive/v2

This directory preserves the historical `com.xa.mass.engine.v2` experiment.

Current status:

- not part of the active source tree
- not part of current regression scope
- kept only for historical reference and selective migration

Why it was moved:

- `v2` was repeatedly mistaken for the active engine implementation
- some archived tests/examples depend on removed messaging packages
- keeping it under `src/main/java` and `src/test/java` created avoidable agent confusion

For current engine work, start with:

- `../README.md`
- `../src/main/java/com/xa/mass/engine/TaskManager.java`
- `../../AGENTS.md`
- `../../doc/AGENT_BASELINE.md`
