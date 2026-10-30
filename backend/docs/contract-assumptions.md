# Provisional Contract Assumptions

> **Status: provisional**
>
> This document describes the interface currently assumed by the Java backend.
> It is not the finalized Solidity ABI and must not be presented as Member 2's
> implemented contract interface.

## Architecture and responsibilities

- The blockchain is authoritative for markets, orders, cancellations, and
  trades.
- PostgreSQL is a rebuildable off-chain projection of confirmed blockchain
  events.
- The frontend submits user transactions through MetaMask.
- The backend never receives or stores user private keys.
- The backend executor submits only settlement transactions.
- The contract independently validates every settlement and performs both token
  transfers atomically.
- Java preflight checks improve reliability, but Java is not the asset-safety
  or authorization boundary.

## Why matching is off-chain

The backend performs matching because:

- Price/time ordering is easier to implement and test in Java.
- Searching the order book does not consume gas.
- Only actual settlements require blockchain transactions.
- The contract can remain focused on custody-free validation and atomic token
  transfer safety.
- PostgreSQL can serve efficient order-book and trade-history queries.

This design also has trade-offs:

- The backend executor controls which compatible pair it proposes next.
- The contract may validate compatibility without enforcing global price/time
  priority.
- We cannot claim that third-party executors are forced to follow the Java
  matcher's queue.
- Backend downtime can delay matching, although users retain control of their
  wallets and tokens.

## Numeric units

The provisional unit convention is:

```text
one lot = baseLotSizeAtoms base-token atoms

baseAmountAtoms =
    fillLots × baseLotSizeAtoms

quoteAmountAtoms =
    fillLots × executionPriceQuoteAtomsPerLot
```

- Blockchain IDs and amounts are non-negative integers.
- Trading calculations use Java `BigInteger`.
- Floating-point arithmetic is not permitted for prices or quantities.
- Large integer values will be serialized by the API as decimal strings.
- Addresses and hashes use normalized lowercase `0x` representations.

## Provisional contract functions

| Function | Called by | Provisional arguments | Purpose | Confirmation needed |
|---|---|---|---|---|
| `registerMarket` | Deployment/admin tooling | Token addresses and market configuration | Register a market | Exact name, authorization, fields, and return value |
| `placeOrder` | Frontend through MetaMask | Market, side, price, and lots | Create an order | Exact name, argument order, enum encoding, and return value |
| `cancelOrder` | Frontend through MetaMask | Order ID | Cancel the caller-owned remainder | Exact cancellation semantics and emitted fields |
| `getMarket` | Backend | Market ID | Read current market state | Exact return schema and metadata source |
| `getOrder` | Backend | Order ID | Read current order state | Exact return schema and status encoding |
| `executeMatch` | Backend executor | Buy ID, sell ID, and fill lots | Validate and settle a match | Authorization, price rule, return value, and errors |

The backend does not call `placeOrder` or `cancelOrder`; users call them through
MetaMask. Exact Solidity names, argument ordering, return values, visibility,
and numeric encodings remain unconfirmed. ERC-20 `balanceOf` and `allowance`
calls target the token contracts rather than the DEX contract.

## Normalized backend events

The following are normalized values emitted by `BlockchainGateway`. They are
not necessarily one-to-one copies of Solidity event parameters. The eventual
Ethereum adapter may combine decoded logs with supplementary contract reads.

### `MarketRegisteredEvent`

- Market ID
- Base and quote token addresses
- Base and quote symbols
- Base and quote decimals
- Base lot size

Symbols and decimals may require supplementary ERC-20 reads if the DEX event
does not include them.

### `OrderPlacedEvent`

- Order ID
- Market ID
- Owner
- Side
- Limit price
- Original lots

The backend derives the initial state:

```text
remainingLots = originalLots
status = OPEN
```

### `OrderCancelledEvent`

- Order ID
- Market ID
- Remaining lots at cancellation

### `TradeExecutedEvent`

- Optional trade ID
- Market ID
- Buy and sell order IDs
- Fill lots
- Execution price
- Both orders' updated remaining quantities

Every normalized event also carries:

- Deployment ID
- Contract address
- Transaction hash
- Block hash
- Block number
- Transaction index
- Log index
- Block timestamp

The provisional event identity is:

```text
deploymentId + transactionHash + logIndex
```

## Provisional enum mappings

The Java meanings are:

```text
OrderSide:
- BUY
- SELL

OrderStatus:
- OPEN
- PARTIALLY_FILLED
- FILLED
- CANCELLED
```

The Solidity numeric values are unknown. The Ethereum adapter must contain the
final numeric mapping in one place; numeric enum values must not be scattered
through the indexer or domain code.

## Matching assumptions

- Orders must belong to the same market.
- A buy price must be greater than or equal to the sell price.
- Higher-priced buys have priority.
- Lower-priced sells have priority.
- Equal prices use block, transaction, then log position.
- Fill quantity is the smaller remaining quantity.
- Execution price is provisionally the older order's limit price.
- Only `OPEN` and `PARTIALLY_FILLED` orders with a positive remainder are
  eligible.
- Orders reserved by unresolved settlement attempts are excluded.
- The provisional matcher will skip same-owner pairs to avoid self-trades.
- The Solidity contract must still reject invalid quantities, incompatible
  orders, cancelled or filled orders, and unsafe transfers.

The current mock fixture uses different owners and does not yet exercise the
self-trade rule.

## Required contract guarantees to confirm

Member 2 must confirm whether the finalized contract:

- Uses unique market, order, and trade identifiers.
- Prevents orders from changing market or owner.
- Restricts cancellation to the order owner.
- Never fills more than either order's remaining quantity.
- Rejects non-crossing prices.
- Rejects inactive or mismatched markets.
- Rejects cancelled or filled orders.
- Updates both orders atomically.
- Transfers both token legs atomically.
- Reverts the complete settlement if either transfer fails.
- Checks balances and allowances through safe ERC-20 transfers.
- Emits a trade event only after successful state updates and transfers.
- Provides enough event data to reconstruct confirmed projections.
- Defines who may call `executeMatch`.
- Defines its self-trade policy.
- Defines its execution-price behavior.
- Provides stable custom errors or documented revert reasons.

These guarantees are requested assumptions, not claims about the current
Solidity implementation.

## Chain and deployment identity

A chain ID alone cannot identify a deployment, especially after restarting or
resetting a local Anvil chain. The backend identifies a deployment using:

- Deployment ID
- Chain ID
- DEX address
- Deployment block number
- Deployment block hash

A mismatch must pause indexing and matching. The backend must not silently
reuse projections from an earlier local deployment.

## Changes required when the ABI is finalized

Expected change points are:

- The future Ethereum implementation of `BlockchainGateway`.
- The gateway event decoder and ABI-to-domain mappings.
- Provisional normalized event records, if required data changes.
- Mock fixtures and gateway contract tests.
- Ethereum configuration and deployment metadata loading.
- Integration tests against Member 2's local deployment.
- This documentation.

Domain and matching logic should remain ABI-independent. Flyway V1 and V2 must
not be edited. If the final contract requires additional persisted data, add a
new V3 migration.

## Outstanding questions for Member 2

1. What is the exact ABI and artifact format?
2. What are the contract addresses and deployment metadata format?
3. What are the exact function names, arguments, return values, and visibility?
4. What are the exact event parameters, and which parameters are indexed?
5. What numeric values represent sides and statuses?
6. What are the lot and price units?
7. What is the execution-price rule?
8. Does a trade ID exist?
9. Does cancellation emit the remaining quantity?
10. Do trade events emit both updated remaining quantities?
11. Where do token symbols and decimals come from?
12. Who may call `executeMatch`?
13. What is the self-trade behavior?
14. Which custom errors or revert conditions should the backend recognize?
15. What is the local-node startup, deployment, token-distribution, and reset
    process?
