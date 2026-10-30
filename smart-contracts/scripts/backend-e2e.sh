#!/usr/bin/env bash
set -euo pipefail

contract_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository_root="$(cd "${contract_dir}/.." && pwd)"
run_dir="$(mktemp -d "${TMPDIR:-/tmp}/dex-e2e.XXXXXX")"
compose_project="dex-e2e-$$"
database_port="${DEX_E2E_DATABASE_PORT:-55432}"
backend_port="${DEX_E2E_BACKEND_PORT:-18080}"
hardhat_pid=""
backend_pid=""

stop_backend() {
  if [[ -n "${backend_pid}" ]] && kill -0 "${backend_pid}" 2>/dev/null; then
    kill "${backend_pid}" 2>/dev/null || true
    wait "${backend_pid}" 2>/dev/null || true
  fi
  backend_pid=""
}

cleanup() {
  result=$?
  stop_backend
  if [[ -n "${hardhat_pid}" ]] && kill -0 "${hardhat_pid}" 2>/dev/null; then
    kill "${hardhat_pid}" 2>/dev/null || true
    wait "${hardhat_pid}" 2>/dev/null || true
  fi
  docker compose \
    --project-name "${compose_project}" \
    --file "${repository_root}/compose.yaml" \
    down --volumes >/dev/null 2>&1 || true
  if [[ ${result} -ne 0 ]]; then
    echo "Acceptance run failed. Logs are retained in ${run_dir}" >&2
    tail -n 80 "${run_dir}/hardhat.log" 2>/dev/null || true
    tail -n 120 "${run_dir}/backend.log" 2>/dev/null || true
  fi
  exit "${result}"
}
trap cleanup EXIT INT TERM

wait_for_rpc() {
  for _ in {1..60}; do
    if curl --silent --fail \
      --header "Content-Type: application/json" \
      --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
      "http://127.0.0.1:8545" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "Hardhat JSON-RPC did not become ready" >&2
  return 1
}

wait_for_backend() {
  for _ in {1..120}; do
    if curl --silent --fail \
      "http://127.0.0.1:${backend_port}/api/v1/status" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "Backend did not become ready" >&2
  return 1
}

trade_is_confirmed() {
  response="$(curl --silent --fail \
    "http://127.0.0.1:${backend_port}/api/v1/markets/1/trades?limit=50")" || return 1
  node -e '
    const page = JSON.parse(process.argv[1]);
    const trade = page.items?.[0];
    process.exit(page.items?.length === 1
      && trade.fillLots === "40"
      && trade.buyOrderRemainingLots === "0"
      && trade.sellOrderRemainingLots === "60" ? 0 : 1);
  ' "${response}"
}

wait_for_trade() {
  for _ in {1..180}; do
    if trade_is_confirmed; then
      return 0
    fi
    sleep 1
  done
  echo "Backend did not confirm the expected trade" >&2
  return 1
}

start_backend() {
  (
    cd "${repository_root}/backend"
    exec ./mvnw --batch-mode --no-transfer-progress spring-boot:run
  ) >"${run_dir}/backend.log" 2>&1 &
  backend_pid=$!
  wait_for_backend
}

cd "${contract_dir}"
./node_modules/.bin/hardhat node \
  --hostname 127.0.0.1 \
  --port 8545 >"${run_dir}/hardhat.log" 2>&1 &
hardhat_pid=$!
wait_for_rpc

npm run deploy:local
set -a
source "${contract_dir}/deployments/localhost.env"
set +a

export DATABASE_NAME=dex_e2e
export DATABASE_PORT="${database_port}"
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${database_port}/dex_e2e"
export SPRING_DATASOURCE_USERNAME=dex_e2e
export SPRING_DATASOURCE_PASSWORD=dex_e2e
export DEX_INDEXER_ENABLED=true
export DEX_INDEXER_POLL_INTERVAL=200ms
export DEX_INDEXER_CONFIRMATION_DEPTH=0
export DEX_MATCHER_ENABLED=true
export DEX_MATCHER_POLL_INTERVAL=200ms
export DEX_EXECUTOR_POLL_INTERVAL=200ms
export SERVER_PORT="${backend_port}"

docker compose \
  --project-name "${compose_project}" \
  --file "${repository_root}/compose.yaml" \
  up --detach --wait postgres

start_backend
wait_for_trade
npm run verify:e2e-state

# Restart against the same database and chain. Re-indexing the same range must
# not create another trade or signature.
stop_backend
start_backend
sleep 2
trade_is_confirmed
npm run verify:e2e-state

echo "Backend Ethereum acceptance passed, including restart idempotency"
echo "Run logs: ${run_dir}"
