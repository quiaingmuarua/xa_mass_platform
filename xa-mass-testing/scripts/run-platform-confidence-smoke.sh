#!/usr/bin/env bash
# run-platform-confidence-smoke.sh - packaged server + admin CLI + Java SDK launcher proof.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

PROFILE="memory-local"
PROFILE_ALLOWLIST_FILE="${REPO_ROOT}/xa-mass-testing/proof/platform-confidence-profiles.txt"
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

if [[ ! -f "${PROFILE_ALLOWLIST_FILE}" ]]; then
  echo "profile allowlist not found: ${PROFILE_ALLOWLIST_FILE}" >&2
  exit 2
fi
SUPPORTED_PROFILES=()
while IFS= read -r supported_profile; do
  SUPPORTED_PROFILES+=("${supported_profile}")
done < <(sed 's/#.*//' "${PROFILE_ALLOWLIST_FILE}" | sed '/^[[:space:]]*$/d' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
PROFILE_SUPPORTED=false
for supported_profile in "${SUPPORTED_PROFILES[@]}"; do
  if [[ "${PROFILE}" == "${supported_profile}" ]]; then
    PROFILE_SUPPORTED=true
    break
  fi
done
if [[ "${PROFILE_SUPPORTED}" != "true" ]]; then
  echo "unsupported profile: ${PROFILE}" >&2
  echo "supported profiles: ${SUPPORTED_PROFILES[*]}" >&2
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
admin_api_health_log="${RUN_DIR}/logs/admin-api-health.log"
admin_task_command_log="${RUN_DIR}/logs/admin-task-command.log"
negative_operator_log="${RUN_DIR}/logs/negative-operator-auth.log"
negative_task_key_log="${RUN_DIR}/logs/negative-task-api-key.log"
negative_worker_key_log="${RUN_DIR}/logs/negative-worker-api-key.log"
worker_log="${RUN_DIR}/logs/worker-launcher.log"
task_log="${RUN_DIR}/logs/task-launcher.log"
task_verify_log="${RUN_DIR}/logs/task-result-verifier.log"

AUTH_MODE=""
OPERATOR_HEADER_SUPPORTED="null"
SESSION_COOKIE_SUPPORTED="null"
FIXTURE_HEADER_DISABLED="null"
UNAUTHENTICATED_OPERATOR_CHECK="not-run"
INVALID_TASK_API_KEY_CHECK="not-run"
INVALID_WORKER_API_KEY_CHECK="not-run"
UNAUTHENTICATED_OPERATOR_HTTP_STATUS="null"
INVALID_TASK_API_KEY_HTTP_STATUS="null"
INVALID_WORKER_API_KEY_HTTP_STATUS="null"
UNAUTHENTICATED_OPERATOR_CODE="null"
INVALID_TASK_API_KEY_CODE="null"
INVALID_WORKER_API_KEY_CODE="null"
UNAUTHENTICATED_OPERATOR_REASON=""
INVALID_TASK_API_KEY_REASON=""
INVALID_WORKER_API_KEY_REASON=""

OPERATOR_LOGIN_CHECK="not-run"
OPERATOR_ENV_INIT_CHECK="not-run"
OPERATOR_TASK_APPROVE_CHECK="not-run"
TASK_CREATE_APPEND_CHECK="not-run"
TASK_READ_RESULT_CHECK="not-run"
WORKER_REGISTER_POLL_CHECK="not-run"
WORKER_SUBMIT_RESULT_CHECK="not-run"
API_HEALTH_JSON="null"
ADMIN_ENV_INIT_ELAPSED_MS="null"

json_scalar() {
  local field="$1"
  local file="$2"
  python3 - "$field" "$file" <<'PY'
import json
import sys

field = sys.argv[1]
path = sys.argv[2]
try:
    with open(path, "r", encoding="utf-8") as handle:
        payload = json.load(handle)
except Exception:
    sys.exit(0)
value = payload.get(field)
if isinstance(value, str):
    print(json.dumps(value))
elif isinstance(value, bool):
    print("true" if value else "false")
elif value is None:
    print("null")
else:
    print(value)
PY
}

json_string() {
  local field="$1"
  local file="$2"
  local value
  value="$(json_scalar "${field}" "${file}")"
  value="${value#\"}"
  value="${value%\"}"
  printf '%s' "${value}"
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

java_readable_path() {
  local value="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "${value}"
  else
    printf '%s' "${value}"
  fi
}

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
  "authMode": "${AUTH_MODE}",
  "operatorHeaderSupported": ${OPERATOR_HEADER_SUPPORTED},
  "fixtureHeaderDisabled": ${FIXTURE_HEADER_DISABLED},
  "sessionCookieSupported": ${SESSION_COOKIE_SUPPORTED},
  "adminRouteFamilies": ["/api/v1/auth", "/api/v1/control-plane", "/api/v1/api-keys", "/api/v1/tasks/{taskId}/commands"],
  "sdkRouteFamilies": ["/api/v1/tasks", "/worker-api/v1"],
  "authorizedPositiveChecks": [
    {
      "operation": "operator.login",
      "proofLine": "operator-admin-session",
      "credentialFamily": "operator-session",
      "routeFamilies": ["/api/v1/auth"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${OPERATOR_LOGIN_CHECK}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "admin-cli",
      "sourceArtifact": "${admin_auth_login_log}"
    },
    {
      "operation": "operator.envInit",
      "proofLine": "operator-admin-session",
      "credentialFamily": "operator-session",
      "routeFamilies": ["/api/v1/control-plane", "/api/v1/api-keys"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${OPERATOR_ENV_INIT_CHECK}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "admin-cli",
      "sourceArtifact": "${admin_env_log}"
    },
    {
      "operation": "operator.taskApprove",
      "proofLine": "operator-admin-session",
      "credentialFamily": "operator-session",
      "routeFamilies": ["/api/v1/tasks/{taskId}/commands"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${OPERATOR_TASK_APPROVE_CHECK}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "admin-cli",
      "sourceArtifact": "${admin_task_command_log}"
    },
    {
      "operation": "taskProducer.createAndAppendItems",
      "proofLine": "task-producer-api-key",
      "credentialFamily": "task-api-key",
      "routeFamilies": ["/api/v1/tasks"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${TASK_CREATE_APPEND_CHECK}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "scenario-task-launcher",
      "sourceArtifact": "${task_log}"
    },
    {
      "operation": "taskProducer.readResult",
      "proofLine": "task-producer-api-key",
      "credentialFamily": "task-api-key",
      "routeFamilies": ["/api/v1/tasks"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${TASK_READ_RESULT_CHECK}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "scenario-result-verifier",
      "sourceArtifact": "${task_verify_log}"
    },
    {
      "operation": "worker.registerAndPoll",
      "proofLine": "worker-api-key",
      "credentialFamily": "worker-api-key",
      "routeFamilies": ["/worker-api/v1"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${WORKER_REGISTER_POLL_CHECK}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "scenario-worker-launcher",
      "sourceArtifact": "${worker_log}"
    },
    {
      "operation": "worker.submitResult",
      "proofLine": "worker-api-key",
      "credentialFamily": "worker-api-key",
      "routeFamilies": ["/worker-api/v1"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${WORKER_SUBMIT_RESULT_CHECK}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "scenario-worker-launcher",
      "sourceArtifact": "${worker_log}"
    }
  ],
  "credentialChecks": {
    "unauthenticatedOperatorRoute": {
      "matrixRowId": "unauthenticatedOperatorRoute",
      "operation": "operator.catalogSyncWithoutSession",
      "credentialFamily": "none",
      "routeFamily": "/api/v1/control-plane",
      "proofLine": "authorization-no-bypass-safety",
      "claimScope": "representative missing-session fail-closed check",
      "status": "${UNAUTHENTICATED_OPERATOR_CHECK}",
      "httpStatus": ${UNAUTHENTICATED_OPERATOR_HTTP_STATUS},
      "expectedHttpStatus": 401,
      "code": ${UNAUTHENTICATED_OPERATOR_CODE},
      "expectedCode": 401,
      "expectedReason": "Authentication is required",
      "failureReason": "$(json_escape "${UNAUTHENTICATED_OPERATOR_REASON}")"
    },
    "invalidTaskApiKey": {
      "matrixRowId": "invalidTaskApiKey",
      "operation": "taskProducer.listTasksWithInvalidApiKey",
      "credentialFamily": "task-api-key",
      "routeFamily": "/api/v1/tasks",
      "proofLine": "authorization-no-bypass-safety",
      "claimScope": "representative task credential fail-closed check",
      "status": "${INVALID_TASK_API_KEY_CHECK}",
      "httpStatus": ${INVALID_TASK_API_KEY_HTTP_STATUS},
      "expectedHttpStatus": 401,
      "code": ${INVALID_TASK_API_KEY_CODE},
      "expectedCode": 401,
      "expectedReason": "Invalid or missing API-key credential",
      "failureReason": "$(json_escape "${INVALID_TASK_API_KEY_REASON}")"
    },
    "invalidWorkerApiKey": {
      "matrixRowId": "invalidWorkerApiKey",
      "operation": "worker.pollWithInvalidApiKey",
      "credentialFamily": "worker-api-key",
      "routeFamily": "/worker-api/v1",
      "proofLine": "authorization-no-bypass-safety",
      "claimScope": "representative worker credential fail-closed check",
      "status": "${INVALID_WORKER_API_KEY_CHECK}",
      "httpStatus": ${INVALID_WORKER_API_KEY_HTTP_STATUS},
      "expectedHttpStatus": 401,
      "code": ${INVALID_WORKER_API_KEY_CODE},
      "expectedCode": 401,
      "expectedReason": "Invalid or missing worker credential",
      "failureReason": "$(json_escape "${INVALID_WORKER_API_KEY_REASON}")"
    }
  },
  "confidenceOverlay": {
    "springProfile": "${PROFILE}",
    "operatorMode": "session",
    "allowLocalFixtureHeader": false,
    "operatorCredentialSeed": true
  },
  "initializerElapsedMs": ${ADMIN_ENV_INIT_ELAPSED_MS},
  "apiHealth": ${API_HEALTH_JSON},
  "runDir": "${RUN_DIR}",
  "serverLog": "${server_log}",
  "adminAuthConfigLog": "${admin_auth_config_log}",
  "adminAuthLoginLog": "${admin_auth_login_log}",
  "adminEnvLog": "${admin_env_log}",
  "adminApiHealthLog": "${admin_api_health_log}",
  "adminTaskCommandLog": "${admin_task_command_log}",
  "negativeOperatorLog": "${negative_operator_log}",
  "negativeTaskKeyLog": "${negative_task_key_log}",
  "negativeWorkerKeyLog": "${negative_worker_key_log}",
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
    dump_tail "${admin_api_health_log}"
    dump_tail "${admin_task_command_log}"
    dump_tail "${negative_operator_log}"
    dump_tail "${negative_task_key_log}"
    dump_tail "${negative_worker_key_log}"
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

WORKER_SPEC_FOR_JAVA="$(java_readable_path "${WORKER_SPEC}")"
CATALOG_MANIFEST_FOR_JAVA="$(java_readable_path "${CATALOG_MANIFEST}")"
RULES_MANIFEST_FOR_JAVA="$(java_readable_path "${RULES_MANIFEST}")"
TASK_KEY_FILE_FOR_JAVA="$(java_readable_path "${TASK_KEY_FILE}")"
MARKER_FILE_FOR_JAVA="$(java_readable_path "${MARKER_FILE}")"

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
    "catalogManifest": "${CATALOG_MANIFEST_FOR_JAVA}",
    "rulesManifest": "${RULES_MANIFEST_FOR_JAVA}"
  },
  "credentials": {
    "taskCredential": {
      "apiKeyFile": "${TASK_KEY_FILE_FOR_JAVA}",
      "principalId": "scenario-task-producer",
      "createdForUserId": "${OPERATOR_USER}",
      "permissions": ["task:create", "task:edit", "task:view"],
      "projectScopes": ["crawlerApp"],
      "eventScopes": ["crawler.fetch-page"],
      "rawSecretFile": "${TASK_KEY_FILE_FOR_JAVA}"
    },
    "workerCredentials": {
      "workerSpecFile": "${WORKER_SPEC_FOR_JAVA}",
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
    "markerFile": "${MARKER_FILE_FOR_JAVA}"
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
AUTH_MODE="$(json_string "authMode" "${admin_auth_config_log}")"
OPERATOR_HEADER_SUPPORTED="$(json_scalar "operatorHeaderSupported" "${admin_auth_config_log}")"
SESSION_COOKIE_SUPPORTED="$(json_scalar "sessionCookieSupported" "${admin_auth_config_log}")"
if [[ -z "${OPERATOR_HEADER_SUPPORTED}" ]]; then
  OPERATOR_HEADER_SUPPORTED="null"
fi
if [[ -z "${SESSION_COOKIE_SUPPORTED}" ]]; then
  SESSION_COOKIE_SUPPORTED="null"
fi
if [[ "${OPERATOR_HEADER_SUPPORTED}" == "false" ]]; then
  FIXTURE_HEADER_DISABLED="true"
else
  FIXTURE_HEADER_DISABLED="false"
fi
if [[ "${AUTH_MODE}" != "session" ]]; then
  echo "confidence lane requires session auth; got:" >&2
  cat "${admin_auth_config_log}" >&2
  exit 1
fi
if [[ "${SESSION_COOKIE_SUPPORTED}" != "true" ]]; then
  echo "confidence lane requires session cookies; got:" >&2
  cat "${admin_auth_config_log}" >&2
  exit 1
fi
if [[ "${OPERATOR_HEADER_SUPPORTED}" != "false" ]]; then
  echo "confidence lane requires operator fixture headers to be disabled; got:" >&2
  cat "${admin_auth_config_log}" >&2
  exit 1
fi
java -jar "${ADMIN_JAR}" auth login \
  --base-url "${BASE_URL}" \
  --operator-user "${OPERATOR_USER}" \
  --operator-password "${MASS_OPERATOR_PASSWORD}" \
  >"${admin_auth_login_log}" 2>&1
OPERATOR_LOGIN_CHECK="passed"

CURRENT_STEP="admin-env-init"
admin_env_init_started_seconds=${SECONDS}
java -jar "${ADMIN_JAR}" env init --config "${ADMIN_CONFIG}" >"${admin_env_log}" 2>&1
ADMIN_ENV_INIT_ELAPSED_MS=$(((SECONDS - admin_env_init_started_seconds) * 1000))
OPERATOR_ENV_INIT_CHECK="passed"
if [[ ! -s "${TASK_KEY_FILE}" ]]; then
  echo "task API-key file was not created: ${TASK_KEY_FILE}" >&2
  exit 1
fi
export MASS_TASK_API_KEY
MASS_TASK_API_KEY="$(<"${TASK_KEY_FILE}")"

expect_fail_closed() {
  local variable_prefix="$1"
  local check_name="$2"
  local output_file="$3"
  local expected_status="$4"
  local expected_code="$5"
  local expected_reason="$6"
  shift 6
  local status
  status="$(curl -sS -o "${output_file}" -w "%{http_code}" "$@" || true)"
  local response_code
  local response_reason
  response_code="$(json_scalar "code" "${output_file}")"
  response_reason="$(json_string "msg" "${output_file}")"
  if [[ -z "${response_code}" ]]; then
    response_code="null"
  fi
  case "${variable_prefix}" in
    UNAUTHENTICATED_OPERATOR)
      UNAUTHENTICATED_OPERATOR_HTTP_STATUS="${status}"
      UNAUTHENTICATED_OPERATOR_CODE="${response_code}"
      UNAUTHENTICATED_OPERATOR_REASON="${response_reason}"
      ;;
    INVALID_TASK_API_KEY)
      INVALID_TASK_API_KEY_HTTP_STATUS="${status}"
      INVALID_TASK_API_KEY_CODE="${response_code}"
      INVALID_TASK_API_KEY_REASON="${response_reason}"
      ;;
    INVALID_WORKER_API_KEY)
      INVALID_WORKER_API_KEY_HTTP_STATUS="${status}"
      INVALID_WORKER_API_KEY_CODE="${response_code}"
      INVALID_WORKER_API_KEY_REASON="${response_reason}"
      ;;
    *)
      echo "unknown fail-closed check variable prefix: ${variable_prefix}" >&2
      return 1
      ;;
  esac
  {
    echo
    echo "status=${status}"
    echo "code=${response_code}"
    echo "failureReason=${response_reason}"
  } >>"${output_file}"
  if [[ "${status}" == "${expected_status}" \
        && "${response_code}" == "${expected_code}" \
        && "${response_reason}" == "${expected_reason}" ]]; then
    return 0
  fi
  echo "expected ${expected_status}/${expected_code}/${expected_reason} for ${check_name}; got ${status}/${response_code}/${response_reason}" >&2
  return 1
}

CURRENT_STEP="negative-auth-checks"
if expect_fail_closed "UNAUTHENTICATED_OPERATOR" "unauthenticated operator route" "${negative_operator_log}" \
  "401" "401" "Authentication is required" \
  -X POST \
  -H "Content-Type: application/json" \
  --data '{"projects":[],"events":[]}' \
  "${BASE_URL}/api/v1/control-plane/catalog:sync"; then
  UNAUTHENTICATED_OPERATOR_CHECK="passed"
else
  UNAUTHENTICATED_OPERATOR_CHECK="failed"
  exit 1
fi
if expect_fail_closed "INVALID_TASK_API_KEY" "invalid task API key" "${negative_task_key_log}" \
  "401" "401" "Invalid or missing API-key credential" \
  -H "X-Mass-Api-Key: invalid-task-api-key" \
  "${BASE_URL}/api/v1/tasks"; then
  INVALID_TASK_API_KEY_CHECK="passed"
else
  INVALID_TASK_API_KEY_CHECK="failed"
  exit 1
fi
if expect_fail_closed "INVALID_WORKER_API_KEY" "invalid worker API key" "${negative_worker_key_log}" \
  "401" "401" "Invalid or missing worker credential" \
  -X POST \
  -H "Content-Type: application/json" \
  -H "X-Mass-Api-Key: invalid-worker-api-key" \
  --data '{}' \
  "${BASE_URL}/worker-api/v1/workers/confidence-worker-001:poll"; then
  INVALID_WORKER_API_KEY_CHECK="passed"
else
  INVALID_WORKER_API_KEY_CHECK="failed"
  exit 1
fi

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
WORKER_REGISTER_POLL_CHECK="passed"

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
TASK_CREATE_APPEND_CHECK="passed"

CURRENT_STEP="operator-task-command"
java -jar "${ADMIN_JAR}" task command \
  --base-url "${BASE_URL}" \
  --operator-user "${OPERATOR_USER}" \
  --operator-password "${MASS_OPERATOR_PASSWORD}" \
  --task-id "${TASK_ID}" \
  --command APPROVE \
  >"${admin_task_command_log}" 2>&1
OPERATOR_TASK_APPROVE_CHECK="passed"

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
TASK_READ_RESULT_CHECK="passed"
WORKER_SUBMIT_RESULT_CHECK="passed"

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

write_summary "passed" "none" "platform confidence smoke passed"
echo "PASSED profile=${PROFILE} runDir=${RUN_DIR}"
