#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8088}"
SUBMITTER_KEY="${SUBMITTER_KEY:-external-proof-submitter-key}"
WORKER_KEY="${WORKER_KEY:-external-proof-worker-key}"
WORKER_ID="${WORKER_ID:-external-proof-polling-worker-001}"
WORKER_GROUP_ID="${WORKER_GROUP_ID:-external-proof-polling}"
PROJECT="${PROJECT:-demoApp}"
EVENT_CODE="${EVENT_CODE:-external.proof.echo}"
POLL_TIMEOUT_MS="${POLL_TIMEOUT_MS:-1000}"
POLL_ATTEMPTS="${POLL_ATTEMPTS:-30}"
TERMINAL_ATTEMPTS="${TERMINAL_ATTEMPTS:-60}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing required command: $1" >&2
    exit 2
  fi
}

json_escape() {
  jq -Rn --arg value "$1" '$value'
}

api() {
  local method="$1"
  local path="$2"
  local key="${3:-}"
  local body="${4:-}"
  local response
  local http_code
  local curl_args=(-sS -X "$method" "${BASE_URL}${path}" -H "Content-Type: application/json" -w $'\n%{http_code}')
  if [[ -n "$key" ]]; then
    curl_args+=(-H "X-Mass-Api-Key: ${key}")
  fi
  if [[ -n "$body" ]]; then
    curl_args+=(-d "$body")
  fi
  response="$(curl "${curl_args[@]}")"
  http_code="$(printf '%s' "$response" | tail -n 1)"
  response="$(printf '%s' "$response" | sed '$d')"
  if [[ "$http_code" -lt 200 || "$http_code" -ge 300 ]]; then
    echo "HTTP ${http_code} ${method} ${path}" >&2
    printf '%s\n' "$response" >&2
    exit 1
  fi
  if [[ -n "$response" ]] && [[ "$(jq -r '.code // empty' <<<"$response")" != "0" ]]; then
    echo "API error ${method} ${path}" >&2
    printf '%s\n' "$response" >&2
    exit 1
  fi
  printf '%s' "$response"
}

log() {
  printf '[external-worker-http-contract] %s\n' "$1"
}

require_cmd curl
require_cmd jq

RUN_ID="$(date +%s)-$$"
TARGET="external-proof-target-${RUN_ID}"

log "health check ${BASE_URL}"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

log "register worker ${WORKER_ID}"
api POST "/worker-api/v1/workers" "$WORKER_KEY" "$(cat <<JSON
{
  "workerId": $(json_escape "$WORKER_ID"),
  "workerGroupId": $(json_escape "$WORKER_GROUP_ID"),
  "adapterId": "polling",
  "transportHint": "polling",
  "attributes": {
    "proofLane": "external-worker-http-contract",
    "routingTags": "proof",
    "country": "proof"
  },
  "eventBindings": [
    {
      "eventCode": $(json_escape "$EVENT_CODE"),
      "projectCodes": [$(json_escape "$PROJECT")]
    }
  ]
}
JSON
)" >/dev/null

log "mark worker online and report owner-backed state/capability"
api POST "/worker-api/v1/workers/${WORKER_ID}:online" "$WORKER_KEY" '{"reason":"cli-proof-online"}' >/dev/null
api POST "/worker-api/v1/workers/${WORKER_ID}:heartbeat" "$WORKER_KEY" '{"reason":"cli-proof-heartbeat"}' >/dev/null
api POST "/worker-api/v1/workers/${WORKER_ID}:report-capability" "$WORKER_KEY" "$(cat <<JSON
{
  "availableEventCodes": [$(json_escape "$EVENT_CODE")],
  "schedulingAttributes": {
    "proofLane": "external-worker-http-contract"
  },
  "agentVersion": "cli-proof"
}
JSON
)" >/dev/null
api POST "/worker-api/v1/workers/${WORKER_ID}:report-state" "$WORKER_KEY" "$(cat <<JSON
{
  "state": "AVAILABLE",
  "reason": "cli-proof-available",
  "attributes": {
    "proofLane": "external-worker-http-contract"
  }
}
JSON
)" >/dev/null

log "create task shell"
create_response="$(api POST "/api/v1/tasks" "$SUBMITTER_KEY" "$(cat <<JSON
{
  "project": $(json_escape "$PROJECT"),
  "userId": "external-proof-user",
  "sharedConfig": {
    "routingCode": "proof",
    "proofLane": "external-worker-http-contract",
    "runId": $(json_escape "$RUN_ID")
  },
  "executionSpec": {
    "batchSize": 1,
    "maxRuntimeSeconds": 120
  }
}
JSON
)")"
TASK_ID="$(jq -r '.data.taskId' <<<"$create_response")"
if [[ -z "$TASK_ID" || "$TASK_ID" == "null" ]]; then
  echo "task create response did not include data.taskId" >&2
  printf '%s\n' "$create_response" >&2
  exit 1
fi

log "append item and approve task ${TASK_ID}"
api POST "/api/v1/tasks/${TASK_ID}/items" "$SUBMITTER_KEY" "$(cat <<JSON
{
  "eventCode": $(json_escape "$EVENT_CODE"),
  "items": [
    {
      "target": $(json_escape "$TARGET"),
      "runId": $(json_escape "$RUN_ID")
    }
  ]
}
JSON
)" >/dev/null
api POST "/api/v1/tasks/${TASK_ID}/commands" "$SUBMITTER_KEY" '{"command":"SEAL"}' >/dev/null
api POST "/api/v1/tasks/${TASK_ID}/commands" "$SUBMITTER_KEY" '{"command":"APPROVE"}' >/dev/null

log "poll until dispatch"
dispatch_item=""
for _ in $(seq 1 "$POLL_ATTEMPTS"); do
  poll_response="$(api POST "/worker-api/v1/workers/${WORKER_ID}:poll" "$WORKER_KEY" "$(cat <<JSON
{
  "maxMessages": 1,
  "timeoutMs": ${POLL_TIMEOUT_MS}
}
JSON
)")"
  dispatch_item="$(jq -c --arg taskId "$TASK_ID" '.data.items[]? | select(.taskId == $taskId)' <<<"$poll_response" | head -n 1)"
  if [[ -n "$dispatch_item" ]]; then
    break
  fi
  sleep 1
done

if [[ -z "$dispatch_item" ]]; then
  echo "worker did not receive task dispatch for ${TASK_ID}" >&2
  exit 1
fi

MESSAGE_ID="$(jq -r '.messageId' <<<"$dispatch_item")"
DISPATCH_EVENT_CODE="$(jq -r '.eventCode' <<<"$dispatch_item")"
DISPATCH_WORKER_ID="$(jq -r '.workerId' <<<"$dispatch_item")"
if [[ "$DISPATCH_EVENT_CODE" != "$EVENT_CODE" || "$DISPATCH_WORKER_ID" != "$WORKER_ID" ]]; then
  echo "unexpected dispatch item: ${dispatch_item}" >&2
  exit 1
fi

log "submit result for message ${MESSAGE_ID}"
api POST "/worker-api/v1/workers/${WORKER_ID}:submit-result" "$WORKER_KEY" "$(cat <<JSON
{
  "taskId": $(json_escape "$TASK_ID"),
  "messageId": $(json_escape "$MESSAGE_ID"),
  "success": true,
  "detail": "external-worker-http-contract-success",
  "output": {
    "target": $(json_escape "$TARGET"),
    "handledBy": $(json_escape "$WORKER_ID")
  }
}
JSON
)" >/dev/null

log "wait for terminal task"
terminal_response=""
for _ in $(seq 1 "$TERMINAL_ATTEMPTS"); do
  terminal_response="$(api GET "/api/v1/tasks/${TASK_ID}" "$SUBMITTER_KEY")"
  status="$(jq -r '.data.task.status // .data.status // empty' <<<"$terminal_response")"
  if [[ "$status" == "TERMINAL" ]]; then
    break
  fi
  sleep 1
done

terminal_status="$(jq -r '.data.task.status // .data.status // empty' <<<"$terminal_response")"
terminal_reason="$(jq -r '.data.task.terminalReason // .data.terminalReason // empty' <<<"$terminal_response")"
if [[ "$terminal_status" != "TERMINAL" || "$terminal_reason" != "ALL_MESSAGES_SUCCEEDED" ]]; then
  echo "task did not converge successfully" >&2
  printf '%s\n' "$terminal_response" >&2
  exit 1
fi

log "request and ack worker command"
COMMAND_ID="external-proof-drain-${RUN_ID}"
api POST "/api/v1/runtime/workers/${WORKER_ID}/commands" "" "$(cat <<JSON
{
  "commandId": $(json_escape "$COMMAND_ID"),
  "workerId": $(json_escape "$WORKER_ID"),
  "commandType": "DRAIN",
  "requester": "external-worker-http-contract",
  "reason": "cli proof command ack",
  "idempotencyKey": $(json_escape "$COMMAND_ID")
}
JSON
)" >/dev/null
api POST "/worker-api/v1/workers/${WORKER_ID}/commands/${COMMAND_ID}:ack" "$WORKER_KEY" '{"status":"DELIVERY_ACCEPTED","reason":"cli-proof-ack"}' >/dev/null

log "mark worker offline"
api POST "/worker-api/v1/workers/${WORKER_ID}:offline" "$WORKER_KEY" '{"reason":"cli-proof-offline"}' >/dev/null

log "passed taskId=${TASK_ID} messageId=${MESSAGE_ID} workerId=${WORKER_ID}"
