#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CLASSPATH_FILE="${REPO_ROOT}/xa-mass-testing/target/sdk-transport-load.classpath"

cd "${REPO_ROOT}"

./mvnw -q -pl xa-mass-testing -am -DskipTests compile
./mvnw -q -pl xa-mass-testing \
  dependency:build-classpath \
  -Dmdep.outputFile="${CLASSPATH_FILE}" \
  -Dmdep.pathSeparator=":"

MODULE_CLASSES=(
  "xa-mass-testing/target/classes"
  "xa-mass-sdk/target/classes"
  "xa-mass-sdk-api/target/classes"
  "xa-mass-engine/target/classes"
  "xa-mass-base/target/classes"
  "transport/transport_runtime/target/classes"
  "transport/transport_api/target/classes"
  "transport/polling-adapter/target/classes"
  "transport/websocket-adapter/target/classes"
  "transport/socket-adapter/target/classes"
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

FORBIDDEN_TRANSPORT_LOAD_TOKENS=(
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

RUNNER_SOURCE="${REPO_ROOT}/xa-mass-testing/src/main/java/com/xa/mass/testing/concurrency/SdkTransportLoadRunner.java"
echo "== Checking SDK transport load source guardrails =="
if [[ ! -f "${RUNNER_SOURCE}" ]]; then
  echo "FAILED: SDK transport load runner source not found: ${RUNNER_SOURCE}"
  exit 1
fi
for token in "${FORBIDDEN_TRANSPORT_LOAD_TOKENS[@]}"; do
  if grep -nF "${token}" "${RUNNER_SOURCE}" >/tmp/xa-mass-transport-load-guard-match.txt; then
    echo "FAILED: SdkTransportLoadRunner must stay runtime/aggregate/transport-diagnostics first; forbidden token '${token}' found:"
    cat /tmp/xa-mass-transport-load-guard-match.txt
    rm -f /tmp/xa-mass-transport-load-guard-match.txt
    exit 1
  fi
done
rm -f /tmp/xa-mass-transport-load-guard-match.txt

exec java "$@" -cp "${RUNTIME_CLASSPATH}" com.xa.mass.testing.concurrency.SdkTransportLoadRunner
