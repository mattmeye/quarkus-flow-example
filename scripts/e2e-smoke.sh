#!/usr/bin/env bash
# End-to-end smoke test: drives the approval workflow from the outside via
# the generic /api/requests + /api/tasks REST endpoints and verifies the
# final terminal state.
#
# Usage:
#   task e2e
# Prerequisite:
#   `task dev:backend` (or `task dev`) running in another terminal.

set -euo pipefail

BASE="${BASE:-http://localhost:8080}"

say() { printf "\033[1;36m▶ %s\033[0m\n" "$*"; }
die() { printf "\033[1;31m✗ %s\033[0m\n" "$*" >&2; exit 1; }

command -v curl >/dev/null || die "missing tool: curl"
command -v jq   >/dev/null || die "missing tool: jq"

say "1) Submit new approval request"
ID=$(curl -fsS -X POST "$BASE/api/requests" \
       -H 'Content-Type: application/json' \
       -d '{
             "requester": "smoke-tester",
             "email": "smoke@example.com",
             "subject": "E2E smoke run",
             "description": "Driven by scripts/e2e-smoke.sh",
             "termsAcknowledged": true
           }' | jq -r '.id')
[[ -n "$ID" && "$ID" != "null" ]] || die "no request id returned"
echo "   id=$ID"

await_pending_task() {
  local group="$1" tries=20
  for ((i=0; i<tries; i++)); do
    local t
    t=$(curl -fsS "$BASE/api/tasks?status=PENDING&requestId=$ID&group=$group" | jq -r '.[0] // empty')
    if [[ -n "$t" ]]; then
      echo "$t"
      return
    fi
    sleep 0.25
  done
  die "timed out waiting for pending task in group $group"
}

await_state() {
  local want="$1" tries=20
  for ((i=0; i<tries; i++)); do
    local got
    got=$(curl -fsS "$BASE/api/requests/$ID" | jq -r '.state')
    if [[ "$got" == "$want" ]]; then
      printf "   state = %s ✓\n" "$got"
      return
    fi
    sleep 0.25
  done
  die "timed out waiting for state $want"
}

complete_task() {
  local taskId="$1" body="$2"
  curl -fsS -X POST "$BASE/api/tasks/$taskId/complete" \
       -H 'Content-Type: application/json' -d "$body" >/dev/null
}

say "2) Confirm email + accept terms (CONFIRMATION task)"
TASK=$(await_pending_task REQUESTER)
TID=$(echo "$TASK" | jq -r '.id')
TOKEN=$(echo "$TASK" | jq -r '.context.confirmationToken')
[[ -n "$TID" && "$TID" != "null" ]] || die "no task id"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || die "no confirmation token"
echo "   task=$TID token=$TOKEN"
complete_task "$TID" "$(jq -nc --arg t "$TOKEN" '{actor:"smoke", outcome:"CONFIRMED", payload:{token:$t, termsAccepted:true}}')"
await_state AWAITING_GROUP1_APPROVAL

say "3) Approve at GROUP_1 (APPROVAL task)"
TASK=$(await_pending_task GROUP_1)
TID=$(echo "$TASK" | jq -r '.id')
complete_task "$TID" '{"actor":"smoke-g1","outcome":"APPROVED","payload":{}}'
await_state AWAITING_GROUP2_APPROVAL

say "4) Approve at GROUP_2 (APPROVAL task)"
TASK=$(await_pending_task GROUP_2)
TID=$(echo "$TASK" | jq -r '.id')
complete_task "$TID" '{"actor":"smoke-g2","outcome":"APPROVED","payload":{}}'
await_state APPROVED

printf "\n\033[1;32m✓ End-to-end smoke test passed\033[0m\n"
