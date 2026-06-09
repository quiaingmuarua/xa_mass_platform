#!/usr/bin/env bash
# run-platform-confidence-smoke.sh - packaged server + admin CLI + Java SDK launcher proof.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

PROFILE="memory-local"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --profile=*)
      PROFILE="${1#--profile=}"
      shift
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "${PROFILE}" != "memory-local" && "${PROFILE}" != "durable-local" ]]; then
  echo "unsupported profile: ${PROFILE}" >&2
  exit 2
fi

cd "${REPO_ROOT}"

RUN_ID="${PROFILE}-$(date +%Y%m%d%H%M%S)-$$"
RUN_DIR="${REPO_ROOT}/xa-mass-testing/target/platform-confidence/${RUN_ID}"
SUMMARY_FILE="${RUN_DIR}/summary.json"
mkdir -p "${RUN_DIR}/logs" "${RUN_DIR}/scenario" "${RUN_DIR}/data" "${RUN_DIR}/secrets" "${RUN_DIR}/state"

BASE_URL="${MASS_CONFIDENCE_BASE_URL:-http://127.0.0.1:$((18080 + ($$ % 1000)))}"
HTTP_PORT="${BASE_URL##*:}"
HTTP_PORT="${HTTP_PORT%%/*}"
WEBSOCKET_PORT="${MASS_CONFIDENCE_WEBSOCKET_PORT:-$((19080 + ($$ % 1000)))}"
OPERATOR_USER="${MASS_OPERATOR_USER:-ops-admin}"
export MASS_OPERATOR_PASSWORD="${MASS_OPERATOR_PASSWORD:-ops-admin}"
RESULT_TIMEOUT_SECONDS="${MASS_CONFIDENCE_RESULT_TIMEOUT_SECONDS:-60}"
SERVER_START_TIMEOUT_SECONDS="${MASS_CONFIDENCE_SERVER_START_TIMEOUT_SECONDS:-60}"
WORKER_READY_TIMEOUT_SECONDS="${MASS_CONFIDENCE_WORKER_READY_TIMEOUT_SECONDS:-30}"

SERVER_PID=""
WORKER_PID=""
CURRENT_STEP="startup"

server_log="${RUN_DIR}/logs/server.log"
admin_auth_config_log="${RUN_DIR}/logs/admin-auth-config.log"
admin_auth_login_log="${RUN_DIR}/logs/admin-auth-login.log"
admin_env_log="${RUN_DIR}/logs/admin-env-init.log"
admin_task_command_log="${RUN_DIR}/logs/admin-task-command.log"
worker_log="${RUN_DIR}/logs/worker-launcher.log"
task_log="${RUN_DIR}/logs/task-launcher.log"
task_verify_log="${RUN_DIR}/logs/task-result-verifier.log"

write_summary() {
  local status="$1"
  local category="$2"
  local message="$3"
  cat >"${SUMMARY_FILE}" <<EOF
{
  "status": "${status}",
  "profile": "${PROFILE}",
  "category": "${category}",
  "message": "${message}",
  "runDir": "${RUN_DIR}",
  "serverLog": "${server_log}",
  "adminAuthConfigLog": "${admin_auth_config_log}",
  "adminAuthLoginLog": "${admin_auth_login_log}",
  "adminEnvLog": "${admin_env_log}",
  "adminTaskCommandLog": "${admin_task_command_log}",
  "workerLog": "${worker_log}",
  "taskLog": "${task_log}",
  "taskVerifyLog": "${task_verify_log}"
}
EOF
}

dump_tail() {
  local file="$1"
  if [[ -f "${file}" ]]; then
    echo "--- tail ${file} ---" >&2
    tail -n 80 "${file}" >&2 || true
  fi
}

cleanup() {
  local exit_code=$?
  if [[ -n "${WORKER_PID}" ]] && kill -0 "${WORKER_PID}" 2>/dev/null; then
    kill "${WORKER_PID}" 2>/dev/null || true
    wait "${WORKER_PID}" 2>/dev/null || true
  fi
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
  if [[ ${exit_code} -ne 0 ]]; then
    write_summary "failed" "${CURRENT_STEP}" "platform confidence smoke failed"
    echo "FAILED category=${CURRENT_STEP} runDir=${RUN_DIR}" >&2
    dump_tail "${server_log}"
    dump_tail "${admin_auth_config_log}"
    dump_tail "${admin_auth_login_log}"
    dump_tail "${admin_env_log}"
    dump_tail "${admin_task_command_log}"
    dump_tail "${worker_log}"
    dump_tail "${task_log}"
    dump_tail "${task_verify_log}"
  fi
}
trap cleanup EXIT

if [[ "${MASS_PLATFORM_CONFIDENCE_SKIP_PACKAGE:-false}" != "true" ]]; then
  CURRENT_STEP="package"
  rm -f \
    "${REPO_ROOT}/xa-mass-server/target/xa-mass-server-0.0.1-SNAPSHOT.jar" \
    "${REPO_ROOT}/tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar" \
    "${REPO_ROOT}/integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar" \
    "${REPO_ROOT}/integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar"
  ./mvnw -q -pl xa-mass-server,tools/xa-mass-admin-cli,integrations/xa-mass-scenario-launcher -am -DskipTests package
fi

SERVER_JAR="${REPO_ROOT}/xa-mass-server/target/xa-mass-server-0.0.1-SNAPSHOT.jar"
ADMIN_JAR="${REPO_ROOT}/tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar"
WORKER_JAR="${REPO_ROOT}/integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar"
TASK_JAR="${REPO_ROOT}/integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar"
ADMIN_CONFIG="${RUN_DIR}/admin-env.local.json"
TASK_CONFIG="${REPO_ROOT}/integrations/xa-mass-scenario-launcher/examples/scenario.local.example.json"
WORKER_SPEC="${REPO_ROOT}/tools/xa-mass-admin-cli/examples/workers.confidence.json"
CATALOG_MANIFEST="${REPO_ROOT}/integrations/xa-mass-scenario-launcher/examples/scenario.catalog.seed.json"
RULES_MANIFEST="${REPO_ROOT}/integrations/samples/dev/scenario/rules.json"
TASK_KEY_FILE="${RUN_DIR}/secrets/task-api-key.txt"
MARKER_FILE="${RUN_DIR}/state/env-init.json"

for required_file in "${SERVER_JAR}" "${ADMIN_JAR}" "${WORKER_JAR}" "${TASK_JAR}" "${TASK_CONFIG}" "${WORKER_SPEC}" "${CATALOG_MANIFEST}" "${RULES_MANIFEST}"; do
  if [[ ! -f "${required_file}" ]]; then
    echo "required artifact not found: ${required_file}" >&2
    exit 1
  fi
done

cp "${WORKER_SPEC}" "${RUN_DIR}/scenario/workers.json"
printf '[]\n' >"${RUN_DIR}/scenario/tasks.json"

cat >"${ADMIN_CONFIG}" <<EOF
{
  "server": {
    "baseUrl": "${BASE_URL}",
    "profile": "${PROFILE}"
  },
  "operator": {
    "user": "${OPERATOR_USER}",
    "passwordEnv": "MASS_OPERATOR_PASSWORD"
  },
  "environment": {
    "mode": "apply",
    "catalogManifest": "${CATALOG_MANIFEST}",
    "rulesManifest": "${RULES_MANIFEST}"
  },
  "credentials": {
    "taskCredential": {
      "apiKeyFile": "${TASK_KEY_FILE}",
      "principalId": "scenario-task-producer",
      "createdForUserId": "${OPERATOR_USER}",
      "permissions": ["task:create", "task:edit", "task:view"],
      "projectScopes": ["crawlerApp"],
      "eventScopes": ["crawler.fetch-page"],
      "rawSecretFile": "${TASK_KEY_FILE}"
    },
    "workerCredentials": {
      "workerSpecFile": "${WORKER_SPEC}",
      "principalIdTemplate": "scenario-worker-\${workerId}",
      "createdForUserId": "${OPERATOR_USER}",
      "permissions": ["worker:poll"],
      "projectScopesFromWorkerBindings": true,
      "eventScopesFromWorkerBindings": true,
      "rawSecretSource": "workerSpec.workerKey",
      "workerIdAttribute": "workerId",
      "maxWorkers": 1
    }
  },
  "state": {
    "mode": "file",
    "markerFile": "${MARKER_FILE}"
  },
  "verify": {
    "requiredProjects": ["crawlerApp"],
    "requiredEvents": ["crawler.fetch-page"]
  }
}
EOF

SERVER_ARGS=(
  "--spring.profiles.active=${PROFILE}"
  "--server.port=${HTTP_PORT}"
  "--mass.websocket.port=${WEBSOCKET_PORT}"
  "--mass.control-plane.seed.enabled=true"
  "--mass.control-plane.seed.mode=apply"
  "--mass.control-plane.seed.operator-credentials-location=classpath:control-plane-seed/operator-credentials.json"
  "--mass.auth.operator.mode=session"
  "--mass.auth.operator.allow-local-fixture-header=false"
)

if [[ "${PROFILE}" == "memory-local" ]]; then
  SERVER_ARGS+=(
    "--mass.storage.jdbc.url=jdbc:h2:file:${RUN_DIR}/data/h2/xa_mass;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE"
  )
else
  SERVER_ARGS+=(
    "--mass.storage.jdbc.url=jdbc:sqlite:${RUN_DIR}/data/sqlite/xa_mass.db"
    "--mass.runtime.redis.namespace=xa:mass:confidence:${RUN_ID}:runtime"
    "--mass.transport.delivery.redis.namespace=xa:mass:confidence:${RUN_ID}:delivery"
    "--mass.transport.presence.redis.namespace=xa:mass:confidence:${RUN_ID}:presence"
  )
fi

CURRENT_STEP="server-startup"
java -jar "${SERVER_JAR}" "${SERVER_ARGS[@]}" >"${server_log}" 2>&1 &
SERVER_PID=$!

deadline=$((SECONDS + SERVER_START_TIMEOUT_SECONDS))
until curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; do
  if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
    echo "server process exited before health" >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    echo "timed out waiting for server health" >&2
    exit 1
  fi
  sleep 1
done

CURRENT_STEP="operator-auth"
java -jar "${ADMIN_JAR}" auth config --base-url "${BASE_URL}" >"${admin_auth_config_log}" 2>&1
if ! grep -q '"authMode":"session"' "${admin_auth_config_log}"; then
  echo "confidence lane requires session auth; got:" >&2
  cat "${admin_auth_config_log}" >&2
  exit 1
fi
java -jar "${ADMIN_JAR}" auth login \
  --base-url "${BASE_URL}" \
  --operator-user "${OPERATOR_USER}" \
  --operator-password "${MASS_OPERATOR_PASSWORD}" \
  >"${admin_auth_login_log}" 2>&1

CURRENT_STEP="admin-env-init"
java -jar "${ADMIN_JAR}" env init --config "${ADMIN_CONFIG}" >"${admin_env_log}" 2>&1
if [[ ! -s "${TASK_KEY_FILE}" ]]; then
  echo "task API-key file was not created: ${TASK_KEY_FILE}" >&2
  exit 1
fi
export MASS_TASK_API_KEY
MASS_TASK_API_KEY="$(<"${TASK_KEY_FILE}")"

CURRENT_STEP="worker-launcher"
java -jar "${WORKER_JAR}" \
  --base-url "${BASE_URL}" \
  --scenario-dir "${RUN_DIR}/scenario" \
  --max-polling-workers 1 \
  >"${worker_log}" 2>&1 &
WORKER_PID=$!

deadline=$((SECONDS + WORKER_READY_TIMEOUT_SECONDS))
until grep -q "running workerSessions=1" "${worker_log}" 2>/dev/null; do
  if ! kill -0 "${WORKER_PID}" 2>/dev/null; then
    echo "worker launcher exited before readiness" >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    echo "timed out waiting for worker launcher readiness" >&2
    exit 1
  fi
  sleep 1
done

CURRENT_STEP="task-launcher"
MASS_TASK_API_KEY="${MASS_TASK_API_KEY}" java -jar "${TASK_JAR}" \
  --base-url "${BASE_URL}" \
  --config "${TASK_CONFIG}" \
  >"${task_log}" 2>&1

TASK_ID="$(sed -n 's/.*taskId=\([^ ]*\).*/\1/p' "${task_log}" | tail -n 1)"
if [[ -z "${TASK_ID}" ]]; then
  echo "task launcher did not report a taskId" >&2
  exit 1
fi

CURRENT_STEP="operator-task-command"
java -jar "${ADMIN_JAR}" task command \
  --base-url "${BASE_URL}" \
  --operator-user "${OPERATOR_USER}" \
  --operator-password "${MASS_OPERATOR_PASSWORD}" \
  --task-id "${TASK_ID}" \
  --command APPROVE \
  >"${admin_task_command_log}" 2>&1

CURRENT_STEP="task-result-verify"
MASS_TASK_API_KEY="${MASS_TASK_API_KEY}" java -cp "${TASK_JAR}" \
  com.xa.mass.scenario.ScenarioTaskResultVerifierMain \
  --base-url "${BASE_URL}" \
  --task-api-key "${MASS_TASK_API_KEY}" \
  --task-id "${TASK_ID}" \
  --timeout-seconds "${RESULT_TIMEOUT_SECONDS}" \
  >"${task_verify_log}" 2>&1

if ! grep -q "visible success taskId=" "${task_verify_log}"; then
  CURRENT_STEP="scheduling-result"
  echo "task result verifier did not report visible success" >&2
  exit 1
fi

write_summary "passed" "none" "platform confidence smoke passed"
echo "PASSED profile=${PROFILE} runDir=${RUN_DIR}"
