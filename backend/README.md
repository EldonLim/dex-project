# DEX Backend

Spring Boot backend for the university decentralized-exchange project.

## Prerequisites

- Java 21
- Docker with Docker Compose

## Run locally

From the repository root, create and load the local environment, start PostgreSQL,
and run the application:

```bash
cp .env.example .env
set -a
source .env
set +a
docker compose up -d postgres
cd backend
./mvnw spring-boot:run
```

The example environment explicitly selects `MOCK` blockchain mode. Ethereum
configuration values are defined, but do not select `ETHEREUM` mode yet: the
full gateway still needs settlement encoding and signing. Never commit `.env`
or real private keys.

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

The Ethereum client is intentionally not yet registered as a
`BlockchainGateway`. Ethereum mode still needs `executeTrade` transaction
encoding and signing before one complete adapter can satisfy the entire
gateway contract. It never returns placeholder data and never silently falls
back to mock mode.

- [Solidity integration reference](docs/contract-assumptions.md)
- [Member 2 integration checklist](docs/member-2-integration-checklist.md)

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

## Run tests

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
