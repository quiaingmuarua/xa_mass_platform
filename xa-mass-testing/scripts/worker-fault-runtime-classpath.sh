#!/usr/bin/env bash
# Shared classpath builder for worker-fault scenario scripts.

build_worker_fault_runtime_classpath() {
  local classpath_file="$1"

  ./mvnw -q -pl xa-mass-testing -am -DskipTests install

  ./mvnw -q -pl xa-mass-testing \
    dependency:build-classpath \
    -Dmdep.outputFile="${classpath_file}" \
    -Dmdep.pathSeparator=":"

  local module_classes=(
    "xa-mass-testing/target/classes"
    "sdk/xa-mass-embedded-sdk/target/classes"
    "sdk/xa-mass-embedded-sdk-api/target/classes"
    "xa-mass-engine/target/classes"
    "xa-mass-base/target/classes"
    "transport/transport_runtime/target/classes"
    "transport/transport_api/target/classes"
    "transport/polling-adapter/target/classes"
    "transport/websocket-adapter/target/classes"
    "transport/socket-adapter/target/classes"
    "platform_infra/mass-runtime-api/target/classes"
    "platform_infra/mass-runtime-memory/target/classes"
    "platform_infra/mass-storage-api/target/classes"
    "platform_infra/mass-storage-memory/target/classes"
    "platform_infra/mass-trace-sink/target/classes"
  )

  local runtime_classpath=""
  for module_classpath in "${module_classes[@]}"; do
    if [[ -d "${module_classpath}" ]]; then
      if [[ -z "${runtime_classpath}" ]]; then
        runtime_classpath="${module_classpath}"
      else
        runtime_classpath="${runtime_classpath}:${module_classpath}"
      fi
    fi
  done

  if [[ -s "${classpath_file}" ]]; then
    runtime_classpath="${runtime_classpath}:$(cat "${classpath_file}")"
  fi

  RUNTIME_CLASSPATH="${runtime_classpath}"
}
