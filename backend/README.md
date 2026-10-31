# DEX Backend

Spring Boot backend for the university decentralized-exchange project. It
indexes confirmed contract events into PostgreSQL, serves read-only APIs,
matches compatible projected orders, and submits serialized settlement
transactions through an explicitly selected blockchain adapter.

## Contents

- [Prerequisites](#prerequisites)
- [Quick start in mock mode](#quick-start-in-mock-mode)
- [Run against local Ethereum](#run-against-local-ethereum)
- [Configuration reference](#configuration-reference)
- [Blockchain integration status](#blockchain-integration-status)
- [Indexer operation and recovery](#indexer-operation-and-recovery)
- [Matcher operation](#matcher-operation)
- [Settlement execution](#settlement-execution)
- [Read API](#read-api)
- [Frontend handoff](docs/frontend-api-handoff.md)
- [Testing](#testing)
- [Security boundaries](#security-boundaries)
- [Troubleshooting](#troubleshooting)

## Prerequisites

- Java 21
- Docker with Docker Compose
- Node.js 22 and npm for contract compilation or Ethereum acceptance runs

The normal backend test suite does not require Node.js. It uses the Java mock
adapter and PostgreSQL Testcontainers.

## Quick start in mock mode

From the repository root, copy the safe local defaults, load them, start
PostgreSQL, and run the application:

```bash
cp .env.example .env
set -a
source .env
set +a
docker compose up -d postgres
cd backend
./mvnw spring-boot:run
```

Verify the backend after its first indexing polls:

```bash
curl http://localhost:8080/api/v1/status
curl http://localhost:8080/api/v1/markets
curl 'http://localhost:8080/api/v1/markets/1/orderbook?limit=50'
```

The example environment explicitly selects `MOCK`, enables indexing, and
disables matching. Its deterministic fixture contains:

- market `1`, pairing `APPLE` with `USDToken`, both with two decimals;
- a base lot size of `1000` base-token atoms;
- Alice's older 100-lot sell order at 10 quote atoms per lot;
- Bob's 40-lot buy order at 12 quote atoms per lot; and
- deterministic blocks, hashes, balances, allowances, and executor identity.

If `DEX_MATCHER_ENABLED=true`, the older sell price is used and the mock
adapter can exercise the same durable settlement lifecycle as Ethereum mode.
Mock data exists only inside the mock gateway; API responses come from the
PostgreSQL projection after the indexer processes those mock events.

Stop the application with `Ctrl-C`. Stop the development database when it is no
longer needed:

```bash
docker compose stop postgres
```

## Run against local Ethereum

The fastest supported integration path runs the complete Alice-100/Bob-40
scenario, including a backend restart:

```bash
cd smart-contracts
npm ci
npm run e2e:backend
```

This starts a disposable Hardhat chain, deploys the checked-in contracts,
creates an isolated PostgreSQL instance, runs the real backend workers, checks
the on-chain balances and order state, restarts the backend, and proves the
trade and executor transaction were not duplicated. See
[Local Ethereum acceptance](docs/ethereum-local-acceptance.md) for ports,
generated files, and exact checks.

For interactive development, use separate terminals.

Terminal 1:

```bash
cd smart-contracts
npm run node:local
```

Terminal 2:

```bash
cd smart-contracts
npm run deploy:local
```

Terminal 3, from the repository root:

```bash
cp .env.example .env
set -a
source .env
source smart-contracts/deployments/localhost.env
set +a
export DEX_MATCHER_ENABLED=true
docker compose up -d --wait postgres
cd backend
./mvnw spring-boot:run
```

The deployment command generates ignored `localhost.json` metadata and an
owner-readable `localhost.env` containing a random key funded only on that
disposable chain. Regenerate both after every Hardhat reset. Never commit the
environment file, reuse its key elsewhere, or combine new chain metadata with
an old deployment ID.

## Configuration reference

Spring reads these variables through `application.yaml`. Durations use Spring
syntax such as `200ms`, `2s`, or `1m`.

| Variable | Default in `.env.example` | Purpose |
| --- | --- | --- |
| `DEX_BLOCKCHAIN_MODE` | `MOCK` | Required adapter selection: `MOCK` or `ETHEREUM`. There is no fallback. |
| `DEX_DEPLOYMENT_ID` | `local-mock-v1` | Stable projection namespace for one exact deployment. |
| `DEX_RPC_URL` | empty | Absolute HTTP(S) Ethereum RPC URL; required in Ethereum mode. |
| `DEX_EXPECTED_CHAIN_ID` | empty | Positive expected chain ID; required in Ethereum mode. |
| `DEX_CONTRACT_ADDRESS` | empty | Lowercase OrderBook address; required in Ethereum mode. |
| `DEX_MARKET_REGISTRY_ADDRESS` | empty | Lowercase MarketRegistry address; required in Ethereum mode and must differ from the OrderBook. |
| `DEX_DEPLOYMENT_BLOCK` | empty | Non-negative first block whose logs rebuild this projection. |
| `DEX_INDEXER_ENABLED` | `true` | Enables bounded fixed-delay event indexing. |
| `DEX_INDEXER_POLL_INTERVAL` | `2s` | Positive delay between indexer invocations. |
| `DEX_INDEXER_CONFIRMATION_DEPTH` | `0` | Blocks withheld from indexing for confirmation safety. |
| `DEX_INDEXER_BLOCK_BATCH_SIZE` | `500` | Blocks per batch, from 1 through 10,000. |
| `DEX_MATCHER_ENABLED` | `false` | Enables both matching and settlement execution. |
| `DEX_MATCHER_POLL_INTERVAL` | `2s` | Positive matcher fixed delay. |
| `DEX_MATCHER_INELIGIBILITY_DURATION` | `30s` | Positive in-memory cooldown after stale or underfunded preflight. |
| `DEX_EXECUTOR_PRIVATE_KEY` | empty | Executor key; required only for enabled Ethereum matching. |
| `DEX_EXECUTOR_POLL_INTERVAL` | `2s` | Positive settlement-executor fixed delay. |
| `DEX_CORS_ORIGINS` | `http://localhost:5173` | Comma-separated exact frontend origins for `/api/v1/**`. |
| `DATABASE_NAME` | `dex` | Compose PostgreSQL database name. |
| `DATABASE_PORT` | `5432` | Host port published by Compose. |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/dex` | JDBC connection used by Spring. Keep its port/name aligned with Compose. |
| `SPRING_DATASOURCE_USERNAME` | `dex` | Local database user. |
| `SPRING_DATASOURCE_PASSWORD` | `dex-local-only` | Local-only example password; replace outside disposable development. |

Ethereum startup validates every required identity value before processing.
Addresses must be lowercase `0x` values. CORS entries may contain only an
HTTP(S) scheme, host, and optional port—no wildcard, path, query, credentials,
or fragment.

Match discovery excludes both orders referenced by unresolved execution
attempts. The operational matcher can preflight and durably reserve one match,
and the settlement executor can durably sign, broadcast, and reconcile that
reservation. Successful settlements become confirmed only when the indexer
atomically applies their trade events; confirmed reverts release their
reservations without changing projected orders.

## Blockchain integration status

Mock mode is currently implemented. Its standard deterministic fixture contains
one market and two compatible orders, and mock settlement supports pending,
successful, and reverted transaction behavior.

The Web3j foundation validates chain identity, reads blocks, queries standard
ERC-20 state, reads executor nonces, broadcasts exact signed payloads, and
observes transactions. The ABI-specific client additionally decodes confirmed
`MarketRegistry` and `OrderBook` logs and reads `getMarket` and `getOrder` using
the checked-in contract artifacts. ABI compatibility tests fail if the relevant
artifact signatures or tuple layouts change.

Deployment identity records the OrderBook address used for orders, trades,
settlement, and allowances plus the separate MarketRegistry address used for
markets. `OrderCancelled` supplies only an order ID, and `TradeExecuted` does
not supply post-trade remainders; the indexer therefore derives those values in
strict log order from the confirmed projection. Token symbols and decimals are
read from the ERC-20 contracts at the market event's block.

The complete Ethereum `BlockchainGateway` is registered only when mode is
explicitly `ETHEREUM`. It composes the RPC and ABI clients, estimates gas,
encodes the exact four-argument `executeTrade` call, and signs a legacy EIP-155
transaction using the configured chain ID and executor. Read-only indexing can
start without executor credentials; Ethereum matching requires a configured
private key. The adapter never returns placeholder data and never silently
falls back to mock mode.

The automated Hardhat acceptance flow validates deployment identity, indexing,
matching, signing, broadcasting, receipt reconciliation, event-driven
confirmation, balances, and restart idempotency against the checked-in
contracts. It is a local integration test, not a production-security audit.

- [Solidity integration reference](docs/contract-assumptions.md)
- [Member 2 integration checklist](docs/member-2-integration-checklist.md)
- [Local Ethereum acceptance](docs/ethereum-local-acceptance.md)

## Indexer operation and recovery

The indexer polls with a fixed delay and processes at most one configured,
bounded block range per invocation. It indexes only blocks that satisfy the
configured confirmation depth. Retryable operational failures preserve the
checkpoint so the same range can be attempted again.

Chain-identity mismatches, checkpoint-hash mismatches, projection
inconsistencies, and non-retryable gateway failures pause indexing. A paused
worker must be recovered explicitly and the application must then be restarted;
it never silently unpauses or deletes projection data. After a local-chain reset
or contract redeployment, using a new stable deployment ID is preferred because
it keeps the previous projection isolated and available for diagnosis.

See [Indexer recovery](docs/indexer-recovery.md) for inspection, backup, resume,
and deployment-scoped reindex procedures.

## Matcher operation

Matching is disabled by default. When `DEX_MATCHER_ENABLED=true`, the matcher
runs with the fixed delay configured by `DEX_MATCHER_POLL_INTERVAL` (two seconds
by default). It waits until the configured deployment is registered and the
indexer has an unpaused, error-free checkpoint with no confirmation-eligible
lag. Each invocation examines at most one proposal and overlapping invocations
are skipped.

Before reserving a proposal, the matcher reloads the market and both orders from
the blockchain adapter. It then checks current balances and allowances using:

```text
seller base required = fillLots × baseLotSizeAtoms
buyer quote required = fillLots × executionPriceQuoteAtomsPerLot
```

These checks reduce avoidable failures; the contract remains the security
boundary. An order with stale state or insufficient funding is temporarily
excluded for `DEX_MATCHER_INELIGIBILITY_DURATION` (30 seconds by default). The
cooldown is advisory, remains in memory only, and resets on restart.

A valid proposal is rechecked against one serializable database snapshot before
a `RESERVED` execution attempt is inserted. Only one unresolved attempt is
allowed for the configured deployment. `RESERVED`, `SIGNED`, `SUBMITTED`, and
`UNCERTAIN` attempts remain unresolved; the matcher performs no more proposal or
RPC work while one exists.

## Settlement execution

When matching is enabled, the settlement executor polls with the fixed delay
configured by `DEX_EXECUTOR_POLL_INTERVAL` (two seconds by default). It handles
at most one unresolved execution attempt per invocation, and overlapping
invocations are skipped.

For a `RESERVED` attempt, transaction preparation and signing occur through the
blockchain adapter outside a database transaction. The executor then records a
short `RESERVED` to `SIGNED` lifecycle transition containing the executor nonce,
transaction hash, and exact signed transaction payload. Persisting the payload
allows a later process to recover the identical transaction after a restart;
the database and its backups must therefore be treated as sensitive.

In Ethereum mode, preparation obtains the executor's pending nonce and the
node's gas price, estimates the exact `executeTrade` call, adds a bounded gas
margin, and signs once with EIP-155 replay protection. The durable payload—not
a newly generated replacement—is used for every later broadcast or recovery.

An existing `SIGNED` attempt is reconstructed from its durable fields and is
never signed again. The executor broadcasts that exact payload and records the
attempt as `SUBMITTED` only when the gateway returns the persisted transaction
hash. If the broadcast outcome is unclear, the attempt becomes `UNCERTAIN` and
continues reserving both orders.

An `UNCERTAIN` attempt is reconciled using its known transaction hash. A visible
pending, successful, or reverted transaction becomes `SUBMITTED`; a transaction
that is not found is safely rebroadcast using the identical signed payload. No
replacement transaction, nonce, signature, or execution attempt is created.

A `SUBMITTED` attempt is observed on every executor cycle. Pending transactions
remain reserved. A temporarily missing transaction becomes `UNCERTAIN` so the
same durable payload can be reconciled and, when safe, rebroadcast. Mined block
identity is rechecked before a terminal outcome is accepted.

A successful receipt does not release its reservation. The indexer correlates
the transaction hash with the local attempt and, in the same database
transaction, updates both order projections, inserts the trade, records the
processed event, marks the attempt `CONFIRMED`, and advances the checkpoint. A
conflicting event rolls back the entire batch and pauses indexing. If the
checkpoint passes a successful receipt block without the expected trade event,
the executor halts for operator investigation.

A reverted receipt becomes terminal only after the indexer checkpoint has
processed through its block, which applies the configured confirmation-depth
policy. Both referenced orders then receive the existing in-memory cooldown
before the attempt becomes `REVERTED`; projected orders and trades remain
unchanged. The bounded revert reason is internal and is never included in the
status API.

The durable execution-attempt state machine and its crash-safety rules are
documented in [Settlement lifecycle](docs/settlement-lifecycle.md). In
particular, a successful receipt is not enough to mark an attempt `CONFIRMED`;
the indexed trade event must update the projections first.

## Read API

The versioned read API is available under `/api/v1`. It reads the confirmed
PostgreSQL projection, so responses can lag the latest blockchain state by the
configured confirmation depth and polling delay.

Frontend implementers should also read the concise
[Member 3 API handoff](docs/frontend-api-handoff.md) for JavaScript numeric
handling, wallet responsibilities, cursor usage, freshness, errors, and CORS.

### List markets

```http
GET /api/v1/markets
```

Markets are scoped to the configured deployment and sorted by numeric market ID.
The set of registered markets is expected to remain small, so this endpoint is
not paginated.

```json
{
  "items": [
    {
      "marketId": "1",
      "baseToken": {
        "address": "0x1111111111111111111111111111111111111111",
        "symbol": "TKA",
        "decimals": 18
      },
      "quoteToken": {
        "address": "0x2222222222222222222222222222222222222222",
        "symbol": "TKB",
        "decimals": 18
      },
      "baseLotSizeAtoms": "1000000000000000000",
      "quantityUnit": "LOTS",
      "priceUnit": "QUOTE_ATOMS_PER_LOT",
      "active": true
    }
  ]
}
```

Large integer values are decimal strings to avoid JavaScript precision loss.
`baseLotSizeAtoms` defines the number of base-token atoms in one lot, and prices
use quote-token atoms per lot.

### Get an order book

```http
GET /api/v1/markets/{marketId}/orderbook?limit=50
```

The response contains individual confirmed orders rather than aggregated price
levels. `limit` defaults to 50, has a maximum of 200, and applies independently
to bids and asks. Each side's `hasMore` value indicates whether additional
eligible orders exist beyond that limit.

```json
{
  "marketId": "1",
  "limit": 50,
  "bids": {
    "items": [
      {
        "orderId": "10",
        "side": "BUY",
        "limitPriceQuoteAtomsPerLot": "2500000",
        "remainingLots": "40",
        "creationPosition": {
          "blockNumber": "12",
          "transactionIndex": "0",
          "logIndex": "1"
        }
      }
    ],
    "hasMore": false
  },
  "asks": {
    "items": [],
    "hasMore": false
  }
}
```

Only `OPEN` and `PARTIALLY_FILLED` orders with a positive remainder appear.
Bids sort by highest price first and asks by lowest price first. Orders at the
same price sort by block number, transaction index, and log index ascending,
with order ID as the final deterministic tie-breaker. An inactive but existing
market can still expose confirmed remaining orders.

`remainingLots` is a quantity in lots, while
`limitPriceQuoteAtomsPerLot` is a price in quote-token atoms per lot. All
blockchain-sized integers are represented as decimal strings. This bounded
snapshot has no cursor pagination and can lag current blockchain state by the
confirmation depth and polling delay. Orders reserved by an unresolved
settlement attempt remain visible until a confirmed blockchain event changes
their projected state.

### List wallet orders

```http
GET /api/v1/orders?owner=0x1111111111111111111111111111111111111111&marketId=1&status=PARTIALLY_FILLED&limit=50
```

`owner` is required. The optional `marketId` and `status` filters narrow the
result; supported statuses are `OPEN`, `PARTIALLY_FILLED`, `FILLED`, and
`CANCELLED`. Wallet filtering is a public blockchain-data query and does not
authenticate or prove control of the supplied address.

```json
{
  "items": [
    {
      "orderId": "10",
      "marketId": "1",
      "owner": "0x1111111111111111111111111111111111111111",
      "side": "BUY",
      "limitPriceQuoteAtomsPerLot": "2500000",
      "originalLots": "100",
      "filledLots": "60",
      "remainingLots": "40",
      "status": "PARTIALLY_FILLED",
      "creationPosition": {
        "blockNumber": "12",
        "transactionIndex": "0",
        "logIndex": "1"
      },
      "creationTransactionHash": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "blockTimestamp": "2026-01-01T12:00:00Z"
    }
  ],
  "nextCursor": "djE6MTI6MDoxOjEw"
}
```

Orders are sorted newest first by block number, transaction index, log index,
and finally order ID. `filledLots` equals `originalLots` minus `remainingLots`.
The default page limit is 50 and the maximum is 200.

When `nextCursor` is not `null`, pass it unchanged as the next request's
`cursor` parameter. Cursors are opaque and versioned; clients must not decode
or construct them. Keyset pagination prevents offset-related duplicates, but
separate requests are not a fixed database snapshot: later confirmed events
can change an order's status between pages.

### Get one order

```http
GET /api/v1/orders/{orderId}
```

This returns one order using the same item shape shown above. Only the
configured deployment is searched, and an unknown order returns a sanitized
`404` Problem Details response.

These order endpoints expose confirmed projection state and can lag the chain
by the configured confirmation depth and polling delay. They do not submit,
cancel, or authenticate orders; user transactions remain the frontend and
MetaMask's responsibility.

### List recent trades

```http
GET /api/v1/markets/{marketId}/trades?limit=50
```

Trades are scoped to the configured deployment and market and sorted newest
first by block number, transaction index, and log index. Historical trades
remain available when an existing market becomes inactive.

```json
{
  "items": [
    {
      "tradeId": null,
      "marketId": "1",
      "buyOrderId": "10",
      "sellOrderId": "11",
      "fillLots": "40",
      "executionPriceQuoteAtomsPerLot": "2500000",
      "buyOrderRemainingLots": "0",
      "sellOrderRemainingLots": "60",
      "executionPosition": {
        "blockNumber": "20",
        "transactionIndex": "1",
        "logIndex": "3"
      },
      "transactionHash": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "blockTimestamp": "2026-01-01T12:00:00Z"
    }
  ],
  "nextCursor": null
}
```

`fillLots` is expressed in lots and `executionPriceQuoteAtomsPerLot` in quote
token atoms per lot. Both remaining quantities are the confirmed order state
immediately after the fill. All blockchain-sized integers are decimal strings.

The provisional contract interface allows `tradeId` to be absent, so this
field is `null` when the event does not provide one. The transaction hash and
log index always identify the indexed trade event.

The default page limit is 50 and the maximum is 200. When `nextCursor` is not
`null`, pass it unchanged through the next request's `cursor` parameter. Trade
cursors are opaque and must not be decoded or constructed by clients. As with
other read endpoints, these are confirmed projections and can lag the chain;
pending execution attempts are never returned as trades.

### Get backend operational status

```http
GET /api/v1/status
```

The status endpoint reports safe deployment identity, indexer progress,
matcher availability, and settlement-executor state without making a blockchain
RPC request. It returns
`200 OK` whenever the status document can be generated, including while the
backend is starting, degraded, retrying, or paused. Use the Actuator health
endpoint for infrastructure health probes rather than treating this operational
status as a liveness response.

```json
{
  "status": "HEALTHY",
  "blockchainMode": "MOCK",
  "deployment": {
    "deploymentId": "local-mock-v1",
    "registered": true,
    "chainId": "31337",
    "dexAddress": "0xdddddddddddddddddddddddddddddddddddddddd",
    "marketRegistryAddress": "0xdddddddddddddddddddddddddddddddddddddddd",
    "deploymentBlockNumber": "100",
    "deploymentBlockHash": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "indexer": {
    "enabled": true,
    "state": "UP_TO_DATE",
    "latestObservedBlockNumber": "103",
    "lastIndexedBlockNumber": "103",
    "indexingLagBlocks": "0",
    "confirmationDepth": 0,
    "lastSuccessfulSyncAt": "2026-01-01T12:00:00Z",
    "lastErrorAt": null,
    "paused": false
  },
  "matcher": {
    "enabled": false,
    "state": "DISABLED"
  },
  "executor": {
    "enabled": false,
    "state": "DISABLED"
  }
}
```

Overall states are `HEALTHY`, `STARTING`, and `DEGRADED`. Indexer states are
`DISABLED`, `STARTING`, `RUNNING`, `SYNCING`, `UP_TO_DATE`, `RETRYING`, and
`PAUSED`. Matcher states are `DISABLED`, `STARTING`, `WAITING_FOR_INDEXER`,
`IDLE`, `RUNNING`, `RESERVED`, `RETRYING`, and `HALTED`. Retryable matcher
failures report `RETRYING`; non-retryable gateway or safety failures halt the
worker until restart without exposing their raw details through the API.
Executor states are `DISABLED`, `STARTING`, `IDLE`, `RUNNING`, `SIGNED`,
`WAITING_FOR_RECONCILIATION`, `RETRYING`, and `HALTED`. Executor `RETRYING` and
`HALTED` states make the overall status degraded, while raw signed transaction
bytes and failure details are never returned.

`indexingLagBlocks` counts only blocks currently eligible after confirmation
depth: `max(latestObserved - confirmationDepth - lastIndexed, 0)`. Chain-sized
numbers are decimal strings, and unavailable deployment or checkpoint fields
are `null`. Raw error messages, pause reasons, RPC URLs, private keys, exception
details, and database details are never included.

Browser access to `/api/v1/**` is restricted to the origins configured by
`DEX_CORS_ORIGINS`. Only read requests are supported. API errors use the
standard `application/problem+json` media type and never include stack traces or
sensitive configuration.

## Testing

```bash
cd backend
./mvnw test
```

Tests require Docker. They start an isolated PostgreSQL 18.6 container through
Testcontainers, apply the Flyway migrations, and remove the temporary container
afterward. The Compose PostgreSQL service does not need to be running.

Use the full Maven verification lifecycle before committing:

```bash
cd backend
./mvnw verify
```

Flyway migration files are append-only after they have been applied. Add a new
versioned migration for schema changes instead of modifying an existing one.

Verify the smart contracts and deployment scripts with Node.js 22:

```bash
cd smart-contracts
npm ci
npm run verify
```

Run the full cross-stack acceptance separately because it starts application
processes and an isolated Compose database:

```bash
cd smart-contracts
npm run e2e:backend
```

## Security boundaries

- The frontend submits user order and cancellation transactions through the
  user's wallet. The backend never receives user private keys.
- The configured executor key authorizes settlement transactions only. Treat
  its environment, PostgreSQL rows containing signed payloads, and database
  backups as sensitive.
- PostgreSQL is a rebuildable projection and operational journal; confirmed
  blockchain events remain authoritative for market, order, and trade state.
- Java matching and preflight checks improve reliability but are not the asset
  security boundary. The Solidity contracts must independently validate every
  settlement and transfer both token legs atomically.
- Public `/api/v1` endpoints are read-only. Wallet-address filters expose only
  public chain data and do not authenticate the caller.
- Never commit `.env`, generated `localhost.env`, real keys, RPC credentials,
  signed transaction bytes, or database backups.

## Troubleshooting

### PostgreSQL does not start

Run `docker compose config` from the repository root and confirm the variables
loaded from `.env`. `DATABASE_PORT` and the port in `SPRING_DATASOURCE_URL` must
match. If port 5432 is occupied, change both values together. Inspect the
service with:

```bash
docker compose ps
docker compose logs postgres
```

### Tests cannot find Docker

Start Docker Desktop and confirm `docker info` succeeds. Backend tests use
Testcontainers and do not use the manually managed Compose database.

### Ethereum mode fails during startup

Check that the generated local environment was sourced after `.env`. The RPC
URL must be reachable, chain ID must match, addresses must be lowercase, and
the configured deployment block must still exist. After a local-chain reset,
redeploy and use the newly generated deployment ID and block metadata.

### The indexer is `PAUSED` or `RETRYING`

`RETRYING` normally indicates a temporary RPC or database failure and does not
advance the checkpoint. `PAUSED` indicates a safety inconsistency and requires
operator review and an application restart after recovery. Follow the
[Indexer recovery runbook](docs/indexer-recovery.md); do not delete rows or
clear a pause casually.

### The matcher stays `WAITING_FOR_INDEXER`

The matcher will not perform discovery or RPC preflight until the deployment is
registered and the indexer has an unpaused, error-free checkpoint with zero
confirmation-eligible lag. Inspect `/api/v1/status` before changing matcher
settings.

### The executor is `RETRYING` or `HALTED`

Do not manually release a signed, submitted, or uncertain attempt. Those states
may represent a transaction already accepted by the node. Preserve the database
and chain, inspect the status and logs, and use the
[Settlement lifecycle](docs/settlement-lifecycle.md) recovery rules.

### API responses are empty or behind the chain

Read endpoints expose only the configured deployment's confirmed projection.
Allow for confirmation depth plus polling delay and check the indexer state.
The backend deliberately makes no live RPC request while serving an API read.

### Browser requests fail CORS

Add the frontend's exact origin to `DEX_CORS_ORIGINS`, for example
`http://localhost:5173`. Do not include a trailing slash or path. Restart the
backend after changing environment variables.

### Hardhat Solidity tests fail on Node 25

Use Node.js 22, which is the declared contract-tooling runtime. The current
Hardhat Solidity reporter uses a style alias rejected by Node 25. The DEX Mocha
suite is run by `npm run verify`; the sample Solidity test is available through
`npm run test:solidity` on the supported runtime.
