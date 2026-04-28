#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ -x "${REPO_ROOT}/mvnw" ]]; then
  MVN_CMD=("${REPO_ROOT}/mvnw")
else
  MVN_CMD=("mvn")
fi

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

cd "${REPO_ROOT}"
"${MVN_CMD[@]}" -pl xa-mass-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dtest=${TEST_ARG}" \
  test
