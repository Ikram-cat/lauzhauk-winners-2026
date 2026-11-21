#!/usr/bin/env bash
# Deploy smoke test for the CFF Plan B backend.
# Usage: ./scripts/smoke.sh https://your-service.up.railway.app
# Covers the four endpoints (/health, /api/locations, /api/journeys, /api/model) and their 400/404 paths.
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
    printf '  PASS  %-15s %s\n' "$name" "$code"
  else
    printf '  FAIL  %-15s %s (want %s%s)\n' "$name" "$code" "$want" "${probe:+, body~$probe}"
    printf '        %s\n' "$(printf '%s' "$body" | head -c 200)"
    fail=1
  fi
}

now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }
later_iso() { date -u -d "+${1:-120} min" +%Y-%m-%dT%H:%M:%SZ; }

echo "Smoke test: $BASE (4 endpoints)"
check health        200 "$BASE/health" '"feeds":{"ojp":"[A-Z]*","nova":"[A-Z]*","gtfsRt":{"enabled":'
check health-status 200 "$BASE/health" '"status":"\(OK\|DEGRADED\)"'
check locations     200 "$BASE/api/locations?query=Lausanne" '"coordinates"'
check loc-limit     200 "$BASE/api/locations?query=Lausanne&limit=2" '"locations"'
check loc-limit-bad 400 "$BASE/api/locations?query=Lausanne&limit=21" '"field":"limit"'
check loc-near      200 "$BASE/api/locations?near=46.5197,6.6323&limit=3" '"locations"'
check loc-near-bad  400 "$BASE/api/locations?near=badinput" '"field":"near"'
check loc-near-q    200 "$BASE/api/locations?near=46.5197,6.6323&query=laus" '"locations"'
check journeys      200 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve" '"action":"\(STAY\|REROUTE\|NONE\)"'
check j-status      200 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve" '"status":"\(NO_DISRUPTION\|DISRUPTION_DETECTED\|UNKNOWN\)"'
check j-deadline    200 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&arrivalDeadline=$(later_iso 180)" '"deadlineSummary":{"feasible"'
check j-limit       200 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&limit=1" '"primaryJourney"'
check j-unknown-arg 200 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&foo=bar" '"recommendedJourney"'
check model         200 "$BASE/api/model" '"riskModel":{.*"delayModel":{'
check bad-request   400 "$BASE/api/journeys?origin=Lausanne" '"field":"destination"'
check bad-limit     400 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&limit=11" '"field":"limit"'
check bad-deadline  400 "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&arrivalDeadline=not-a-date" '"field":"arrivalDeadline"'
check duplicate     400 "$BASE/api/journeys?origin=Lausanne&origin=Bern&destination=Gen%C3%A8ve" 'INVALID_REQUEST'
# The mock provider resolves any text, so an unknown place only 404s against live OJP.
if curl -s -m 10 "$BASE/health" | grep -q '"ojp":"DISABLED"'; then
  echo "  SKIP  not-found       (mock provider: every place resolves)"
else
  check not-found     404 "$BASE/api/journeys?origin=Nowhereville-XYZ&destination=Gen%C3%A8ve" '"code":"LOCATION_NOT_FOUND"'
fi
check gone-redirect 404 "$BASE/api/redirect?origin=Lausanne&destination=Gen%C3%A8ve" ''
check gone-alts     404 "$BASE/api/alternatives?from=Gen%C3%A8ve&to=Lausanne&notBefore=$(now_iso)" ''

echo
if [ "$fail" -eq 0 ]; then
  echo "ALL PASS — $BASE is demo-ready"
  exit 0
fi
echo "FAILURES PRESENT — inspect the bodies above"
exit 1
