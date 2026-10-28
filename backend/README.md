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
