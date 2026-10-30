# Local Ethereum acceptance

This runbook exercises the complete backend against the checked-in Solidity
contracts on a disposable Hardhat JSON-RPC chain. It is the integration check
for the Alice-100/Bob-40 scenario and restart idempotency.

## Prerequisites

- Java 21
- Docker with Docker Compose
- Node.js 22 (the supported contract-tooling runtime)
- npm dependencies installed with `npm ci` under `smart-contracts/`

Node 25 is intentionally outside the declared engine range. The current
Hardhat Solidity-test reporter uses a terminal-style alias that Node 25 rejects.
The TypeScript/Mocha DEX suite still passes there, but use Node 22 for the full
supported toolchain.

## Verify contracts

```bash
cd smart-contracts
npm ci
npm run verify
```

This compiles the contracts, type-checks the deployment and verification
scripts, and runs the Mocha contract suite. The unrelated sample Solidity test
can additionally be run under the supported Node version with:

```bash
npm run test:solidity
```

## Run the backend acceptance flow

Make sure ports `8545`, `55432`, and `18080` are free, then run:

```bash
cd smart-contracts
npm run e2e:backend
```

Optional port overrides are:

```bash
DEX_E2E_DATABASE_PORT=55433 \
DEX_E2E_BACKEND_PORT=18081 \
npm run e2e:backend
```

The command performs the following work:

1. Starts a disposable Hardhat node with chain ID `31337`.
2. Deploys both mock tokens, both registries, and the OrderBook.
3. Registers the market after OrderBook deployment so the configured indexing
   range includes every reconstructive event.
4. Funds Alice with 100 base lots and Bob with the quote value for 40 lots.
5. Creates approvals, Alice's 100-lot sell, and Bob's compatible 40-lot buy.
6. Generates and funds a random executor used only for this local run.
7. Writes ignored deployment metadata and backend environment files.
8. Starts an isolated PostgreSQL Compose project and the real Spring backend.
9. Waits for indexing, matching, signing, broadcasting, receipt observation,
   and indexed confirmation.
10. Verifies one 40-lot trade, the 60/0 order remainders, and both token legs.
11. Restarts the backend against the same database and chain.
12. Verifies exactly one trade and one executor transaction still exist.

The script removes its isolated PostgreSQL container and volume and stops both
application processes on exit. If it fails, it prints and retains the temporary
log directory for diagnosis.

## Generated identity and secrets

The deployment script writes:

- `smart-contracts/deployments/localhost.json` with safe deployment identity,
  addresses, transaction hashes, and fixture values;
- `smart-contracts/deployments/localhost.env` with matching Spring variables
  and the ephemeral executor key.

Both files are ignored. The environment file is created with owner-only
permissions. Never commit its key or reuse it outside the disposable local
chain.

The deployment ID incorporates the OrderBook deployment-block hash. The
backend also validates the complete block hash, chain ID, OrderBook address,
and MarketRegistry address. Resetting the local node therefore requires a new
deployment and cannot silently reuse an old projection.
