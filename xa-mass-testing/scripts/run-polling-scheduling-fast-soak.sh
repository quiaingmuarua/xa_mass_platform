#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "${SCRIPT_DIR}/run-polling-scheduling-soak.sh" \
  -Dmass.soak.durationSeconds="${MASS_SOAK_DURATION_SECONDS:-20}" \
  -Dmass.soak.workerCount="${MASS_SOAK_WORKER_COUNT:-4}" \
  -Dmass.soak.initialWorkerCount="${MASS_SOAK_INITIAL_WORKER_COUNT:-2}" \
  -Dmass.soak.lateWorkerStartAfterMillis="${MASS_SOAK_LATE_WORKER_START_AFTER_MILLIS:-5000}" \
  -Dmass.soak.requireLateWorkerWork="${MASS_SOAK_REQUIRE_LATE_WORKER_WORK:-true}" \
  -Dmass.soak.groupCount="${MASS_SOAK_GROUP_COUNT:-2}" \
  -Dmass.soak.eventCodeCount="${MASS_SOAK_EVENT_CODE_COUNT:-2}" \
  -Dmass.soak.submitRatePerSecond="${MASS_SOAK_SUBMIT_RATE_PER_SECOND:-5}" \
  -Dmass.soak.messagesPerTask="${MASS_SOAK_MESSAGES_PER_TASK:-4}" \
  -Dmass.soak.pollBatchSize="${MASS_SOAK_POLL_BATCH_SIZE:-2}" \
  -Dmass.soak.processingDelayMillis="${MASS_SOAK_PROCESSING_DELAY_MILLIS:-5}" \
  -Dmass.soak.failureEveryNth="${MASS_SOAK_FAILURE_EVERY_NTH:-2}" \
  -Dmass.soak.drainTimeoutSeconds="${MASS_SOAK_DRAIN_TIMEOUT_SECONDS:-60}" \
  -Dmass.soak.trace="${MASS_SOAK_TRACE:-true}" \
  "$@"
