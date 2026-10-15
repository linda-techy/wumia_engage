#!/usr/bin/env bash
# P7-T04, after the burst: the rest of the pass bar, which k6 cannot see.
#
#   TARGET=https://staging.example METRICS_TOKEN=… [PSQL='psql "postgres://…"'] load/k6/after.sh
#
#   1. The inbox backlog drains within 5 minutes (engage_inbox_pending reaches 0).
#   2. No database pool exhaustion (hikaricp timeouts).
#   3. With PSQL set: the cascade ticker is under 30 s behind (oldest due journey step).
# Exit code 0 = pass. Anything it cannot read is a FAIL, never a pass.
set -u
: "${TARGET:?set TARGET}" "${METRICS_TOKEN:?set METRICS_TOKEN (ingest-api /prometheus)}"
TARGET="${TARGET%/}"
DRAIN_LIMIT=${DRAIN_LIMIT:-300}

# metric <name>: the sum of every series of that name (0 when the metric is absent).
# Returns non-zero when /prometheus cannot be read, so a wrong token is not "0 pending".
metric() {
  local body
  body=$(curl -sf -H "Authorization: Bearer $METRICS_TOKEN" "$TARGET/prometheus") || return 1
  awk -v m="$1" '$1 ~ "^"m"({|$)" { s += $2 } END { printf "%.0f\n", s + 0 }' <<<"$body"
}

fail=0
echo "Waiting for the inbox to drain (limit ${DRAIN_LIMIT}s)…"
start=$(date +%s)
while :; do
  if ! pending=$(metric engage_inbox_pending); then
    echo "FAIL cannot read $TARGET/prometheus (is METRICS_TOKEN right?)"; exit 1
  fi
  oldest=$(metric engage_inbox_oldest_pending_seconds) || oldest='?'
  elapsed=$(( $(date +%s) - start ))
  echo "  ${elapsed}s: ${pending} pending, oldest ${oldest}s"
  if [ "$pending" = "0" ]; then echo "PASS inbox drained in ${elapsed}s"; break; fi
  if [ "$elapsed" -ge "$DRAIN_LIMIT" ]; then echo "FAIL inbox still has ${pending} after ${DRAIN_LIMIT}s"; fail=1; break; fi
  sleep 15
done

if ! timeouts=$(metric hikaricp_connections_timeout_total); then
  echo "FAIL cannot read $TARGET/prometheus"; exit 1
fi
if [ "$timeouts" = "0" ]; then echo "PASS no connection-pool timeouts"
else echo "FAIL ${timeouts} connection-pool timeouts"; fail=1; fi

if [ -n "${PSQL:-}" ]; then
  SQL="SELECT COALESCE(round(extract(epoch FROM now() - min(next_step_at))), 0)
         FROM cascade_runs WHERE status IN ('active','waiting') AND next_step_at <= now()"
  # eval, so PSQL may quote a path with spaces: PSQL='"/c/Program Files/…/psql.exe" "postgres://…"'
  lag=$(eval "$PSQL -tAc \"\$SQL\"") && lag=${lag//[[:space:]]/}
  if ! [[ "${lag:-}" =~ ^[0-9]+$ ]]; then
    echo "FAIL could not read the cascade lag (is PSQL right?): ${lag:-no output}"; fail=1
  elif [ "$lag" -lt 30 ]; then echo "PASS cascade ticker ${lag}s behind"
  else echo "FAIL cascade ticker ${lag}s behind (limit 30s)"; fail=1; fi
else
  echo "SKIP cascade ticker lag: set PSQL='psql \"postgres://…\"' to check it"
fi
exit $fail
