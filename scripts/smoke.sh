#!/usr/bin/env bash
# Smoke test for the compose stack: brings it up, issues a key, relays four requests through the gateway
# (plus one through the dashboard's nginx), and checks that each one was recorded with a cost.
# Runs on Linux and on Windows Git Bash. Needs docker compose, curl and python. No paid provider is called.
#   scripts/smoke.sh              keeps the containers and data running afterwards
#   SMOKE_DOWN=1 scripts/smoke.sh tears everything down at the end (volumes included)
set -u
cd "$(dirname "$0")/.."

# On Windows, python3 may be the Microsoft Store stub that prints an install hint, so each candidate must actually run.
PY=""
for candidate in python3 python; do
  if "$candidate" -c "import json" >/dev/null 2>&1; then PY=$candidate; break; fi
done
[ -n "$PY" ] || { echo "python is required"; exit 1; }
if [ -f .env ]; then
  set -a
  . <(sed 's/\r$//' .env)
  set +a
fi
ADMIN=${TOLLGATE_ADMIN_TOKEN:-change-me-to-a-long-random-token}
SERVER=http://localhost:${SERVER_PORT:-8080}
DASH=http://localhost:${DASHBOARD_PORT:-3000}
FAKE=http://localhost:${FAKE_PROVIDER_PORT:-9090}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
failures=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
admin=(-H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json")

# 1. Up, waiting for every health check.
if docker compose up -d --build --wait; then
  pass "docker compose up (all services healthy)"
else
  fail "docker compose up"
  docker compose ps
  docker compose logs --tail 50
  exit 1
fi

# 2. Each service answers.
wait_http() { # url expected-substring label [curl args...]
  local url=$1 expect=$2 label=$3 body=""
  shift 3
  for _ in $(seq 1 30); do
    body=$(curl -s --max-time 5 "$@" "$url" 2>/dev/null) && [[ "$body" == *"$expect"* ]] && { pass "$label"; return 0; }
    sleep 2
  done
  fail "$label (last body: ${body:0:200})"
  return 1
}
wait_http "$SERVER/actuator/health" '"status":"UP"' "server health"
wait_http "$FAKE/actuator/health" '"status":"UP"' "fake provider health"
wait_http "$DASH/" '<div id="root">' "dashboard serves index.html"
wait_http "$DASH/admin/v1/providers" '"name"' "dashboard proxies /admin to the server" "${admin[@]}"

# 3. A project and a key. The key stays in a variable and is never printed.
project=$(curl -s "${admin[@]}" -d "{\"name\":\"smoke-$(date +%Y%m%d-%H%M%S)\"}" "$SERVER/admin/v1/projects" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null)
key=$(curl -s "${admin[@]}" -d '{"name":"smoke"}' "$SERVER/admin/v1/projects/$project/keys" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["key"])' 2>/dev/null)
if [ -n "$project" ] && [ -n "$key" ]; then
  pass "project created and key issued"
else
  fail "project or key (project='$project')"
  exit 1
fi

# 4. Four relay calls plus one through nginx; each must answer 200 with the expected frame and a request id.
ids=()
relay() { # label url body expected-regex
  local label=$1 url=$2 body=$3 expect=$4 code rid
  code=$(curl -s -N --max-time 30 -D "$tmp/h" -o "$tmp/b" -w '%{http_code}' \
    -H "Authorization: Bearer $key" -H "Content-Type: application/json" -H "X-Tollgate-Tag: smoke" -d "$body" "$url")
  rid=$(tr -d '\r' < "$tmp/h" | awk 'tolower($1) == "x-tollgate-request-id:" { print $2 }')
  if [ "$code" = 200 ] && grep -q "$expect" "$tmp/b" && [ -n "$rid" ]; then
    pass "$label (request $rid)"
    ids+=("$rid")
  else
    fail "$label (HTTP $code, body: $(head -c 200 "$tmp/b"))"
  fi
}
relay "OpenAI non-stream" "$SERVER/v1/chat/completions" '{"model":"fake-small","messages":[{"role":"user","content":"hi"}]}' '"usage"'
relay "OpenAI stream" "$SERVER/v1/chat/completions" '{"model":"fake-small","stream":true,"messages":[{"role":"user","content":"hi"}]}' 'data: \[DONE\]'
relay "Anthropic non-stream" "$SERVER/v1/messages" '{"model":"fake-small","max_tokens":32,"messages":[{"role":"user","content":"hi"}]}' '"usage"'
relay "Anthropic stream" "$SERVER/v1/messages" '{"model":"fake-small","max_tokens":32,"stream":true,"messages":[{"role":"user","content":"hi"}]}' 'event: message_stop'
relay "OpenAI stream through nginx" "$DASH/v1/chat/completions" '{"model":"fake-small","stream":true,"messages":[{"role":"user","content":"hi"}]}' 'data: \[DONE\]'

# 5. Every request is recorded with outcome ok and a cost (fake-small is priced in config/prices.example.yaml).
check_records() {
  curl -s "${admin[@]}" "$SERVER/admin/v1/usage/records?projectId=$project&limit=20" | "$PY" -c '
import json, sys
want = sys.argv[1:]
rows = {r["requestId"]: r for r in json.load(sys.stdin)["records"]}
bad = [i for i in want if i not in rows or rows[i]["outcome"] != "ok" or rows[i]["cost"] is None]
print(" ".join(bad))' "${ids[@]}"
}
missing=""
for _ in $(seq 1 10); do
  missing=$(check_records 2>/dev/null)
  [ -z "$missing" ] && break
  sleep 1
done
if [ ${#ids[@]} -eq 5 ] && [ -z "$missing" ]; then
  pass "usage records: 5 requests recorded with outcome ok and a cost"
else
  fail "usage records (recorded ${#ids[@]} ids; missing or without cost: $missing)"
fi
if curl -s "${admin[@]}" "$SERVER/admin/v1/usage/summary?projectId=$project&groupBy=model" | grep -q '"key":"fake-small"'; then
  pass "usage summary groups fake/fake-small"
else
  fail "usage summary"
fi

if [ "${SMOKE_DOWN:-0}" = 1 ]; then
  docker compose down -v
fi

if [ "$failures" -eq 0 ]; then
  echo "SMOKE PASSED"
else
  echo "SMOKE FAILED ($failures)"
  exit 1
fi
