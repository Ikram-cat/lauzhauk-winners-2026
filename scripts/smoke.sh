#!/usr/bin/env bash
# Deploy smoke test for the CFF Plan B backend.
# Usage: ./scripts/smoke.sh https://your-service.up.railway.app
set -u
BASE="${1:-http://localhost:8080}"
BASE="${BASE%/}"
fail=0

check() { # name, expected_code, url, jq-free body probe
  local name="$1" want="$2" url="$3" probe="${4:-}"
  local code body
  body=$(curl -s -m 30 -w '\n%{http_code}' "$url" 2>/dev/null)
  code=$(printf '%s' "$body" | tail -n1)
  body=$(printf '%s' "$body" | sed '$d')
  if [ "$code" = "$want" ] && { [ -z "$probe" ] || printf '%s' "$body" | grep -q "$probe"; }; then
    printf '  PASS  %-14s %s\n' "$name" "$code"
  else
    printf '  FAIL  %-14s %s (want %s%s)\n' "$name" "$code" "$want" "${probe:+, body~$probe}"
    printf '        %s\n' "$(printf '%s' "$body" | head -c 200)"
    fail=1
  fi
}

now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }
later_iso() { date -u -d "+${1:-120} min" +%Y-%m-%dT%H:%M:%SZ; }

echo "Smoke test: $BASE"
check health        200 "$BASE/health" '"status":"ok"'
check model         200 "$BASE/api/model" '"status":"ok"'
check locations     200 "$BASE/api/locations?query=Lausanne" 'locations'
check journeys      200 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve" 'journeys'
check deadline      200 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&arrivalDeadline=$(later_iso 180)" 'advice'
check alternatives  200 "$BASE/api/alternatives?from=Gen%C3%A8ve&to=Lausanne&notBefore=$(now_iso)" 'alternatives'
check bad-request   400 "$BASE/api/journeys?origin=Lausanne" 'INVALID_REQUEST'
check bad-deadline  400 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&arrivalDeadline=not-a-date" 'INVALID_REQUEST'

echo
if [ "$fail" -eq 0 ]; then
  echo "ALL PASS — $BASE is demo-ready"
  exit 0
fi
echo "FAILURES PRESENT — inspect the bodies above"
exit 1
