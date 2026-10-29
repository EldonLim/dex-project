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

The example environment explicitly selects `MOCK` blockchain mode. To use
`ETHEREUM` mode, fill in the Ethereum-only variables in `.env`. Matching in
Ethereum mode also requires `DEX_EXECUTOR_PRIVATE_KEY` when
`DEX_MATCHER_ENABLED=true`. Never commit `.env` or real private keys.

## Blockchain integration status

Mock mode is currently implemented. Its standard deterministic fixture contains
one market and two compatible orders, and mock settlement supports pending,
successful, and reverted transaction behavior.

Ethereum/Web3j integration is not implemented yet. Passing the mock tests does
not prove compatibility with the finalized Solidity contracts, and the
application never silently falls back from Ethereum mode to mock mode.

- [Provisional contract assumptions](docs/contract-assumptions.md)
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
in later endpoints use quote-token atoms per lot.

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
