#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CLASSPATH_FILE="${REPO_ROOT}/xa-mass-testing/target/sdk-transport-load.classpath"

cd "${REPO_ROOT}"

./mvnw -q -pl xa-mass-testing -am test-compile
./mvnw -q -f xa-mass-testing/pom.xml \
  dependency:build-classpath \
  -Dmdep.outputFile="${CLASSPATH_FILE}" \
  -Dmdep.pathSeparator=":"

MODULE_CLASSES=(
  "xa-mass-testing/target/classes"
  "xa-mass-sdk/target/classes"
  "xa-mass-sdk-api/target/classes"
  "xa-mass-engine/target/classes"
  "xa-mass-core/target/classes"
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

exec java "$@" -cp "${RUNTIME_CLASSPATH}" com.xa.mass.testing.concurrency.SdkTransportLoadRunner
