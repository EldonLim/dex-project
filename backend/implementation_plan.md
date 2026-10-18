# Java DEX Backend Learning Roadmap

## Current State

- The repository is a clean monorepo skeleton containing only placeholder READMEs, `.gitignore`, and a safe `.env.example`.
- No Java project, database schema, CI workflow, ABI, deployment metadata, contracts, or frontend implementation exists yet.
- No `AGENTS.md` instructions were found.
- The working tree is clean and already on `eldon-backend-init-branch`, not `main`.
- The local environment has Java 21, Maven, Docker, and Docker Compose.
- The initial technology baseline will be Java 21, Maven Wrapper, PostgreSQL, Flyway, Spring Data JPA, Testcontainers, and the current stable Spring Boot release. Spring Boot 4.1.1 supports Java 21 and the installed Maven version; exact versions will be revalidated in the Stage 1 plan. [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html)

## Roadmap

### 1. Backend scaffolding, database, and configuration

- Checkpoint 1A: Create the single Spring Boot Maven application and package structure.
- Checkpoint 1B: Add PostgreSQL through Docker Compose and establish explicit environment-variable configuration.
- Checkpoint 1C: Introduce the initial Flyway schema for markets, orders, trades, execution attempts, processed events, and checkpoints.
- Checkpoint 1D: Add startup validation, configuration tests, Maven Wrapper, and a basic CI build.
- Exit condition: a fresh developer can start PostgreSQL, run migrations, launch the application, and execute its smoke tests.

Flyway will be the only schema-creation mechanism; Hibernate will validate rather than create tables. PostgreSQL requires Flyway’s database-specific module. [Spring Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

### 2. Domain model, provisional gateway, and mock chain

- Checkpoint 2A: Define ABI-independent market, order, trade, chain-position, and settlement value objects.
- Checkpoint 2B: Establish address normalization, `BigInteger` arithmetic, lot/price units, statuses, and decimal-string JSON rules.
- Checkpoint 2C: Define `BlockchainGateway` and its application-facing records without exposing Web3j types.
- Checkpoint 2D: Implement deterministic mock chain fixtures and visibly identified mock mode.
- Checkpoint 2E: Write the contract-assumptions document and unit tests for mappings and large values.
- Exit condition: backend behavior can be developed and tested without contracts, while every provisional contract dependency has one replaceable boundary.

### 3. Event indexing and database projections

- Checkpoint 3A: Fetch mock events in bounded block ranges and sort them by block, transaction, and log position.
- Checkpoint 3B: Apply each event and its checkpoint atomically while recording unique event identity.
- Checkpoint 3C: Resume from checkpoints and prevent duplicate fills or trades after restart.
- Checkpoint 3D: detect checkpoint hash mismatches or chain resets, pause unsafe work, and expose degraded status.
- Checkpoint 3E: Test duplicate events, restarts, transaction rollback, confirmation depth, and reset detection.
- Exit condition: deterministic mock-chain events produce durable, idempotent PostgreSQL projections.

### 4. Versioned read APIs

- Checkpoint 4A: Add stable DTOs, validation, decimal-string integer serialization, and consistent error responses.
- Checkpoint 4B: Implement market, individual-order orderbook, wallet-order, order-detail, trade-history, and status reads under `/api/v1`.
- Checkpoint 4C: Add bounded cursor pagination, deterministic sorting, configurable CORS, and freshness metadata.
- Checkpoint 4D: Add controller/integration tests and complete request/response examples.
- Exit condition: Member 3 can build the frontend entirely against documented public read endpoints.

### 5. Matching engine

- Checkpoint 5A: Implement the matcher as a pure, independently testable domain service.
- Checkpoint 5B: Apply market compatibility, crossed-price checks, price/time priority, partial fills, and older-order execution pricing.
- Checkpoint 5C: Exclude unresolved reservations and temporarily ineligible orders without changing their confirmed on-chain status.
- Checkpoint 5D: Coordinate a non-overlapping scheduled matching cycle.
- Checkpoint 5E: Test incompatible prices, tie-breaking, partial fills, large values, self-trade policy, and blocked orders.
- Exit condition: the same projection always yields the same proposed match without submitting a transaction.

### 6. Durable settlement lifecycle and recovery

- Checkpoint 6A: Define the execution-attempt state machine and reservation lifecycle.
- Checkpoint 6B: Add one serialized worker, one executor account, and at most one unresolved settlement transaction.
- Checkpoint 6C: Persist transaction identity, nonce, and attempt information before or alongside broadcast as appropriate.
- Checkpoint 6D: Track receipts and reconcile success, revert, timeout, uncertain broadcast, cancellation races, and restarts.
- Checkpoint 6E: Ensure successful receipts do not directly mutate order projections; confirmed events remain the sole projection path.
- Exit condition: retries and restarts cannot duplicate a pending fill or silently abandon a reservation.

### 7. Ethereum/Web3j adapter

- Checkpoint 7A: Ingest Member 2’s finalized ABI and deployment metadata, then resolve every documented provisional assumption.
- Checkpoint 7B: Implement chain/deployment validation, ranged log retrieval, event decoding, getters, balances, and allowances.
- Checkpoint 7C: Implement executor signing, nonce handling, transaction submission, receipt lookup, and sanitized errors.
- Checkpoint 7D: Run the complete Alice-100/Bob-40 flow against Anvil and verify restart idempotency.
- Exit condition: Ethereum mode passes the end-to-end scenario using the actual contracts, or remaining incompatibilities are listed explicitly if final artifacts are still unavailable.

Mock mode and Ethereum mode will be explicitly configured; failure to contact Ethereum must never trigger an automatic mock fallback.

### 8. Consolidation, documentation, and final verification

- Checkpoint 8A: Complete the backend README covering setup, configuration, mock/Ethereum operation, APIs, troubleshooting, and reset/reindex procedures.
- Checkpoint 8B: Finish Member 2’s ABI/deployment checklist and Member 3’s API handoff.
- Checkpoint 8C: Document why off-chain matching was selected: cheaper and simpler on-chain storage, easier matching-policy iteration, deterministic backend indexing, and simpler querying.
- Checkpoint 8D: Also document its limitations: matcher/executor availability is operationally centralized, third-party executors are not forced to respect backend priority, and the contract remains the asset-safety boundary.
- Checkpoint 8E: Run the full unit, PostgreSQL integration, API, recovery, mock scenario, and available Anvil test suites.
- Exit condition: the final report states what works, how to run it, what was tested, and every dependency on Member 2.

## Stable Interfaces and Boundaries

- Public HTTP surface: only the specified `/api/v1` read endpoints; no backend order placement, cancellation, login, or public settlement endpoint.
- Blockchain boundary: domain-facing `BlockchainGateway`, deterministic mock implementation, and later Ethereum/Web3j implementation.
- Persistence boundary: confirmed on-chain projections remain separate from local execution attempts and reservations.
- Numeric contract: token quantities, prices, IDs, blocks, and nonces use exact integers; externally visible large integers are decimal strings.
- Security boundary: users sign through MetaMask, approve the DEX contract, and never provide private keys to the backend.
- Repository boundary: implementation stays primarily under `backend/`; teammate-owned contracts and frontend files are not invented or overwritten.

## Working Method and Defaults

- Before implementing each stage, create a separate decision-complete plan based on the code produced by previous stages.
- Work through one small checkpoint at a time, explaining its purpose, implementation, and verification before advancing.
- Each stage must finish with passing tests, runnable instructions, and a concise learning summary.
- Package namespace defaults to `com.team.dex` unless the team establishes an organizational namespace before Stage 1.
- Initial matching assumptions remain those in the handoff, including older-order execution pricing. The self-trade policy will be explicitly chosen during Stage 5 planning because it is a product/contract coordination decision.
- No mainnet deployment, microservices, Kafka, Redis, Kubernetes, traditional authentication, WebSockets, or full automatic reorg recovery are included.
- No merge or push will occur unless explicitly requested.
