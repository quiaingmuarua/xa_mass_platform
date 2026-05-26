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

GUARDED_RUNNERS=(
  "${RUNNERS[@]}"
  "com.xa.mass.testing.perf.TaskFlowLoadModelRunner"
)

FORBIDDEN_SMOKE_TOKENS=(
  "TaskMessageProjection"
  "TaskMessageAttemptProjection"
  "getTaskMessage"
  "TaskMessageStats"
  "TaskMessageAttemptStats"
  "ProjectionTestViews"
  "CompatibilityMessageView"
  "CompatibilityAttemptView"
)

echo "== Checking perf smoke source guardrails =="
for runner in "${GUARDED_RUNNERS[@]}"; do
  runner_path="${runner//.//}.java"
  source_file="${REPO_ROOT}/xa-mass-testing/src/main/java/${runner_path}"
  if [[ ! -f "${source_file}" ]]; then
    echo "FAILED: perf smoke runner source not found: ${source_file}"
    exit 1
  fi
  for token in "${FORBIDDEN_SMOKE_TOKENS[@]}"; do
    if grep -nF "${token}" "${source_file}" >/tmp/xa-mass-perf-guard-match.txt; then
      echo "FAILED: ${runner} must stay runtime/timing-first; forbidden token '${token}' found:"
      cat /tmp/xa-mass-perf-guard-match.txt
      rm -f /tmp/xa-mass-perf-guard-match.txt
      exit 1
    fi
  done
done
rm -f /tmp/xa-mass-perf-guard-match.txt

for runner in "${RUNNERS[@]}"; do
  echo "== Running ${runner} =="
  JAVA_ARGS=("$@")
  if [[ "${runner}" == "com.xa.mass.testing.perf.TaskInteractiveRetryWakeupSmokeRunner" ]]; then
    JAVA_ARGS+=(
      "-Dxa.mass.engine.interactiveWorkRetryDelayMillis=${XA_MASS_INTERACTIVE_RETRY_DELAY_MILLIS:-200}"
      "-Dmass.retrywakeup.smoke.minRetryDispatchDelayMillis=${MASS_RETRYWAKEUP_SMOKE_MIN_DELAY_MILLIS:-80}"
    )
  fi
  if [[ ${#JAVA_ARGS[@]} -gt 0 ]]; then
    java "${JAVA_ARGS[@]}" -cp "${RUNTIME_CLASSPATH}" "${runner}"
  else
    java -cp "${RUNTIME_CLASSPATH}" "${runner}"
  fi
done

if [[ -n "${MASS_PERF_TASK_FLOW_BACKENDS:-}" ]]; then
  IFS=',' read -r -a TASK_FLOW_BACKENDS <<< "${MASS_PERF_TASK_FLOW_BACKENDS}"
  for backend in "${TASK_FLOW_BACKENDS[@]}"; do
    backend="$(echo "${backend}" | xargs)"
    if [[ -z "${backend}" ]]; then
      continue
    fi
    echo "== Running TaskFlowLoadModelRunner backend=${backend} =="
    java "$@" \
      "-Dmass.load.runtimeBackend=${backend}" \
      "-Dmass.load.messages=${MASS_PERF_TASK_FLOW_MESSAGES:-64}" \
      "-Dmass.load.workers=${MASS_PERF_TASK_FLOW_WORKERS:-8}" \
      "-Dmass.load.batchSize=${MASS_PERF_TASK_FLOW_BATCH_SIZE:-4}" \
      "-Dmass.load.callbackThreads=${MASS_PERF_TASK_FLOW_CALLBACK_THREADS:-8}" \
      "-Dmass.load.retryFailureEveryNth=${MASS_PERF_TASK_FLOW_RETRY_FAILURE_EVERY_NTH:-0}" \
      "-Dmass.load.duplicateResultEveryNth=${MASS_PERF_TASK_FLOW_DUPLICATE_RESULT_EVERY_NTH:-0}" \
      "-Dmass.load.timeoutSeconds=${MASS_PERF_TASK_FLOW_TIMEOUT_SECONDS:-60}" \
      "-Dmass.load.redisUri=${MASS_PERF_TASK_FLOW_REDIS_URI:-redis://localhost:6379}" \
      -cp "${RUNTIME_CLASSPATH}" \
      com.xa.mass.testing.perf.TaskFlowLoadModelRunner
  done
fi
