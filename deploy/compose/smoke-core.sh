#!/usr/bin/env bash
# P1.C4 acceptance check for the compose core profile (ADR-004). Leaves the stack running.
#   deploy/compose/smoke-core.sh
# Checks: build + all healthy, routing through the gateway with one traceId across hops,
# non-root + read-only app dir + JSON-only logs, idle memory, graceful SIGTERM stop (DEBT-4).
# Timings use date, not curl's time_total (which under-reports on WSL2, see PROJECT_STATE).
set -euo pipefail
cd "$(dirname "$0")"
export GIT_SHA="${GIT_SHA:-$(git rev-parse --short HEAD 2>/dev/null || echo dev)}"

compose() { docker compose --profile core "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
now() { date +%s.%N; }
since() { printf '%.1f' "$(echo "$(now) - $1" | bc)"; }
services=(gateway auth-service chat-service)

# The access line is written after the response is flushed, so poll briefly.
await_log() { # $1=service $2=pattern
  for _ in $(seq 1 40); do
    compose logs --no-log-prefix "$1" | grep -q "$2" && return 0
    sleep 0.25
  done
  return 1
}

echo "== build + start, waiting for healthy"
start=$(now)
compose up -d --build --wait --wait-timeout 180 >/dev/null || { compose ps; fail "stack did not become healthy"; }
echo "   all healthy after $(since "$start") s (includes image build)"
compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}' | sed 's/^/   /'

echo "== routing + trace propagation"
for svc in auth chat; do
  headers=$(curl -fsS -D - -o /dev/null "http://localhost:8080/api/$svc/v1/ping") \
    || fail "GET /api/$svc/v1/ping via the gateway"
  trace=$(printf '%s' "$headers" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-trace-id" {print $2}')
  [[ $trace =~ ^[0-9a-f]{32}$ ]] || fail "no X-Trace-Id on /api/$svc/v1/ping"
  await_log gateway "\"traceId\":\"$trace\"" || fail "traceId $trace missing from gateway logs"
  await_log "$svc-service" "\"traceId\":\"$trace\"" || fail "traceId $trace missing from $svc-service logs"
  echo "   /api/$svc/v1/ping 200; traceId $trace in gateway + $svc-service logs"
done

echo "== container hardening"
for s in "${services[@]}"; do
  uid=$(compose exec -T "$s" id -u)
  [[ $uid == 10001 ]] || fail "$s runs as uid $uid, expected 10001"
  if compose exec -T "$s" sh -c 'touch /app/probe' 2>/dev/null; then fail "$s can write /app"; fi
  nonjson=$(compose logs --no-log-prefix "$s" | grep -vc '^{' || true)
  [[ $nonjson == 0 ]] || fail "$s wrote $nonjson non-JSON log lines"
  echo "   $s: uid 10001, /app read-only, JSON-only logs"
done

echo "== idle memory"
docker stats --no-stream --format '{{.Name}}: {{.MemUsage}}' $(compose ps -q) | sed 's/^/   /'

echo "== graceful stop: SIGTERM -> drain -> exit 143 (137 would mean SIGKILL)"
cid=$(compose ps -q chat-service)
start=$(now)
compose stop chat-service >/dev/null 2>&1
took=$(since "$start")
code=$(docker inspect -f '{{.State.ExitCode}}' "$cid")
oom=$(docker inspect -f '{{.State.OOMKilled}}' "$cid")
compose logs --no-log-prefix chat-service | grep -q 'Graceful shutdown complete' || fail "no graceful-shutdown log line"
[[ $code == 143 ]] || fail "chat-service exit code $code"
[[ $oom == false ]] || fail "chat-service was OOM-killed"
echo "   stopped in $took s, exit $code, OOMKilled=$oom"
compose up -d --wait chat-service >/dev/null 2>&1 || fail "chat-service did not come back healthy"

echo "PASS: core profile healthy. Stack left running; stop it with: docker compose --profile core stop"
