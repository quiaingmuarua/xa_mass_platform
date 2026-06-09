#!/usr/bin/env bash
# run-server-default-startup-smoke.sh - no-arg packaged server startup and durable restart proof.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

RUN_ID="default-startup-$(date +%Y%m%d%H%M%S)-$$"
RUN_DIR="${REPO_ROOT}/xa-mass-testing/target/server-default-startup/${RUN_ID}"
WORK_DIR="${RUN_DIR}/work"
SUMMARY_FILE="${RUN_DIR}/summary.json"
BASE_URL="http://127.0.0.1:8088"
SQLITE_PATH="${WORK_DIR}/data/xa-mass-sqlite/xa_mass.db"
OPERATOR_USER="${MASS_OPERATOR_USER:-ops-admin}"
export MASS_OPERATOR_PASSWORD="${MASS_OPERATOR_PASSWORD:-ops-admin}"
SERVER_START_TIMEOUT_SECONDS="${MASS_DEFAULT_STARTUP_SERVER_START_TIMEOUT_SECONDS:-60}"
POST_HEALTH_OBSERVE_SECONDS="${MASS_DEFAULT_STARTUP_POST_HEALTH_OBSERVE_SECONDS:-5}"

SERVER_PID=""
CURRENT_STEP="startup"
RESTART_COUNT=0
FIRST_HEALTH="not-run"
SECOND_HEALTH="not-run"
FIRST_OPERATOR_LOGIN="not-run"
SECOND_OPERATOR_LOGIN="not-run"
LOG_FAILURE_SCAN="not-run"
DEFAULT_PROFILE_LOG_OBSERVED="false"
SQLITE_RESTART_REUSED="false"
REDIS_NAMESPACE_MODE="default"
PORT_PRECHECK="not-run"

mkdir -p "${RUN_DIR}/logs" "${WORK_DIR}"

port_precheck_log="${RUN_DIR}/logs/port-precheck-health.json"
first_server_log="${RUN_DIR}/logs/server-first.log"
second_server_log="${RUN_DIR}/logs/server-second.log"
first_auth_config_log="${RUN_DIR}/logs/auth-config-first.log"
second_auth_config_log="${RUN_DIR}/logs/auth-config-second.log"
first_auth_login_log="${RUN_DIR}/logs/auth-login-first.log"
second_auth_login_log="${RUN_DIR}/logs/auth-login-second.log"

write_summary() {
  local status="$1"
  local category="$2"
  local message="$3"
  cat >"${SUMMARY_FILE}" <<EOF
{
  "status": "${status}",
  "category": "${category}",
  "message": "${message}",
  "baseUrl": "${BASE_URL}",
  "portPrecheck": "${PORT_PRECHECK}",
  "defaultProfile": "durable-local",
  "defaultProfileLogObserved": ${DEFAULT_PROFILE_LOG_OBSERVED},
  "workDir": "${WORK_DIR}",
  "sqlitePath": "${SQLITE_PATH}",
  "restartCount": ${RESTART_COUNT},
  "firstHealth": "${FIRST_HEALTH}",
  "secondHealth": "${SECOND_HEALTH}",
  "firstOperatorLogin": "${FIRST_OPERATOR_LOGIN}",
  "secondOperatorLogin": "${SECOND_OPERATOR_LOGIN}",
  "sameSqliteRestart": ${SQLITE_RESTART_REUSED},
  "redisNamespaceMode": "${REDIS_NAMESPACE_MODE}",
  "logFailureScan": "${LOG_FAILURE_SCAN}",
  "authorizedPositiveChecks": [
    {
      "operation": "server.health",
      "proofLine": "operator-admin-session",
      "credentialFamily": "operator-session",
      "routeFamilies": ["/actuator/health"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${FIRST_HEALTH}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "curl",
      "sourceArtifact": "${first_server_log}"
    },
    {
      "operation": "operator.login",
      "proofLine": "operator-admin-session",
      "credentialFamily": "operator-session",
      "routeFamilies": ["/api/v1/auth"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${FIRST_OPERATOR_LOGIN}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "admin-cli",
      "sourceArtifact": "${first_auth_login_log}"
    },
    {
      "operation": "operator.loginAfterRestart",
      "proofLine": "operator-admin-session",
      "credentialFamily": "operator-session",
      "routeFamilies": ["/api/v1/auth"],
      "authorizationExpectation": "authorized-positive",
      "wrongRejectionProofClass": "product-api-capability",
      "status": "${SECOND_OPERATOR_LOGIN}",
      "claimScope": "valid credential/session must not be wrongly rejected",
      "sourceProcess": "admin-cli",
      "sourceArtifact": "${second_auth_login_log}"
    }
  ],
  "firstServerLog": "${first_server_log}",
  "secondServerLog": "${second_server_log}",
  "portPrecheckLog": "${port_precheck_log}",
  "firstAuthConfigLog": "${first_auth_config_log}",
  "secondAuthConfigLog": "${second_auth_config_log}",
  "firstAuthLoginLog": "${first_auth_login_log}",
  "secondAuthLoginLog": "${second_auth_login_log}"
}
EOF
}

dump_tail() {
  local file="$1"
  if [[ -f "${file}" ]]; then
    echo "--- tail ${file} ---" >&2
    tail -n 120 "${file}" >&2 || true
  fi
}

stop_server() {
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
  SERVER_PID=""
}

cleanup() {
  local exit_code=$?
  stop_server
  if [[ ${exit_code} -ne 0 ]]; then
    write_summary "failed" "${CURRENT_STEP}" "server default startup smoke failed"
    echo "FAILED category=${CURRENT_STEP} runDir=${RUN_DIR}" >&2
    dump_tail "${first_server_log}"
    dump_tail "${second_server_log}"
    dump_tail "${first_auth_config_log}"
    dump_tail "${second_auth_config_log}"
    dump_tail "${first_auth_login_log}"
    dump_tail "${second_auth_login_log}"
  fi
}
trap cleanup EXIT

CURRENT_STEP="port-precheck"
if curl -fsS "${BASE_URL}/actuator/health" >"${port_precheck_log}" 2>&1; then
  PORT_PRECHECK="occupied"
  write_summary "blocked" "${CURRENT_STEP}" "default startup port is already serving health"
  echo "default startup port is already serving health: ${BASE_URL}" >&2
  trap - EXIT
  exit 2
fi
PORT_PRECHECK="passed"

CURRENT_STEP="package"
if [[ "${MASS_DEFAULT_STARTUP_SKIP_PACKAGE:-false}" != "true" ]]; then
  rm -f \
    "${REPO_ROOT}/xa-mass-server/target/xa-mass-server-0.0.1-SNAPSHOT.jar" \
    "${REPO_ROOT}/tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar"
  ./mvnw -q -pl xa-mass-server,tools/xa-mass-admin-cli -am -DskipTests package
fi

SERVER_JAR="${REPO_ROOT}/xa-mass-server/target/xa-mass-server-0.0.1-SNAPSHOT.jar"
ADMIN_JAR="${REPO_ROOT}/tools/xa-mass-admin-cli/target/xa-mass-admin-cli.jar"
for required_file in "${SERVER_JAR}" "${ADMIN_JAR}"; do
  if [[ ! -f "${required_file}" ]]; then
    echo "required artifact not found: ${required_file}" >&2
    exit 1
  fi
done

json_scalar() {
  local field="$1"
  local file="$2"
  sed -n "s/.*\"${field}\":\\(\"[^\"]*\"\\|true\\|false\\|null\\).*/\\1/p" "${file}" | tail -n 1
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

start_server() {
  local log_file="$1"
  pushd "${WORK_DIR}" >/dev/null
  java -jar "${SERVER_JAR}" >"${log_file}" 2>&1 &
  SERVER_PID=$!
  popd >/dev/null
  RESTART_COUNT=$((RESTART_COUNT + 1))
}

wait_for_health() {
  local health_name="$1"
  local log_file="$2"
  local deadline=$((SECONDS + SERVER_START_TIMEOUT_SECONDS))
  until curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; do
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
      echo "server process exited before health" >&2
      return 1
    fi
    if (( SECONDS >= deadline )); then
      echo "timed out waiting for server health" >&2
      return 1
    fi
    sleep 1
  done
  sleep "${POST_HEALTH_OBSERVE_SECONDS}"
  if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
    echo "server process exited after health" >&2
    return 1
  fi
  if grep -q "Application run failed" "${log_file}"; then
    echo "server log contains Application run failed" >&2
    LOG_FAILURE_SCAN="failed"
    return 1
  fi
  LOG_FAILURE_SCAN="passed"
  if grep -q "durable-local" "${log_file}"; then
    DEFAULT_PROFILE_LOG_OBSERVED="true"
  fi
  if [[ "${health_name}" == "first" ]]; then
    FIRST_HEALTH="passed"
  else
    SECOND_HEALTH="passed"
  fi
}

operator_login() {
  local phase="$1"
  local config_log="$2"
  local login_log="$3"
  java -jar "${ADMIN_JAR}" auth config --base-url "${BASE_URL}" >"${config_log}" 2>&1
  if [[ "$(json_string "authMode" "${config_log}")" != "session" ]]; then
    echo "default startup requires session auth; got:" >&2
    cat "${config_log}" >&2
    return 1
  fi
  java -jar "${ADMIN_JAR}" auth login \
    --base-url "${BASE_URL}" \
    --operator-user "${OPERATOR_USER}" \
    --operator-password "${MASS_OPERATOR_PASSWORD}" \
    >"${login_log}" 2>&1
  if [[ "${phase}" == "first" ]]; then
    FIRST_OPERATOR_LOGIN="passed"
  else
    SECOND_OPERATOR_LOGIN="passed"
  fi
}

CURRENT_STEP="first-start"
start_server "${first_server_log}"
wait_for_health "first" "${first_server_log}"
operator_login "first" "${first_auth_config_log}" "${first_auth_login_log}"
if [[ ! -f "${SQLITE_PATH}" ]]; then
  echo "default durable-local SQLite file was not created: ${SQLITE_PATH}" >&2
  exit 1
fi
stop_server

CURRENT_STEP="second-start"
start_server "${second_server_log}"
wait_for_health "second" "${second_server_log}"
operator_login "second" "${second_auth_config_log}" "${second_auth_login_log}"
if [[ -f "${SQLITE_PATH}" ]]; then
  SQLITE_RESTART_REUSED="true"
fi
stop_server

if [[ "${DEFAULT_PROFILE_LOG_OBSERVED}" != "true" ]]; then
  echo "server logs did not show durable-local default profile" >&2
  exit 1
fi
if [[ "${SQLITE_RESTART_REUSED}" != "true" ]]; then
  echo "same SQLite file was not reused across restart" >&2
  exit 1
fi

write_summary "passed" "none" "server default startup smoke passed"
echo "PASSED defaultProfile=durable-local runDir=${RUN_DIR}"
