#!/usr/bin/env bash
set -euo pipefail
experiment_root="$(cd -- "$(dirname -- "$0")" && pwd)"
cd "$experiment_root"
if [ "$#" -eq 0 ]; then
  set -- --experiment-config integrations/worker-call-performance/configs/capacity-10k.json
fi
exec python3 integrations/worker-call-performance/run_worker_call_performance.py --skip-build "$@"
