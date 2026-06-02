#!/usr/bin/env bash
# run-worker-fault-scenario.sh - run one worker-fault scenario id through the Java ledger
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: xa-mass-testing/scripts/run-worker-fault-scenario.sh <scenario-id> [extra JVM args...]" >&2
  exit 2
fi

SCENARIO_ID="$1"
shift

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CLASSPATH_FILE="${REPO_ROOT}/xa-mass-testing/target/worker-fault-scenario.classpath"

cd "${REPO_ROOT}"
source "${SCRIPT_DIR}/worker-fault-runtime-classpath.sh"
build_worker_fault_runtime_classpath "${CLASSPATH_FILE}"

java \
  -Dmass.sdk.chaos.forceExit=false \
  "$@" \
  -cp "${RUNTIME_CLASSPATH}" \
  "com.xa.mass.testing.workerfault.WorkerFaultScenarioCli" \
  "${SCENARIO_ID}"
