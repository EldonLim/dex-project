# Member 3 Frontend API Handoff

This is the frontend integration summary for the Java backend. The complete
payload examples and field descriptions remain in the
[backend README](../README.md#read-api); this document records the client rules
that are easiest to get wrong.

## Scope and base URL

The backend exposes read-only HTTP resources under `/api/v1`. For local
development the default base URL is:

```text
http://localhost:8080/api/v1
```

The frontend continues to send `placeOrder` and `cancelOrder` transactions
directly to the checked-in contracts through the user's wallet. There is no
backend login, order-placement, cancellation, or settlement endpoint, and the
frontend must never send a user private key to the backend.

## Endpoint summary

| Method and path | Parameters | Purpose |
| --- | --- | --- |
| `GET /markets` | none | List markets for the configured deployment. |
| `GET /markets/{marketId}/orderbook` | `limit` optional, default 50, range 1–200 | Return individual eligible bids and asks, each independently limited. |
| `GET /orders` | `owner` required; `marketId`, `status`, `cursor`, and `limit` optional | List confirmed orders for one wallet. |
| `GET /orders/{orderId}` | non-negative decimal order ID | Return one confirmed order. |
| `GET /markets/{marketId}/trades` | `cursor` optional; `limit` optional, default 50, range 1–200 | List confirmed trades newest first. |
| `GET /status` | none | Report safe deployment and worker state; this is not a liveness endpoint. |

Supported order-status filters are `OPEN`, `PARTIALLY_FILLED`, `FILLED`, and
`CANCELLED`. The owner filter accepts a 20-byte `0x` Ethereum address and does
not prove wallet ownership.

## Client data rules

- Treat IDs, token amounts, prices, lots, block numbers, and log positions as
  decimal strings. Parse them with JavaScript `BigInt` when arithmetic or
  numeric comparison is required; never convert them to `Number`.
- Token `decimals` and page `limit` are JSON numbers.
- Addresses and hashes are normalized lowercase `0x` strings.
- Quantities are lots. One lot is `baseLotSizeAtoms` base-token atoms.
- Prices are quote-token atoms per lot. A fill's quote atoms equal
  `fillLots × executionPriceQuoteAtomsPerLot`.
- Render token values with the market token's decimals. Do not use floating
  point for trading calculations.
- `tradeId` may be `null`; use the transaction hash plus execution log index as
  the stable event identity for this contract version.

## Pagination and ordering

Wallet orders and trades use opaque keyset cursors. When `nextCursor` is not
`null`, pass it back unchanged as `cursor`. Do not decode, construct, persist as
a permanent bookmark, or reuse a cursor with different filters.

The order book is a bounded snapshot, not a cursor page. Its `limit` applies
separately to bids and asks, and each side has its own `hasMore` flag. It returns
individual orders rather than aggregated price levels.

## Projection freshness

All market, order, and trade responses come from confirmed PostgreSQL
projections. They may lag the wallet receipt and chain head by the configured
confirmation depth and polling delay. After a wallet transaction succeeds, the
frontend should show a pending/indexing state and refresh the relevant read
resource until the confirmed projection appears. It must not synthesize a
confirmed backend order or trade from the wallet receipt.

Use `/status` to explain operational delay to a developer or operator. The
endpoint returns HTTP 200 even for `STARTING` or `DEGRADED`; inspect its JSON
`status` and worker states. Use `/actuator/health` for infrastructure health
checks, not from normal browser UI.

## Errors and CORS

API errors use `application/problem+json` and include `type`, `title`, `status`,
`detail`, and `instance`. Invalid requests also include an `errors` array. The
stable problem types are:

```text
urn:dex:problem:invalid-request
urn:dex:problem:not-found
urn:dex:problem:internal-error
```

Display a user-friendly message based on the status/type. Do not depend on
internal exception text; it is intentionally not exposed.

Browser calls are allowed only from exact origins in `DEX_CORS_ORIGINS`. The
API permits `GET` and preflight `OPTIONS`, does not allow credentials, and does
not apply these CORS rules to Actuator.

## Recommended frontend flow

1. Load `/markets`, then the selected market's order book and recent trades.
2. Load `/orders?owner={connectedAddress}` after a wallet is connected.
3. Submit placement or cancellation through MetaMask against the OrderBook.
4. Wait for the wallet transaction receipt, then poll the corresponding backend
   read resource until the confirmed projection reflects it.
5. Pass returned cursors unchanged for additional order or trade pages.

For complete response JSON and sorting semantics, use the
[Read API reference](../README.md#read-api) as the authoritative client
contract.
