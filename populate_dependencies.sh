#!/usr/bin/env bash

# 预下载所有模块依赖，便于离线环境构建
set -e

MODULES=(
  "."
  "xa-mass-core"
  "transport/transport_api"
  "transport/polling-adapter"
  "transport/transport_runtime"
  "xa-mass-engine"
  "transport/websocket-adapter"
  "xa-mass-sdk-api"
  "xa-mass-sdk"
  "xa-mass-testing"
  "xa-mass-dev-app"
)

for module in "${MODULES[@]}"; do
  echo "Prefetching $module"
  (cd "$module" && mvn -B dependency:go-offline > /dev/null)
done

echo "Dependencies cached in $HOME/.m2/repository"

