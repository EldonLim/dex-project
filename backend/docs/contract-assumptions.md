# Solidity Integration Reference

> **Status: source and ABI inspected**
>
> This reference describes the contracts and ABI artifacts currently checked
> into `smart-contracts/`. Function and event shapes below are confirmed by
> compatibility tests. Deployment operations and the security-policy decisions
> called out as open must still be agreed by the team.

## Architecture and responsibilities

- `MarketRegistry` owns market registration and market reads.
- `OrderBook` owns order placement, cancellation, reads, and settlement.
- The blockchain is authoritative for markets, orders, cancellations, and
  trades; PostgreSQL is a rebuildable projection of confirmed logs.
- The frontend submits `placeOrder` and `cancelOrder` through MetaMask. The
  backend never receives user private keys.
- The backend executor submits only `executeTrade` settlements.
- Solidity validates each settlement and uses `SafeERC20.safeTransferFrom` for
  both token legs in one atomic transaction.
- Java matching and preflight improve ordering and reliability. The contract is
  still the asset-safety boundary.

## Why matching is off-chain

- Price/time ordering is easier to implement and test in Java.
- Order searching does not consume gas.
- Only actual settlements require transactions.
- The contract remains focused on validation and atomic transfer safety.
- PostgreSQL provides efficient order-book and history APIs.

The trade-off is that the executor selects which compatible pair to propose.
The current contract validates the submitted pair and price but does not enforce
the Java matcher's global queue. Backend downtime can delay matching without
taking custody of user wallets or tokens.

## Numeric units

```text
one lot = baseLotSizeAtoms base-token atoms

baseAmountAtoms =
    fillLots × baseLotSizeAtoms

quoteAmountAtoms =
    fillLots × executionPriceQuoteAtomsPerLot
```

- Solidity uses `uint256`; Java uses exact `BigInteger` values.
- Floating-point arithmetic is not permitted for trading values.
- APIs serialize blockchain-sized integers as decimal strings.
- Addresses and hashes use normalized lowercase `0x` values.

## Confirmed contract functions

| Contract | Function | Caller | Arguments | Result or purpose |
|---|---|---|---|---|
| `MarketRegistry` | `registerMarket` | Owner/deployment tooling | base token, quote token, base lot atoms | Returns market ID and emits `MarketRegistered` |
| `MarketRegistry` | `getMarket` | Backend | market ID | Base token, quote token, base lot atoms, registered flag |
| `OrderBook` | `placeOrder` | User through MetaMask | market ID, side, lots, limit price | Returns order ID and emits `OrderPlaced` |
| `OrderBook` | `cancelOrder` | User through MetaMask | order ID | Marks the caller-owned open order cancelled |
| `OrderBook` | `getOrder` | Backend | order ID | Returns the complete order tuple |
| `OrderBook` | `executeTrade` | Backend executor | buy ID, sell ID, fill lots, execution price | Validates, updates both orders, transfers both tokens, and emits `TradeExecuted` |

ERC-20 `symbol`, `decimals`, `balanceOf`, and `allowance` calls target token
contracts rather than either DEX contract.

## Confirmed events and normalized projections

Every normalized backend event carries deployment ID, emitting contract,
transaction hash, block hash, block number, transaction index, log index, and
block timestamp. Stable event identity is:

```text
deploymentId + transactionHash + logIndex
```

### `MarketRegistered`

The log provides market ID, base token, quote token, and base lot atoms. The
Ethereum decoder supplements it with ERC-20 `symbol` and `decimals` reads at the
event block before creating `MarketRegisteredEvent`.

### `OrderPlaced`

The log provides order ID, owner, market ID, side, lots, and limit price. The
projection derives:

```text
remainingLots = originalLots
status = OPEN
```

### `OrderCancelled`

The Solidity log provides only the order ID. The projection locates that order
in the configured deployment, preserves its current remaining quantity, and
changes its status to `CANCELLED`.

### `TradeExecuted`

The log provides buy and sell order IDs, market ID, buyer, seller, fill lots,
execution price, base atoms, and quote atoms. It has no trade ID and does not
emit updated order remainders. The projection applies events in chain order and
derives both remainders from the prior confirmed order state. It also validates:

- buyer and seller against the projected order owners;
- base atoms against fill lots and the market lot size;
- quote atoms against fill lots and execution price; and
- the event against any local execution attempt with the same transaction hash.

The API therefore returns `tradeId: null` for this contract version.

## Confirmed enum mapping

```text
Solidity Side:
0 = BUY
1 = SELL

Solidity Status:
0 = OPEN
1 = FILLED
2 = CANCELLED
```

Solidity keeps a partially filled order at status `OPEN`. The Ethereum adapter
maps `OPEN` plus `0 < remainingLots < originalLots` to Java
`PARTIALLY_FILLED`. Numeric mappings live only in the ABI adapter.

## Matching and settlement behavior

- Orders must exist, be open, have BUY/SELL sides, and share a market.
- Fill lots must be positive and cannot exceed either remainder.
- Execution price must be positive, no higher than the buy limit, and no lower
  than the sell limit.
- The Java matcher chooses the older order's price and skips same-owner pairs.
- Both order remainders update before token calls; a failed safe transfer
  reverts the entire transaction.
- The contract emits `TradeExecuted` only after both transfers succeed.

The contract accepts the execution price supplied by the backend when it lies
within both limits. This is why the backend persists its chosen execution price
before signing and includes it in `executeTrade`.

## Chain and deployment identity

Chain ID alone is insufficient, particularly after a local-chain reset. The
backend identifies one deployment using:

- stable deployment ID;
- chain ID;
- OrderBook address;
- MarketRegistry address;
- deployment block number; and
- deployment block hash.

A mismatch pauses indexing and matching. The backend must not silently reuse a
projection from an older local deployment.

## Open team decisions

The ABI is known, but these operational and policy decisions remain:

1. Restricting `executeTrade`: it is currently callable by any address.
2. Self-trade policy: Solidity currently permits it; the Java matcher skips it.
3. Stable error behavior: contracts currently use revert strings rather than
   custom errors.
4. Stable deployment metadata: record both contract addresses, chain ID,
   deployment block number/hash, deployment transaction hashes, and source
   commit or tag.
5. Local startup and reset commands, account funding, token distribution,
   approvals, and initial-market registration.
6. Deployment ordering: the configured start block must include the
   `MarketRegistered` event, or the market must be registered after the
   OrderBook deployment and chosen start block.

## Remaining backend integration work

- Encode and sign `executeTrade` with the configured executor.
- Compose the ABI client and generic RPC client into the complete Ethereum
  `BlockchainGateway` only when every gateway method is implemented.
- Add deployment metadata generation and an end-to-end local-chain test.
- Keep mock and Ethereum gateway contract tests aligned.

Do not edit historical Flyway migrations. Persisted model changes require a new
migration after V4.
