#!/usr/bin/env bash

# Bash/curl equivalent of correctness-burst.mjs. Requires bash, curl, jq, xargs.
# Requests are never retried. The script creates a fresh show and leaves its
# successful holds/bookings in place; use a test deployment.

set -u

HOT_SEAT_COUNT=${HOT_SEAT_COUNT:-8}
QUOTA_SEAT_COUNT=10
BASE_URL=${BASE_URL:-https://ticketmaster-api-7s47.onrender.com}
REQUESTS=${REQUESTS:-1000}
CONCURRENCY=${CONCURRENCY:-$REQUESTS}
TOKEN_CONCURRENCY=${TOKEN_CONCURRENCY:-25}
SAMPLE_INTERVAL_MS=${SAMPLE_INTERVAL_MS:-100}
TIMEOUT_MS=${TIMEOUT_MS:-30000}
USER_PREFIX=${USER_PREFIX:-correctness-$(date +%s)}
LOG_FILE=${LOG_FILE:-logs/correctness-burst-$(date -u +%Y-%m-%dT%H-%M-%SZ).log}

# xargs relaunches this file in a lightweight worker mode. Handle that before
# parsing user options or opening the main log in each worker process.
worker_request() {
  local method=$1 url=$2 token=$3 body=$4 key=$5 timeout=$6 bodyfile=$7 errfile=$8
  local meta status elapsed curlrc
  local -a args
  args=(--silent --show-error --max-time "$timeout" --output "$bodyfile" --write-out '%{http_code}\t%{time_total}')
  [[ -n "$token" ]] && args+=(--header "Authorization: Bearer $token")
  if [[ -n "$body" ]]; then args+=(--header 'Content-Type: application/json' --data-binary "$body"); fi
  [[ -n "$key" ]] && args+=(--header "Idempotency-Key: $key")
  meta=$(curl "${args[@]}" --request "$method" "$url" 2>"$errfile")
  curlrc=$?
  status=${meta%%$'\t'*}; elapsed=${meta#*$'\t'}
  if (( curlrc != 0 )) || [[ ! "$status" =~ ^[0-9]+$ ]]; then status=0; fi
  [[ -f "$bodyfile" ]] || : > "$bodyfile"
  awk -v s="${elapsed:-0}" 'BEGIN { printf "%.1f", s * 1000 }' > "${bodyfile%.json}.ms"
  printf '%s\n' "$status" > "${bodyfile%.json}.status"
}

if [[ "${1:-}" == --worker-token ]]; then
  shift
  base=$1; timeout=$2; workdir=$3; token_dir=$4; stats=$5; index=$6; user=$7; role=$8
  mkdir -p "$token_dir"
  body=$(jq -cn --arg id "$user" --arg role "$role" '{user_id:$id,role:$role}')
  out="$workdir/token.$index.json"
  worker_request POST "$base/auth/token" '' "$body" '' "$timeout" "$out" "$workdir/token.$index.err"
  status=$(cat "${out%.json}.status"); elapsed=$(cat "${out%.json}.ms")
  printf 'token-%s %s %s\n' "$role" "$status" "$elapsed" >> "$stats"
  if [[ "$status" == 200 ]]; then
    jq -er '.token | strings | select(length > 0)' "$out" > "$token_dir/token.$index" 2>/dev/null || exit 1
  else
    : > "$token_dir/token.$index"
  fi
  exit 0
fi

if [[ "${1:-}" == --worker-reserve ]]; then
  shift
  base=$1; timeout=$2; workdir=$3; show=$4; stats=$5
  index=$6; seat=$7; token_file=$8; key=$9; spoof=${10}; label=${11}
  token=$(cat "$token_file")
  body=$(jq -cn --arg seat "$seat" --arg key "$key" --arg spoof "$spoof" \
    '{seats:[$seat]} + (if $key != "" then {idempotency_key:$key} else {} end) + (if $spoof != "" then {user_id:$spoof} else {} end)')
  out="$workdir/$label.$index.json"
  worker_request POST "$base/shows/$show/reserve" "$token" "$body" "$key" "$timeout" "$out" "$workdir/$label.$index.err"
  status=$(cat "${out%.json}.status"); elapsed=$(cat "${out%.json}.ms")
  printf '%s %s %s\n' "$label" "$status" "$elapsed" >> "$stats"
  exit 0
fi

usage() {
  cat <<'EOF'
Usage: bash scripts/correctness-burst.sh [options]

Creates an isolated show, sends concurrent hot-seat reservations, samples
inventory during the burst, then checks idempotency, user quota and identity.

Options:
  --base-url URL             App root
  --requests N               Number of hot-seat reserve calls (default: 1000)
  --concurrency N            Simultaneous hot-seat calls (default: requests)
  --token-concurrency N      Parallel token creation (default: 25)
  --hot-seat-count N         Hot-seat pool size (default: 8)
  --sample-interval-ms N     Inventory polling cadence (default: 100)
  --timeout-ms N             Per-request timeout (default: 30000)
  --user-prefix PREFIX       Prefix for generated test user IDs
  --log-file FILE            Log destination (default: timestamped file in logs/)
  --help                     Show this help

Environment equivalents: BASE_URL, REQUESTS, CONCURRENCY, TOKEN_CONCURRENCY,
HOT_SEAT_COUNT, SAMPLE_INTERVAL_MS, TIMEOUT_MS, USER_PREFIX, LOG_FILE.
Dependencies: bash, curl, jq, xargs. Requests are not retried.
EOF
}

fail_arg() { printf 'Error: %s\n' "$1" >&2; exit 2; }
while (($#)); do
  case "$1" in
    --base-url) (($# >= 2)) || fail_arg 'missing value for --base-url'; BASE_URL=$2; shift 2 ;;
    --requests) (($# >= 2)) || fail_arg 'missing value for --requests'; REQUESTS=$2; shift 2 ;;
    --concurrency) (($# >= 2)) || fail_arg 'missing value for --concurrency'; CONCURRENCY=$2; shift 2 ;;
    --token-concurrency) (($# >= 2)) || fail_arg 'missing value for --token-concurrency'; TOKEN_CONCURRENCY=$2; shift 2 ;;
    --hot-seat-count) (($# >= 2)) || fail_arg 'missing value for --hot-seat-count'; HOT_SEAT_COUNT=$2; shift 2 ;;
    --sample-interval-ms) (($# >= 2)) || fail_arg 'missing value for --sample-interval-ms'; SAMPLE_INTERVAL_MS=$2; shift 2 ;;
    --timeout-ms) (($# >= 2)) || fail_arg 'missing value for --timeout-ms'; TIMEOUT_MS=$2; shift 2 ;;
    --user-prefix) (($# >= 2)) || fail_arg 'missing value for --user-prefix'; USER_PREFIX=$2; shift 2 ;;
    --log-file) (($# >= 2)) || fail_arg 'missing value for --log-file'; LOG_FILE=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) fail_arg "unknown option: $1" ;;
  esac
done

is_positive_int() { [[ "$1" =~ ^[1-9][0-9]*$ ]]; }
for pair in "REQUESTS:$REQUESTS" "CONCURRENCY:$CONCURRENCY" \
  "TOKEN_CONCURRENCY:$TOKEN_CONCURRENCY" "SAMPLE_INTERVAL_MS:$SAMPLE_INTERVAL_MS" \
  "TIMEOUT_MS:$TIMEOUT_MS" "HOT_SEAT_COUNT:$HOT_SEAT_COUNT"; do
  name=${pair%%:*}; value=${pair#*:}
  is_positive_int "$value" || fail_arg "$name must be a positive integer"
done
(( HOT_SEAT_COUNT >= 2 )) || fail_arg 'HOT_SEAT_COUNT must be at least 2'
(( REQUESTS >= HOT_SEAT_COUNT )) || fail_arg 'REQUESTS must be at least HOT_SEAT_COUNT'
[[ "$USER_PREFIX" != *[[:space:]]* ]] || fail_arg 'USER_PREFIX cannot contain whitespace'
[[ "$BASE_URL" != *[[:space:]]* ]] || fail_arg 'BASE_URL cannot contain whitespace'
BASE_URL=${BASE_URL%/}
command -v curl >/dev/null || fail_arg 'curl is required'
command -v jq >/dev/null || fail_arg 'jq is required'
command -v xargs >/dev/null || fail_arg 'xargs is required'

if [[ ! -x "$0" ]]; then
  SELF=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
else
  SELF=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
fi
mkdir -p "$(dirname "$LOG_FILE")" || exit 1
LOG_FILE=$(cd "$(dirname "$LOG_FILE")" && pwd)/$(basename "$LOG_FILE")
exec > >(tee -a "$LOG_FILE") 2>&1
umask 077
WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/correctness-burst.XXXXXX") || exit 1
trap 'rm -rf "$WORKDIR"' EXIT HUP INT TERM
STATS_FILE="$WORKDIR/stats.tsv"
: > "$STATS_FILE"
printf 'Writing run log to %s\n' "$LOG_FILE"

# Worker processes are launched by xargs. They write response bodies to files,
# append one compact status/latency row, and never print credentials.
worker_request() {
  local method=$1 url=$2 token=$3 body=$4 key=$5 timeout=$6 bodyfile=$7 errfile=$8
  local meta status elapsed curlrc
  local -a args
  args=(--silent --show-error --max-time "$timeout" --output "$bodyfile" --write-out '%{http_code}\t%{time_total}')
  [[ -n "$token" ]] && args+=(--header "Authorization: Bearer $token")
  if [[ -n "$body" ]]; then
    args+=(--header 'Content-Type: application/json' --data-binary "$body")
  fi
  [[ -n "$key" ]] && args+=(--header "Idempotency-Key: $key")
  meta=$(curl "${args[@]}" --request "$method" "$url" 2>"$errfile")
  curlrc=$?
  status=${meta%%$'\t'*}
  elapsed=${meta#*$'\t'}
  if (( curlrc != 0 )) || [[ ! "$status" =~ ^[0-9]+$ ]]; then status=0; fi
  [[ -f "$bodyfile" ]] || : > "$bodyfile"
  awk -v s="${elapsed:-0}" 'BEGIN { printf "%.1f", s * 1000 }' > "${bodyfile%.json}.ms"
  printf '%s\n' "$status" > "${bodyfile%.json}.status"
  if (( curlrc != 0 )); then printf 'curl exit %s\n' "$curlrc" >> "$errfile"; fi
}

if [[ "${1:-}" == --worker-token ]]; then
  shift
  base=$1; timeout=$2; workdir=$3; token_dir=$4; stats=$5; index=$6; user=$7; role=$8
  mkdir -p "$token_dir"
  body=$(jq -cn --arg id "$user" --arg role "$role" '{user_id:$id,role:$role}')
  out="$workdir/token.$index.json"
  worker_request POST "$base/auth/token" '' "$body" '' "$timeout" "$out" "$workdir/token.$index.err"
  status=$(cat "${out%.json}.status")
  elapsed=$(cat "${out%.json}.ms")
  printf 'token-%s %s %s\n' "$role" "$status" "$elapsed" >> "$stats"
  if [[ "$status" == 200 ]]; then
    jq -er '.token | strings | select(length > 0)' "$out" > "$token_dir/token.$index" 2>/dev/null || exit 1
  else
    : > "$token_dir/token.$index"
  fi
  exit 0
fi

if [[ "${1:-}" == --worker-reserve ]]; then
  shift
  base=$1; timeout=$2; workdir=$3; show=$4; stats=$5
  index=$6; seat=$7; token_file=$8; key=$9; spoof=${10}; label=${11}
  token=$(cat "$token_file")
  body=$(jq -cn --arg seat "$seat" --arg key "$key" --arg spoof "$spoof" \
    '{seats:[$seat]} + (if $key != "" then {idempotency_key:$key} else {} end) + (if $spoof != "" then {user_id:$spoof} else {} end)')
  out="$workdir/$label.$index.json"
  worker_request POST "$base/shows/$show/reserve" "$token" "$body" "$key" "$timeout" "$out" "$workdir/$label.$index.err"
  status=$(cat "${out%.json}.status"); elapsed=$(cat "${out%.json}.ms")
  printf '%s %s %s\n' "$label" "$status" "$elapsed" >> "$stats"
  exit 0
fi

API_STATUS=0 API_BODY_FILE= API_ELAPSED=0 API_ERROR_FILE=
api_call() {
  local method=$1 path=$2 token=$3 body=$4 key=$5 label=$6
  local n out
  n=$((${API_COUNTER:-0} + 1)); API_COUNTER=$n
  out="$WORKDIR/api.$n.json"
  worker_request "$method" "$BASE_URL$path" "$token" "$body" "$key" "$TIMEOUT_MS" "$out" "$WORKDIR/api.$n.err"
  API_STATUS=$(cat "${out%.json}.status")
  API_ELAPSED=$(cat "${out%.json}.ms")
  API_BODY_FILE=$out
  API_ERROR_FILE="$WORKDIR/api.$n.err"
  printf '%s %s %s\n' "$label" "$API_STATUS" "$API_ELAPSED" >> "$STATS_FILE"
}

check() {
  if [[ "$1" != true ]]; then
    FAILURES=$((FAILURES + 1))
    printf 'FAIL: %s\n' "$2"
  fi
}
json_value() { jq -er "$1 // empty" "$2" 2>/dev/null; }
get_token() {
  local user=$1 role=${2:-user} body
  body=$(jq -cn --arg id "$user" --arg role "$role" '{user_id:$id,role:$role}')
  api_call POST /auth/token '' "$body" '' "token-$role"
  check "$([[ "$API_STATUS" == 200 ]] && echo true || echo false)" "Token creation for $user returned $API_STATUS"
  TOKEN=$(json_value '.token | strings' "$API_BODY_FILE" || true)
}
read_show() {
  local show=$1 token=$2 label=$3
  api_call GET "/shows/$show?include_seats=true" "$token" '' '' "$label"
  check "$([[ "$API_STATUS" == 200 ]] && echo true || echo false)" "$label: GET show returned $API_STATUS"
  STATE_FILE=$API_BODY_FILE
  if [[ "$API_STATUS" == 200 ]]; then
    local total sum
    total=$(jq -r '.total_seats // 0' "$STATE_FILE")
    sum=$(jq -r '(.counts.available // 0)+(.counts.held // 0)+(.counts.confirmed // 0)' "$STATE_FILE")
    check "$([[ "$sum" == "$total" ]] && echo true || echo false)" "$label: reconciliation failed ($sum != $total)"
  fi
}
reserve_one() {
  local show=$1 token=$2 seat=$3 key=$4 spoof=$5 label=$6 body
  body=$(jq -cn --arg seat "$seat" --arg key "$key" --arg spoof "$spoof" \
    '{seats:[$seat]} + (if $key != "" then {idempotency_key:$key} else {} end) + (if $spoof != "" then {user_id:$spoof} else {} end)')
  api_call POST "/shows/$show/reserve" "$token" "$body" "$key" "$label"
}
assert_status_error() {
  local status=$1 file=$2 expected_status=$3 expected_error=$4 description=$5 error
  error=$(json_value '.error' "$file" || true)
  check "$([[ "$status" == "$expected_status" && "$error" == "$expected_error" ]] && echo true || echo false)" \
    "$description: expected $expected_status $expected_error, got $status ${error:-}"
}

FAILURES=0
printf 'Requesting admin token; preparing %s hot-seat calls with concurrency %s.\n' "$REQUESTS" "$CONCURRENCY"
get_token "$USER_PREFIX-admin" admin
ADMIN_TOKEN=$TOKEN
HOT_SEATS=()
ALL_SEATS=()
for ((i=1; i<=HOT_SEAT_COUNT; i++)); do printf -v seat 'H%02d' "$i"; HOT_SEATS+=("$seat"); ALL_SEATS+=("$seat"); done
ALL_SEATS+=(I01 I02)
QUOTA_SEATS=()
for ((i=1; i<=QUOTA_SEAT_COUNT; i++)); do printf -v seat 'Q%02d' "$i"; QUOTA_SEATS+=("$seat"); ALL_SEATS+=("$seat"); done
ALL_SEATS+=(O01 O02)
SEATS_JSON=$(printf '%s\n' "${ALL_SEATS[@]}" | jq -Rsc 'split("\n")[:-1]')
show_body=$(jq -cn --arg name "correctness-$(date +%s)" --argjson seats "$SEATS_JSON" \
  '{name:$name,seats:$seats,price_paise:100,per_user_limit:4}')
api_call POST /shows "$ADMIN_TOKEN" "$show_body" '' create-show
SHOW_ID=$(json_value '.id | strings' "$API_BODY_FILE" || true)
check "$([[ "$API_STATUS" == 201 && -n "$SHOW_ID" ]] && echo true || echo false)" "Show creation returned $API_STATUS"
if [[ "$API_STATUS" != 201 || -z "$SHOW_ID" ]]; then printf 'Cannot continue: test show creation failed.\n'; exit 1; fi
printf 'Created isolated show %s with %s seats; hot pool: %s.\n' "$SHOW_ID" "${#ALL_SEATS[@]}" "${HOT_SEATS[*]}"

get_token "$USER_PREFIX-sampler"
SAMPLING_TOKEN=$TOKEN
read_show "$SHOW_ID" "$SAMPLING_TOKEN" baseline-show-state
baseline_total=$(jq -r '.counts.available // 0' "$STATE_FILE")
baseline_held=$(jq -r '.counts.held // 0' "$STATE_FILE")
baseline_confirmed=$(jq -r '.counts.confirmed // 0' "$STATE_FILE")
check "$([[ "$baseline_total" == "${#ALL_SEATS[@]}" && "$baseline_held" == 0 && "$baseline_confirmed" == 0 ]] && echo true || echo false)" \
  'Fresh show did not begin with every seat available'

# Prepare user tokens in bounded parallel waves. xargs controls the process
# count and works with the system Bash shipped on macOS as well as Linux.
TOKEN_DIR="$WORKDIR/tokens"; mkdir -p "$TOKEN_DIR"
TOKEN_JOBS="$WORKDIR/token-jobs"
: > "$TOKEN_JOBS"
for ((i=1; i<=REQUESTS; i++)); do printf '%s %s user\n' "$i" "$USER_PREFIX-hot-$i" >> "$TOKEN_JOBS"; done
token_start=$SECONDS
xargs -n 3 -P "$TOKEN_CONCURRENCY" bash "$SELF" --worker-token "$BASE_URL" "$TIMEOUT_MS" "$WORKDIR" "$TOKEN_DIR" "$STATS_FILE" < "$TOKEN_JOBS"
token_ok=0
for ((i=1; i<=REQUESTS; i++)); do [[ -s "$TOKEN_DIR/token.$i" ]] && token_ok=$((token_ok + 1)); done
printf 'Created %s/%s hot-test tokens in %ss.\n' "$token_ok" "$REQUESTS" "$((SECONDS-token_start))"
if (( token_ok != REQUESTS )); then
  check false "Could not create all hot-test tokens ($token_ok/$REQUESTS); hot-seat phase was not sent"
  printf 'Token creation outcomes:\n'
  awk '$1 == "token-user" && $2 != 200 { count[$2]++ } END { for (status in count) printf "  status %s: %d\n", status, count[status] }' "$STATS_FILE" | sort
  printf 'Transport errors:\n'
  grep -h '^curl:' "$WORKDIR"/token.*.err 2>/dev/null | sort | uniq -c || true
  printf 'Stopping before reservations. Details are in %s\n' "$LOG_FILE"
  exit 1
fi

# Start inventory polling in the background. Each response is persisted for
# the parent process to validate after xargs completes.
MONITOR_DONE="$WORKDIR/monitor.done"; rm -f "$MONITOR_DONE"
monitor_show() {
  local n=0 bodyfile status elapsed
  while [[ ! -e "$MONITOR_DONE" ]]; do
    n=$((n + 1)); bodyfile="$WORKDIR/live-show-$n.json"
    worker_request GET "$BASE_URL/shows/$SHOW_ID?include_seats=true" "$SAMPLING_TOKEN" '' '' "$TIMEOUT_MS" "$bodyfile" "$WORKDIR/live-show-$n.err"
    status=$(cat "${bodyfile%.json}.status"); elapsed=$(cat "${bodyfile%.json}.ms")
    printf 'live-show-state %s %s\n' "$status" "$elapsed" >> "$STATS_FILE"
    [[ "$status" == 200 ]] || printf '%s\n' "$status" > "$WORKDIR/live-show-$n.failed"
    sleep "$(awk -v ms="$SAMPLE_INTERVAL_MS" 'BEGIN {printf "%.3f", ms/1000}')"
  done
}
monitor_show & MONITOR_PID=$!

HOT_JOBS="$WORKDIR/hot-jobs"; : > "$HOT_JOBS"
for ((i=1; i<=REQUESTS; i++)); do
  seat_index=$(((i - 1) % HOT_SEAT_COUNT))
  printf -v seat 'H%02d' "$((seat_index + 1))"
  printf '%s %s %s - - hot-reserve\n' "$i" "$seat" "$TOKEN_DIR/token.$i" >> "$HOT_JOBS"
done
hot_start=$SECONDS
xargs -n 6 -P "$CONCURRENCY" bash "$SELF" --worker-reserve "$BASE_URL" "$TIMEOUT_MS" "$WORKDIR" "$SHOW_ID" "$STATS_FILE" < "$HOT_JOBS"
touch "$MONITOR_DONE"; wait "$MONITOR_PID" || true
printf 'Hot-seat storm completed %s/%s calls in %ss.\n' "$REQUESTS" "$REQUESTS" "$((SECONDS-hot_start))"

hot_success=(); hot_taken=(); hot_other=(); winner_index=(); winner_reservation=()
for ((s=0; s<HOT_SEAT_COUNT; s++)); do hot_success[$s]=0; hot_taken[$s]=0; hot_other[$s]=0; winner_index[$s]=; winner_reservation[$s]=; done
for ((i=1; i<=REQUESTS; i++)); do
  s=$(((i - 1) % HOT_SEAT_COUNT)); out="$WORKDIR/hot-reserve.$i.json"
  status=$(cat "${out%.json}.status" 2>/dev/null || echo 0)
  error=$(json_value '.error' "$out" || true)
  if [[ "$status" == 201 ]]; then
    hot_success[$s]=$((hot_success[$s] + 1)); winner_index[$s]=$i
    winner_reservation[$s]=$(json_value '.reservation_id | strings' "$out" || true)
    actual_user=$(json_value '.user_id | strings' "$out" || true)
    expected_user="$USER_PREFIX-hot-$i"
    check "$([[ "$actual_user" == "$expected_user" ]] && echo true || echo false)" "Hot reserve identity mismatch for H$(printf '%02d' "$((s+1))")"
  elif [[ "$status" == 409 && "$error" == seat_taken ]]; then
    hot_taken[$s]=$((hot_taken[$s] + 1))
  else
    hot_other[$s]=$((hot_other[$s] + 1))
    check false "Hot reserve for H$(printf '%02d' "$((s+1))") returned $status ${error:-}"
  fi
done
for ((s=0; s<HOT_SEAT_COUNT; s++)); do
  printf -v seat 'H%02d' "$((s+1))"
  assigned=$((REQUESTS / HOT_SEAT_COUNT)); (( s < REQUESTS % HOT_SEAT_COUNT )) && assigned=$((assigned + 1))
  check "$([[ ${hot_success[$s]} == 1 ]] && echo true || echo false)" "$seat: expected exactly one 201, got ${hot_success[$s]}"
  expected_taken=$((assigned - 1))
  check "$([[ ${hot_taken[$s]} == "$expected_taken" && ${hot_other[$s]} == 0 ]] && echo true || echo false)" \
    "$seat: expected $expected_taken seat_taken declines and no other outcomes; got ${hot_taken[$s]} and ${hot_other[$s]}"
  printf '  %s: 201=%s, 409 seat_taken=%s, other=%s\n' "$seat" "${hot_success[$s]}" "${hot_taken[$s]}" "${hot_other[$s]}"
done

sample_count=0
for live_file in "$WORKDIR"/live-show-*.json; do
  [[ -f "$live_file" ]] || continue
  status=$(cat "${live_file%.json}.status")
  if [[ "$status" == 200 ]]; then
    sample_count=$((sample_count + 1))
    total=$(jq -r '.total_seats // 0' "$live_file")
    sum=$(jq -r '(.counts.available // 0)+(.counts.held // 0)+(.counts.confirmed // 0)' "$live_file")
    check "$([[ "$sum" == "$total" ]] && echo true || echo false)" "live-show-state: reconciliation failed ($sum != $total)"
  fi
done
check "$([[ $sample_count -gt 0 ]] && echo true || echo false)" 'No successful inventory sample was collected during hot-seat burst'
printf 'Sampled reconciliation %s times during burst.\n' "$sample_count"

# Confirm one winner for every hot seat.
for ((s=0; s<HOT_SEAT_COUNT; s++)); do
  [[ -n "${winner_index[$s]}" ]] || continue
  token=$(cat "$TOKEN_DIR/token.${winner_index[$s]}")
  api_call POST "/reservations/${winner_reservation[$s]}/confirm" "$token" '' '' hot-confirm
  response_status=$(json_value '.status' "$API_BODY_FILE" || true)
  check "$([[ "$API_STATUS" == 200 && "$response_status" == confirmed ]] && echo true || echo false)" "Confirm winner for H$(printf '%02d' "$((s+1))") returned $API_STATUS $response_status"
done
read_show "$SHOW_ID" "$SAMPLING_TOKEN" post-hot-show-state
for ((s=0; s<HOT_SEAT_COUNT; s++)); do
  printf -v seat 'H%02d' "$((s+1))"
  seat_status=$(jq -r --arg seat "$seat" '.seats[] | select(.label==$seat) | .status' "$STATE_FILE" 2>/dev/null | head -n 1)
  check "$([[ "$seat_status" == confirmed ]] && echo true || echo false)" "$seat: expected one confirmed winner, got ${seat_status:-missing}"
done

# Idempotent concurrent retries, including the different-seat conflict.
get_token "$USER_PREFIX-idem"; IDEM_TOKEN=$TOKEN
IDEM_KEY="$USER_PREFIX-same-key"; IDEM_TOKEN_FILE="$WORKDIR/idem-token"; printf '%s' "$IDEM_TOKEN" > "$IDEM_TOKEN_FILE"
IDEM_JOBS="$WORKDIR/idem-jobs"; : > "$IDEM_JOBS"
for ((i=1; i<=10; i++)); do printf '%s I01 %s %s - idempotency-replay\n' "$i" "$IDEM_TOKEN_FILE" "$IDEM_KEY" >> "$IDEM_JOBS"; done
xargs -n 6 -P 10 bash "$SELF" --worker-reserve "$BASE_URL" "$TIMEOUT_MS" "$WORKDIR" "$SHOW_ID" "$STATS_FILE" < "$IDEM_JOBS"
idem_created=0 idem_replayed=0; idem_ids="$WORKDIR/idem-ids"
: > "$idem_ids"
for ((i=1; i<=10; i++)); do
  out="$WORKDIR/idempotency-replay.$i.json"; status=$(cat "${out%.json}.status")
  [[ "$status" == 201 ]] && idem_created=$((idem_created + 1))
  [[ "$status" == 200 ]] && idem_replayed=$((idem_replayed + 1))
  json_value '.reservation_id | strings' "$out" >> "$idem_ids" || true
done
idem_unique=$(sort -u "$idem_ids" | wc -l | tr -d ' ')
check "$([[ $idem_created == 1 && $idem_replayed == 9 && $idem_unique == 1 ]] && echo true || echo false)" \
  "Same-key retry expected 1x201 + 9x200 with one reservation; got ${idem_created}x201 + ${idem_replayed}x200 and $idem_unique IDs"
reserve_one "$SHOW_ID" "$IDEM_TOKEN" I02 "$IDEM_KEY" '' idempotency-conflict
assert_status_error "$API_STATUS" "$API_BODY_FILE" 409 idempotency_conflict 'Different seats with same key'

# Ten simultaneous seats for one user on a show limit of four.
get_token "$USER_PREFIX-quota"; QUOTA_TOKEN=$TOKEN
QUOTA_TOKEN_FILE="$WORKDIR/quota-token"; printf '%s' "$QUOTA_TOKEN" > "$QUOTA_TOKEN_FILE"
QUOTA_JOBS="$WORKDIR/quota-jobs"; : > "$QUOTA_JOBS"
for ((i=1; i<=QUOTA_SEAT_COUNT; i++)); do
  printf -v seat 'Q%02d' "$i"
  printf '%s %s %s %s - quota-reserve\n' "$i" "$seat" "$QUOTA_TOKEN_FILE" "$USER_PREFIX-quota-$i" >> "$QUOTA_JOBS"
done
xargs -n 6 -P 10 bash "$SELF" --worker-reserve "$BASE_URL" "$TIMEOUT_MS" "$WORKDIR" "$SHOW_ID" "$STATS_FILE" < "$QUOTA_JOBS"
quota_created=0 quota_declined=0
for ((i=1; i<=QUOTA_SEAT_COUNT; i++)); do
  out="$WORKDIR/quota-reserve.$i.json"; status=$(cat "${out%.json}.status"); error=$(json_value '.error' "$out" || true)
  [[ "$status" == 201 ]] && quota_created=$((quota_created + 1))
  [[ "$status" == 409 && "$error" == per_user_limit ]] && quota_declined=$((quota_declined + 1))
done
check "$([[ $quota_created == 4 && $quota_declined == 6 ]] && echo true || echo false)" \
  "Limit=4 with 10 parallel calls expected 4x201 + 6x409 per_user_limit; got $quota_created and $quota_declined"
read_show "$SHOW_ID" "$SAMPLING_TOKEN" post-quota-show-state
quota_held=$(jq -r '[.seats[] | select(.label | startswith("Q")) | select(.status=="held")] | length' "$STATE_FILE")
check "$([[ $quota_held -le 4 ]] && echo true || echo false)" "Quota user holds $quota_held seats; limit is 4"

# Token identity and ownership enforcement.
get_token "$USER_PREFIX-owner"; OWNER_TOKEN=$TOKEN
get_token "$USER_PREFIX-other"; OTHER_TOKEN=$TOKEN
reserve_one "$SHOW_ID" "$OWNER_TOKEN" O01 "$USER_PREFIX-owned" "$USER_PREFIX-other" identity-spoof-reserve
owned_id=$(json_value '.reservation_id | strings' "$API_BODY_FILE" || true)
owned_user=$(json_value '.user_id | strings' "$API_BODY_FILE" || true)
check "$([[ "$API_STATUS" == 201 && "$owned_user" == "$USER_PREFIX-owner" ]] && echo true || echo false)" \
  "Spoofed reserve should belong to token user; got $API_STATUS owner ${owned_user:-}"
if [[ "$API_STATUS" == 201 && -n "$owned_id" ]]; then
  api_call POST "/reservations/$owned_id/cancel" "$OTHER_TOKEN" '' '' foreign-cancel
  assert_status_error "$API_STATUS" "$API_BODY_FILE" 403 forbidden 'Foreign user cancellation'
  read_show "$SHOW_ID" "$SAMPLING_TOKEN" after-foreign-cancel-state
  owner_seat_status=$(jq -r '.seats[] | select(.label=="O01") | .status' "$STATE_FILE" | head -n 1)
  check "$([[ "$owner_seat_status" == held ]] && echo true || echo false)" 'Foreign cancellation changed the owner held seat'
  api_call POST "/reservations/$owned_id/cancel" "$OWNER_TOKEN" '' '' owner-cancel
  cancel_status=$(json_value '.status' "$API_BODY_FILE" || true)
  check "$([[ "$API_STATUS" == 200 && "$cancel_status" == cancelled ]] && echo true || echo false)" "Owner cancel expected 200 cancelled; got $API_STATUS $cancel_status"
fi
read_show "$SHOW_ID" "$SAMPLING_TOKEN" final-show-state
owner_seat_status=$(jq -r '.seats[] | select(.label=="O01") | .status' "$STATE_FILE" | head -n 1)
check "$([[ "$owner_seat_status" == available ]] && echo true || echo false)" 'Owner cancellation did not release the owner seat'

# Aggregate statuses, latency percentiles and errors from all curl workers.
five_xx=$(awk '$2 >= 500 && $2 < 600 {n++} END {print n+0}' "$STATS_FILE")
transport=$(awk '$2 == 0 {n++} END {print n+0}' "$STATS_FILE")
check "$([[ "$five_xx" == 0 ]] && echo true || echo false)" "Observed $five_xx HTTP 5xx responses"
check "$([[ "$transport" == 0 ]] && echo true || echo false)" "Observed $transport transport errors"
printf '\nHTTP results by phase/status:\n'
awk '{count[$1" "$2]++} END {for (key in count) printf "  %s: %d\n", key, count[key]}' "$STATS_FILE" | sort
printf 'Total HTTP responses: %s; 5xx: %s; transport errors: %s\n' "$(awk '$2>0 {n++} END{print n+0}' "$STATS_FILE")" "$five_xx" "$transport"
sort -n "$WORKDIR"/*.ms > "$WORKDIR/latencies.sorted" 2>/dev/null || :
latency_count=$(wc -l < "$WORKDIR/latencies.sorted" | tr -d ' ')
percentile() { awk -v p="$1" -v n="$latency_count" 'NR==int(n*p+0.999999) {print; found=1; exit} END {if (!found) print 0}' "$WORKDIR/latencies.sorted"; }
printf 'Latency ms: p50=%s, p95=%s, p99=%s\n' "$(percentile 0.50)" "$(percentile 0.95)" "$(percentile 0.99)"
printf 'Hot-seat winners: '
for ((s=0; s<HOT_SEAT_COUNT; s++)); do printf 'H%02d=%s ' "$((s+1))" "$( [[ -n "${winner_index[$s]}" ]] && echo 'confirmed once' || echo 'no winner' )"; done
printf '\nFinal inventory: %s\n' "$(jq -c '.counts' "$STATE_FILE")"
if (( FAILURES > 0 )); then printf '\nFAIL: %s correctness assertion(s) failed.\n' "$FAILURES"; exit 1; fi
printf '\nPASS: all correctness assertions passed.\n'
