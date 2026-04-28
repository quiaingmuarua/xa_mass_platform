#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

TESTS=(
  "NodePollingWorkerBlackBoxIntegrationTest"
  "NodeWebSocketWorkerBlackBoxIntegrationTest"
  "NodeSocketWorkerBlackBoxIntegrationTest"
  "JavaPollingWorkerBlackBoxIntegrationTest"
  "JavaWebSocketWorkerBlackBoxIntegrationTest"
  "JavaSocketWorkerBlackBoxIntegrationTest"
)

TEST_ARG="$(IFS=,; echo "${TESTS[*]}")"

echo "[external-worker-samples] repo root: ${REPO_ROOT}"
echo "[external-worker-samples] tests: ${TEST_ARG}"

to_windows_path() {
  local input_path="$1"
  if command -v wslpath >/dev/null 2>&1; then
    wslpath -w "${input_path}"
    return
  fi
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "${input_path}"
    return
  fi
  if pwd -W >/dev/null 2>&1; then
    (
      cd "${input_path}"
      pwd -W
    )
    return
  fi
  return 1
}

if command -v powershell.exe >/dev/null 2>&1; then
  REPO_ROOT_WIN="$(to_windows_path "${REPO_ROOT}")"
  echo "[external-worker-samples] using Windows PowerShell toolchain"
  powershell.exe -NoProfile -Command \
    "Set-Location '${REPO_ROOT_WIN}'; .\\mvnw.cmd --% -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=${TEST_ARG} test"
  exit $?
fi

if [[ -x "${REPO_ROOT}/mvnw" ]]; then
  MVN_CMD=("${REPO_ROOT}/mvnw")
else
  MVN_CMD=("mvn")
fi

cd "${REPO_ROOT}"
"${MVN_CMD[@]}" -pl xa-mass-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dtest=${TEST_ARG}" \
  test
