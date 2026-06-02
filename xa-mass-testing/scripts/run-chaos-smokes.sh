#!/usr/bin/env bash
# run-chaos-smokes.sh - CI chaos smoke gate
#
# Builds xa-mass-testing and runs the designated fast chaos probes.
# Exits non-zero if any probe fails.
#
# Usage:
#   xa-mass-testing/scripts/run-chaos-smokes.sh [extra JVM args...]
#
# Environment overrides:
#   CHAOS_TIMEOUT_SECONDS   - probe timeout (default: 30)
#   CHAOS_PROCESSING_DELAY  - worker processing delay ms (default: 10)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CLASSPATH_FILE="${REPO_ROOT}/xa-mass-testing/target/chaos-smokes.classpath"

cd "${REPO_ROOT}"

echo "== Building xa-mass-testing and sibling modules =="
./mvnw -q -pl xa-mass-testing -am -DskipTests install

./mvnw -q -pl xa-mass-testing \
  dependency:build-classpath \
  -Dmdep.outputFile="${CLASSPATH_FILE}" \
  -Dmdep.pathSeparator=":"

MODULE_CLASSES=(
  "xa-mass-testing/target/classes"
  "sdk/xa-mass-embedded-sdk/target/classes"
  "sdk/xa-mass-embedded-sdk-api/target/classes"
  "xa-mass-engine/target/classes"
  "xa-mass-base/target/classes"
  "transport/transport_runtime/target/classes"
  "transport/transport_api/target/classes"
  "transport/polling-adapter/target/classes"
  "transport/websocket-adapter/target/classes"
  "transport/socket-adapter/target/classes"
  "platform_infra/mass-runtime-api/target/classes"
  "platform_infra/mass-runtime-memory/target/classes"
  "platform_infra/mass-storage-api/target/classes"
  "platform_infra/mass-storage-memory/target/classes"
  "platform_infra/mass-trace-sink/target/classes"
)

RUNTIME_CLASSPATH=""
for module_classpath in "${MODULE_CLASSES[@]}"; do
  if [[ -d "${module_classpath}" ]]; then
    if [[ -z "${RUNTIME_CLASSPATH}" ]]; then
      RUNTIME_CLASSPATH="${module_classpath}"
    else
      RUNTIME_CLASSPATH="${RUNTIME_CLASSPATH}:${module_classpath}"
    fi
  fi
done

if [[ -s "${CLASSPATH_FILE}" ]]; then
  RUNTIME_CLASSPATH="${RUNTIME_CLASSPATH}:$(cat "${CLASSPATH_FILE}")"
fi

TIMEOUT_SECONDS="${CHAOS_TIMEOUT_SECONDS:-30}"
PROCESSING_DELAY="${CHAOS_PROCESSING_DELAY:-10}"

# Scenario rows that must pass on every PR.
# Keep this list fast (< 15 s each). Add new proven-stable runners here after
# they've been validated in at least one scheduled run.
SMOKE_SCENARIOS=(
  "polling-lease-expiry-redispatch"
  "websocket-lease-expiry-redispatch"
  "websocket-late-stale-result-replay"
)

runner_source_for_scenario() {
  java \
    -cp "${RUNTIME_CLASSPATH}" \
    "com.xa.mass.testing.workerfault.WorkerFaultScenarioCli" \
    --runner-class \
    "$1"
}

FORBIDDEN_MAINLINE_TOKENS=(
  "ProjectionTestViews"
  "CompatibilityMessageView"
  "CompatibilityAttemptView"
  "TaskMessageProjection"
  "TaskMessageAttemptProjection"
  "TaskMessageStats"
  "TaskMessageAttemptStats"
  "getTaskMessage"
  "waitForSingleMessage"
  "taskDetailStore()"
)

echo "== Checking chaos smoke source guardrails =="
for scenario in "${SMOKE_SCENARIOS[@]}"; do
  runner="$(runner_source_for_scenario "${scenario}")"
  runner_path="${runner//.//}.java"
  source_file="${REPO_ROOT}/xa-mass-testing/src/main/java/${runner_path}"
  if [[ ! -f "${source_file}" ]]; then
    echo "FAILED: chaos smoke runner source not found: ${source_file}"
    exit 1
  fi
  for token in "${FORBIDDEN_MAINLINE_TOKENS[@]}"; do
    if grep -nF "${token}" "${source_file}" >/tmp/xa-mass-chaos-guard-match.txt; then
      echo "FAILED: ${runner} must stay runtime/aggregate/trace-first; forbidden token '${token}' found:"
      cat /tmp/xa-mass-chaos-guard-match.txt
      rm -f /tmp/xa-mass-chaos-guard-match.txt
      exit 1
    fi
  done
done
rm -f /tmp/xa-mass-chaos-guard-match.txt

FAILED_SCENARIOS=()

for scenario in "${SMOKE_SCENARIOS[@]}"; do
  echo ""
  echo "== Chaos scenario: ${scenario} =="
  set +e
  java \
    -Dmass.sdk.chaos.forceExit=false \
    -Dmass.sdk.chaos.timeoutSeconds="${TIMEOUT_SECONDS}" \
    -Dmass.sdk.chaos.processingDelayMillis="${PROCESSING_DELAY}" \
    "$@" \
    -cp "${RUNTIME_CLASSPATH}" \
    "com.xa.mass.testing.workerfault.WorkerFaultScenarioCli" \
    "${scenario}"
  EXIT_CODE=$?
  set -e
  if [[ ${EXIT_CODE} -ne 0 ]]; then
    echo "FAILED: ${scenario} exited with code ${EXIT_CODE}"
    FAILED_SCENARIOS+=("${scenario}")
  else
    echo "PASSED: ${scenario}"
  fi
done

echo ""
if [[ ${#FAILED_SCENARIOS[@]} -gt 0 ]]; then
  echo "== Chaos smoke gate FAILED: ${#FAILED_SCENARIOS[@]} scenario(s) failed:"
  for r in "${FAILED_SCENARIOS[@]}"; do
    echo "  - ${r}"
  done
  exit 1
else
  echo "== Chaos smoke gate PASSED: all ${#SMOKE_SCENARIOS[@]} scenario(s) succeeded"
fi
