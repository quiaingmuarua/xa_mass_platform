#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CLASSPATH_FILE="${REPO_ROOT}/xa-mass-testing/target/perf-smokes.classpath"

cd "${REPO_ROOT}"

./mvnw -q -pl xa-mass-testing -am -DskipTests install
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

RUNNERS=(
  "com.xa.mass.testing.perf.TaskWorkloadMixSmokeRunner"
  "com.xa.mass.testing.perf.TaskInteractiveRetryWakeupSmokeRunner"
)

for runner in "${RUNNERS[@]}"; do
  echo "== Running ${runner} =="
  JAVA_ARGS=("$@")
  if [[ "${runner}" == "com.xa.mass.testing.perf.TaskInteractiveRetryWakeupSmokeRunner" ]]; then
    JAVA_ARGS+=(
      "-Dxa.mass.engine.interactiveWorkRetryDelayMillis=${XA_MASS_INTERACTIVE_RETRY_DELAY_MILLIS:-200}"
      "-Dmass.retrywakeup.smoke.minRetryDispatchDelayMillis=${MASS_RETRYWAKEUP_SMOKE_MIN_DELAY_MILLIS:-80}"
    )
  fi
  java "${JAVA_ARGS[@]}" -cp "${RUNTIME_CLASSPATH}" "${runner}"
done
