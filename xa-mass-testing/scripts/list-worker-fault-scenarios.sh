#!/usr/bin/env bash
# list-worker-fault-scenarios.sh - print the current worker-fault scenario ledger
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CLASSPATH_FILE="${REPO_ROOT}/xa-mass-testing/target/worker-fault-scenarios.classpath"

cd "${REPO_ROOT}"
source "${SCRIPT_DIR}/worker-fault-runtime-classpath.sh"

build_worker_fault_runtime_classpath "${CLASSPATH_FILE}"

java \
  -cp "${RUNTIME_CLASSPATH}" \
  "com.xa.mass.testing.workerfault.WorkerFaultScenarioCli" \
  --list
