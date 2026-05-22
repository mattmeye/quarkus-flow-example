#!/usr/bin/env bash
# End-to-end smoke test: drives the approval workflow from the outside via
# the REST API and verifies the final terminal state.
#
# Usage:
#   task e2e
# Prerequisite:
#   `task dev:backend` (or `task dev`) running in another terminal.

set -euo pipefail

BASE="${BASE:-http://localhost:8080}"

say() { printf "\033[1;36m▶ %s\033[0m\n" "$*"; }
die() { printf "\033[1;31m✗ %s\033[0m\n" "$*" >&2; exit 1; }

require_tool() { command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"; }
require_tool curl
require_tool jq

say "1) Submit new approval request"
CREATE=$(curl -fsS -X POST "$BASE/api/approvals" \
  -H 'Content-Type: application/json' \
  -d '{
        "requester": "smoke-tester",
        "email": "smoke@example.com",
        "subject": "E2E smoke run",
        "description": "Driven by scripts/e2e-smoke.sh",
        "termsAcknowledged": true
      }')
ID=$(echo "$CREATE" | jq -r '.request.id')
TOKEN=$(echo "$CREATE" | jq -r '.request.confirmationToken')
[[ -n "$ID"    && "$ID"    != "null" ]] || die "no request id returned"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || die "no confirmation token returned"
echo "   id=$ID"
echo "   token=$TOKEN"

assert_state() {
  local want="$1"
  local got
  got=$(curl -fsS "$BASE/api/approvals/$ID" | jq -r '.state')
  if [[ "$got" != "$want" ]]; then
    die "expected state $want but got $got"
  fi
  printf "   state = %s ✓\n" "$got"
}

await_state() {
  local want="$1" tries=20
  for ((i=0; i<tries; i++)); do
    local got
    got=$(curl -fsS "$BASE/api/approvals/$ID" | jq -r '.state')
    if [[ "$got" == "$want" ]]; then
      printf "   state = %s ✓\n" "$got"
      return
    fi
    sleep 0.25
  done
  die "timed out waiting for state $want"
}

assert_state AWAITING_CONFIRMATION

say "2) Confirm email + terms"
curl -fsS -X POST "$BASE/api/approvals/$ID/confirm" \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\",\"termsAccepted\":true}" >/dev/null
await_state AWAITING_GROUP1_APPROVAL

say "3) Group 1 approves"
curl -fsS -X POST "$BASE/api/approvals/$ID/group1/decision" \
  -H 'Content-Type: application/json' \
  -d '{"approver":"smoke-g1","decision":"APPROVED"}' >/dev/null
await_state AWAITING_GROUP2_APPROVAL

say "4) Group 2 approves"
curl -fsS -X POST "$BASE/api/approvals/$ID/group2/decision" \
  -H 'Content-Type: application/json' \
  -d '{"approver":"smoke-g2","decision":"APPROVED"}' >/dev/null
await_state APPROVED

printf "\n\033[1;32m✓ End-to-end smoke test passed\033[0m\n"
