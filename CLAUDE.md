# CLAUDE.md

Status: thin compatibility handoff.

This repository uses [AGENTS.md](AGENTS.md) as the current agent contract.
Do not treat older copies of this file, generated summaries, or archived
roadmaps as implementation proof.

Before changing behavior, read:

1. [AGENTS.md](AGENTS.md)
2. [doc/AGENT_BASELINE.md](doc/AGENT_BASELINE.md)
3. [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md)
4. [doc/INFRA_TRUTH_LAYERS.md](doc/INFRA_TRUTH_LAYERS.md) when the change
   touches storage, runtime, audit, or observability placement
5. the owning module README or owner contract

Trust order:

1. code
2. verified runtime behavior
3. [AGENTS.md](AGENTS.md)
4. active baseline docs under `doc/` and module `doc/baseline/`
5. module READMEs
6. archived roadmaps only after re-verification

Useful current indexes:

- [doc/README.md](doc/README.md)
- [doc/TESTING_INDEX.md](doc/TESTING_INDEX.md)
- [xa-mass-engine/doc/README.md](xa-mass-engine/doc/README.md)

Archived documents are changelog-style reference only. They are not proof that
the current implementation behaves that way.
