# DEX Smart Contracts

## Overview

This module implements the smart-contract layer for a decentralised
exchange (DEX) prototype. It provides token registration, market
registration, order creation and cancellation, and on-chain settlement
for matched buy and sell orders.

The contracts are designed to integrate with: - **Member 1:** the
matching engine, which identifies compatible orders and determines the
fill quantity and execution price. - **Member 3:** the frontend, which
connects wallets, approves tokens, submits transactions, and displays
orders and trades.

## Main features

-   ERC-20 mock tokens for local testing.
-   Owner-managed token registry.
-   Owner-managed market registry.
-   Order placement and cancellation.
-   Partial and full fills.
-   On-chain validation of matched orders and execution prices.
-   Atomic token settlement using OpenZeppelin `SafeERC20`.
-   Events for order placement, cancellation, and trade execution.
-   Hardhat-based compilation and automated tests.

## Technology stack

-   Solidity `0.8.34` (as configured in the current Hardhat setup)
-   Hardhat
-   TypeScript
-   Ethers
-   Mocha and Chai
-   OpenZeppelin Contracts

## Project structure

The main files are expected to be organised as follows. Check the actual
repository if filenames or test locations differ.

``` text
dex-contracts/
├── contracts/
│   ├── MockToken.sol
│   ├── TokenRegistry.sol
│   ├── MarketRegistry.sol
│   └── OrderBook.sol
├── scripts/
│   └── deploy.ts
├── test/
│   └── DEX.test.ts
├── hardhat.config.ts
├── package.json
└── README.md
```

Hardhat writes compiled artifacts under `artifacts/`. Do not manually
edit generated artifacts.

## Prerequisites

Install a compatible Node.js version and npm. This project uses npm
commands below. If the repository already contains a lockfile, preserve
it and use the package manager selected by the team.

## Installation

From this directory:

``` bash
npm install
```

If OpenZeppelin Contracts is not installed:

``` bash
npm install @openzeppelin/contracts
```

## Compile contracts

``` bash
npx hardhat compile
```

The current Hardhat configuration uses Solidity `0.8.34`, with the
optimizer and `viaIR` enabled in the configured compilation profiles.
Confirm that the `default` profile has the intended settings, since
`npx hardhat compile` normally uses the default profile.

## Run tests

``` bash
npx hardhat test
```

Before integration, verify that the complete test suite passes. Tests
should cover at least:

-   Mock-token deployment and initial supply.
-   Token registration and invalid token rejection.
-   Market registration and invalid market rejection.
-   Order placement, order IDs, and cancellation permissions.
-   Partial fills and full fills.
-   Rejection of invalid quantities, sides, markets, and execution
    prices.
-   Token balances and allowances required for settlement.
-   Rollback of order state and token transfers when settlement reverts.
-   Correct event values and order status updates.

Do not rely on a passing test count alone. Confirm that the important
settlement behaviours are covered by assertions.

## Contract architecture

### `MockToken.sol`

An ERC-20 token used for local development and tests. It mints the
configured initial supply to the deploying account.

### `TokenRegistry.sol`

Maintains the list of approved token contracts. Market registration
relies on this registry to check whether the base and quote tokens are
approved.

### `MarketRegistry.sol`

Registers markets that pair an approved base token with an approved
quote token. A market stores its base token, quote token, base lot size
in token atoms, and registration status.

The `OrderBook` expects the registry to expose:

``` solidity
function isMarketRegistered(uint256 marketId)
    external
    view
    returns (bool);

function getMarketTokens(uint256 marketId)
    external
    view
    returns (
        address baseToken,
        address quoteToken,
        uint256 baseLotAtoms
    );
```

Ensure `getMarketTokens()` exists in `MarketRegistry.sol` and is
included in its newly compiled ABI.

### `OrderBook.sol`

Stores orders and validates trade settlement. The agreed order structure
is:

``` solidity
enum Side { BUY, SELL }
enum Status { OPEN, FILLED, CANCELLED }

struct Order {
    uint256 id;
    address owner;
    uint256 marketId;
    Side side;
    uint256 originalLots;
    uint256 remainingLots;
    uint256 limitPrice;
    Status status;
}
```

Order IDs start at 1 and increase monotonically. `originalLots` stays
unchanged after fills. `remainingLots` decreases only after a successful
settlement. A partially filled order remains `OPEN`; it becomes `FILLED`
when no lots remain. An owner can cancel an open order.

## Matching and settlement interface

Member 1's matching engine should identify a compatible buy order and
sell order, determine the fill quantity and execution price, and call
the settlement function using the agreed interface:

``` solidity
function executeTrade(
    uint256 buyOrderId,
    uint256 sellOrderId,
    uint256 fillLots,
    uint256 executionPrice
) external;
```

The contract validates that: - Both orders exist and are open. - The
first order is a buy and the second is a sell. - Both orders belong to
the same market. - The fill quantity is positive and does not exceed
either order's remaining quantity. - The execution price is positive and
satisfies both limit prices.

The caller is not restricted to Member 1 by default. The contract
validates the trade parameters, but it does not independently enforce a
matching algorithm or price-time priority.

## Quantity and price units

Use integer arithmetic throughout Solidity and the matching engine. In
TypeScript, use `bigint` values such as those returned by Ethers
utilities. Avoid floating-point arithmetic for token amounts,
quantities, or prices.

Definitions:

-   `baseLotAtoms`: smallest base-token units in one lot.
-   `fillLots`: number of lots traded.
-   `limitPrice`: quote-token atoms per base lot.
-   `executionPrice`: quote-token atoms per filled lot.

Calculations:

``` text
baseAtoms  = fillLots * baseLotAtoms
quoteAtoms = fillLots * executionPrice
```

Example for two tokens with 18 decimals and a lot size of one whole base
token:

-   `baseLotAtoms = 10^18`
-   `fillLots = 4`
-   `executionPrice = 2 * 10^18` quote-token atoms per lot
-   `baseAtoms = 4 * 10^18` (4 base tokens)
-   `quoteAtoms = 8 * 10^18` (8 quote tokens)

This example applies only when the market's configured lot size is one
whole base token. Always read the actual market configuration rather
than assuming this value.

## Token approvals and settlement

Before settlement, the seller must approve the `OrderBook` to spend the
required base tokens, and the buyer must approve it to spend the
required quote tokens. Each participant must also hold enough of the
relevant token.

Settlement transfers base tokens from seller to buyer and quote tokens
from buyer to seller. If a transfer fails, the transaction should revert
atomically, including any order-state updates made in that transaction.

Orders do **not** reserve funds when placed. The frontend and matching
engine should handle failed settlement transactions and should not
report a trade as completed until the transaction succeeds.

## Deployment and integration

Deploy contracts in dependency order:

1.  Deploy mock tokens for local testing.
2.  Deploy `TokenRegistry`.
3.  Register approved tokens.
4.  Deploy `MarketRegistry` with the `TokenRegistry` address.
5.  Deploy `OrderBook` with the `MarketRegistry` address.
6.  Register the market so its event is at or after the backend's configured
    deployment block.
7.  Save the deployed addresses and share them with the integration
    team.

After changing contract code, recompile and redeploy before relying on
the new implementation. Restarting a local Hardhat node usually resets
its local chain state; redeploy and refresh the addresses after a reset.

For the repeatable local workflow, use Node.js 22 and run:

```bash
npm ci
npm run verify
npm run node:local
```

In a second terminal:

```bash
cd smart-contracts
npm run deploy:local
```

The deployment command creates `deployments/localhost.json` and the private,
owner-readable `deployments/localhost.env`. Both are ignored and must be
regenerated after every node reset. The environment file contains a random,
funded executor key for that disposable chain only.

The project-root `START_HERE.md` uses `npm run deploy:ui`. That command creates
an empty interactive order book, funds the two standard Hardhat trader
accounts, generates a disposable Java executor, and writes matching ignored
configuration for Spring, Windows CMD, and Vite. It is the official integrated
frontend and Java-backend workflow. `deploy:local` retains the seeded
Alice-100/Bob-40 fixture used by backend acceptance automation. Both regenerate
the ignored deployment identity after a chain reset.

To run deployment, the Alice-100/Bob-40 backend settlement, on-chain balance
checks, and backend restart-idempotency as one command:

```bash
npm run e2e:backend
```

This command uses an isolated PostgreSQL Compose project and cleans it up on
exit. See
[`backend/docs/ethereum-local-acceptance.md`](../backend/docs/ethereum-local-acceptance.md)
for the exact scenario and optional port overrides.

## Export ABIs

Hardhat artifacts contain the ABI and other compilation output. After a
successful compile, standalone ABI JSON files can be extracted from the
artifacts. Example commands (run from the project root on macOS/Linux
with `jq` installed):

``` bash
mkdir -p abi

jq '.abi' artifacts/contracts/MockToken.sol/MockToken.json > abi/MockToken.json
jq '.abi' artifacts/contracts/TokenRegistry.sol/TokenRegistry.json > abi/TokenRegistry.json
jq '.abi' artifacts/contracts/MarketRegistry.sol/MarketRegistry.json > abi/MarketRegistry.json
jq '.abi' artifacts/contracts/OrderBook.sol/OrderBook.json > abi/OrderBook.json
```

Confirm that the artifact paths match the actual source filenames. Share
the ABIs together with the matching deployed addresses and network/chain
ID. An ABI does not include the deployed address.

## Known limitations and security considerations

-   No funds are reserved when orders are placed.
-   No automatic matching or price-time priority is implemented
    on-chain.
-   `executeTrade()` validates a proposed match but does not prove that
    the matching engine followed a specific priority policy.
-   Any account may call settlement unless additional access control is
    implemented. The contract still checks order details and price
    limits, but the team should review whether this permission model
    matches the intended design.
-   Fee-on-transfer, rebasing, and other non-standard ERC-20 tokens are
    not specifically supported.
-   The code has not been represented as audited or suitable for
    production deployment.
