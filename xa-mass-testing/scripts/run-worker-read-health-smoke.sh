#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

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

case "${PROFILE}" in
  memory-local|durable-local)
    ;;
  *)
    echo "unsupported profile: ${PROFILE}" >&2
    exit 2
    ;;
esac

OPERATOR_USER="${MASS_OPERATOR_USER:-ops-admin}"
if [[ -z "${MASS_OPERATOR_PASSWORD:-}" ]]; then
  echo "MASS_OPERATOR_PASSWORD is required" >&2
  exit 2
fi

WORKER_COUNT="${MASS_WORKER_READ_HEALTH_WORKER_COUNT:-100}"
WORKER_GROUP_COUNT="${MASS_WORKER_READ_HEALTH_WORKER_GROUP_COUNT:-5}"
if ! [[ "${WORKER_COUNT}" =~ ^[0-9]+$ ]] || (( WORKER_COUNT < 100 )); then
  echo "MASS_WORKER_READ_HEALTH_WORKER_COUNT must be >= 100" >&2
  exit 2
fi
if ! [[ "${WORKER_GROUP_COUNT}" =~ ^[0-9]+$ ]] || (( WORKER_GROUP_COUNT < 1 )); then
  echo "MASS_WORKER_READ_HEALTH_WORKER_GROUP_COUNT must be >= 1" >&2
  exit 2
fi

RUN_ID="$(date +%Y%m%d%H%M%S)-$$"
RUN_DIR="${REPO_ROOT}/xa-mass-testing/target/worker-read-health/${PROFILE}-${RUN_ID}"
LOG_DIR="${RUN_DIR}/logs"
mkdir -p "${LOG_DIR}" "${RUN_DIR}/scenario" "${RUN_DIR}/secrets" "${RUN_DIR}/state" "${RUN_DIR}/data"

HTTP_PORT="${MASS_WORKER_READ_HEALTH_HTTP_PORT:-$((21080 + ($$ % 1000)))}"
WEBSOCKET_PORT="${MASS_WORKER_READ_HEALTH_WEBSOCKET_PORT:-$((22080 + ($$ % 1000)))}"
BASE_URL="${MASS_WORKER_READ_HEALTH_BASE_URL:-http://127.0.0.1:${HTTP_PORT}}"
SERVER_START_TIMEOUT_SECONDS="${MASS_WORKER_READ_HEALTH_SERVER_START_TIMEOUT_SECONDS:-90}"

SERVER_PID=""
CURRENT_STEP="init"
API_HEALTH_JSON="null"

server_log="${LOG_DIR}/server.log"
admin_env_log="${LOG_DIR}/admin-env-init.log"
admin_api_health_log="${LOG_DIR}/admin-api-health.log"
worker_register_log="${LOG_DIR}/worker-register-api-online.log"

java_readable_path() {
  local raw="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "${raw}"
  else
    printf '%s\n' "${raw}"
  fi
}

json_string() {
  local value="$1"
  printf '%s' "${value}" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

write_summary() {
  local status="$1"
  local category="$2"
  local message="$3"
  cat >"${RUN_DIR}/summary.json" <<EOF
{
  "status": "${status}",
  "category": "${category}",
  "message": "$(json_string "${message}")",
  "profile": "${PROFILE}",
  "runDir": "$(json_string "${RUN_DIR}")",
  "logs": {
    "server": "$(json_string "${server_log}")",
    "adminEnvInit": "$(json_string "${admin_env_log}")",
    "adminApiHealth": "$(json_string "${admin_api_health_log}")",
    "workerRegisterApiOnline": "$(json_string "${worker_register_log}")"
  },
  "workerFixture": {
    "workerCount": ${WORKER_COUNT},
    "workerGroupCount": ${WORKER_GROUP_COUNT},
    "onlineWorkerCount": ${WORKER_COUNT},
    "lockedWorkerCount": 0,
    "sessionCount": 0,
    "creationPath": "worker-api register-api-online-only",
    "startedWorkerSessionCount": 0
  },
  "apiHealth": ${API_HEALTH_JSON}
}
EOF
}

dump_tail() {
  local file="$1"
  if [[ -f "${file}" ]]; then
    echo "---- tail ${file} ----" >&2
    tail -n 80 "${file}" >&2 || true
  fi
}

cleanup() {
  local exit_code=$?
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
  if [[ ${exit_code} -ne 0 ]]; then
    write_summary "failed" "${CURRENT_STEP}" "worker read health smoke failed"
    echo "FAILED category=${CURRENT_STEP} runDir=${RUN_DIR}" >&2
    dump_tail "${server_log}"
    dump_tail "${admin_env_log}"
    dump_tail "${worker_register_log}"
    dump_tail "${admin_api_health_log}"
  fi
}
trap cleanup EXIT

if [[ "${MASS_WORKER_READ_HEALTH_SKIP_PACKAGE:-false}" != "true" ]]; then
  CURRENT_STEP="package"
  rm -f \
    "${REPO_ROOT}/xa-mass-server/target/xa-mass-server-0.0.1-SNAPSHOT.jar" \
    "${REPO_ROOT}/tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar" \
    "${REPO_ROOT}/integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar"
  ./mvnw -q -pl xa-mass-server,tools/xa-mass-admin-cli,integrations/xa-mass-scenario-launcher -am -DskipTests package
fi

SERVER_JAR="${REPO_ROOT}/xa-mass-server/target/xa-mass-server-0.0.1-SNAPSHOT.jar"
ADMIN_JAR="${REPO_ROOT}/tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar"
WORKER_JAR="${REPO_ROOT}/integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar"
CATALOG_MANIFEST="${REPO_ROOT}/integrations/xa-mass-scenario-launcher/examples/scenario.catalog.seed.json"
RULES_MANIFEST="${REPO_ROOT}/integrations/samples/dev/scenario/rules.json"
WORKER_SPEC="${RUN_DIR}/scenario/workers.json"
ADMIN_CONFIG="${RUN_DIR}/admin-env.local.json"
TASK_KEY_FILE="${RUN_DIR}/secrets/task-api-key.txt"
MARKER_FILE="${RUN_DIR}/state/env-init.json"

for required_file in "${SERVER_JAR}" "${ADMIN_JAR}" "${WORKER_JAR}" "${CATALOG_MANIFEST}" "${RULES_MANIFEST}"; do
  if [[ ! -f "${required_file}" ]]; then
    echo "required artifact not found: ${required_file}" >&2
    exit 1
  fi
done

{
  echo "["
  for ((index = 1; index <= WORKER_COUNT; index++)); do
    if (( index > 1 )); then
      echo ","
    fi
    worker_id="$(printf 'worker-read-%04d' "${index}")"
    worker_key="$(printf 'worker-read-%04d-key' "${index}")"
    group_index=$(( (index - 1) % WORKER_GROUP_COUNT ))
    group_id="$(printf 'worker-read-group-%02d' "${group_index}")"
    adapter_node_id="$(printf 'worker-read-node-%02d' "${group_index}")"
    cat <<EOF
  {
    "workerId": "${worker_id}",
    "workerKey": "${worker_key}",
    "workerGroupId": "${group_id}",
    "adapterNodeId": "${adapter_node_id}",
    "adapterId": "polling",
    "transportHint": "polling",
    "startMode": "api-online",
    "attributes": {
      "fixture": "worker-read-health",
      "region": "local",
      "workerIndex": "${index}"
    },
    "eventBindings": [
      {
        "eventCode": "crawler.fetch-page",
        "projectCodes": ["crawlerApp"]
      }
    ]
  }
EOF
  done
  echo
  echo "]"
} >"${WORKER_SPEC}"
printf '[]\n' >"${RUN_DIR}/scenario/tasks.json"

WORKER_SPEC_FOR_JAVA="$(java_readable_path "${WORKER_SPEC}")"
CATALOG_MANIFEST_FOR_JAVA="$(java_readable_path "${CATALOG_MANIFEST}")"
RULES_MANIFEST_FOR_JAVA="$(java_readable_path "${RULES_MANIFEST}")"
TASK_KEY_FILE_FOR_JAVA="$(java_readable_path "${TASK_KEY_FILE}")"
MARKER_FILE_FOR_JAVA="$(java_readable_path "${MARKER_FILE}")"

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
    "catalogManifest": "$(json_string "${CATALOG_MANIFEST_FOR_JAVA}")",
    "rulesManifest": "$(json_string "${RULES_MANIFEST_FOR_JAVA}")"
  },
  "credentials": {
    "taskCredential": {
      "apiKeyFile": "$(json_string "${TASK_KEY_FILE_FOR_JAVA}")",
      "principalId": "worker-read-health-task-producer",
      "createdForUserId": "${OPERATOR_USER}",
      "permissions": ["task:create", "task:edit", "task:view"],
      "projectScopes": ["crawlerApp"],
      "eventScopes": ["crawler.fetch-page"],
      "rawSecretFile": "$(json_string "${TASK_KEY_FILE_FOR_JAVA}")"
    },
    "workerCredentials": {
      "workerSpecFile": "$(json_string "${WORKER_SPEC_FOR_JAVA}")",
      "principalIdTemplate": "worker-read-health-\${workerId}",
      "createdForUserId": "${OPERATOR_USER}",
      "permissions": ["worker:poll"],
      "projectScopesFromWorkerBindings": true,
      "eventScopesFromWorkerBindings": true,
      "rawSecretSource": "workerSpec.workerKey",
      "workerIdAttribute": "workerId",
      "maxWorkers": ${WORKER_COUNT}
    }
  },
  "state": {
    "mode": "file",
    "markerFile": "$(json_string "${MARKER_FILE_FOR_JAVA}")"
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
    "--mass.runtime.redis.namespace=xa:mass:worker-read:${RUN_ID}:runtime"
    "--mass.transport.delivery.redis.namespace=xa:mass:worker-read:${RUN_ID}:delivery"
    "--mass.transport.presence.redis.namespace=xa:mass:worker-read:${RUN_ID}:presence"
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

CURRENT_STEP="admin-env-init"
java -jar "${ADMIN_JAR}" env init --config "${ADMIN_CONFIG}" >"${admin_env_log}" 2>&1

CURRENT_STEP="worker-register-api-online"
java -jar "${WORKER_JAR}" \
  --base-url "${BASE_URL}" \
  --scenario-dir "${RUN_DIR}/scenario" \
  --register-api-online-only \
  >"${worker_register_log}" 2>&1

if ! grep -q "registered api-online workers=${WORKER_COUNT}" "${worker_register_log}" 2>/dev/null; then
  echo "worker launcher did not report expected worker count" >&2
  exit 1
fi

CURRENT_STEP="api-health"
set +e
java -jar "${ADMIN_JAR}" api health --config "${ADMIN_CONFIG}" >"${admin_api_health_log}" 2>&1
api_health_status=$?
set -e
api_health_json_line="$(sed -n '/^{/p' "${admin_api_health_log}" | tail -n 1)"
if [[ -z "${api_health_json_line}" ]]; then
  echo "admin api health did not emit a JSON report" >&2
  cat "${admin_api_health_log}" >&2
  exit 1
fi
API_HEALTH_JSON="${api_health_json_line}"
if [[ ${api_health_status} -ne 0 ]]; then
  echo "admin api health failed" >&2
  cat "${admin_api_health_log}" >&2
  exit 1
fi

write_summary "passed" "none" "worker read health smoke passed"
echo "PASSED profile=${PROFILE} workerCount=${WORKER_COUNT} runDir=${RUN_DIR}"
