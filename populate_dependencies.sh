#!/usr/bin/env bash

# 预下载所有模块依赖，便于离线环境构建
set -e

MODULES=("." "xa-mass-api" "xa-mass-eventbus" "xa-mass-engine" \ 
  "xa-mass-gateway" "xa-mass-runtime" "xa-mass-mock")

for module in "${MODULES[@]}"; do
  echo "Prefetching $module"
  (cd "$module" && mvn -B dependency:go-offline > /dev/null)
done

echo "Dependencies cached in $HOME/.m2/repository"



