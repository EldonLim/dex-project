# Live Ethereum matcher

This adapter fills the two runtime gaps documented by the supplied Java
backend: real Ethereum connectivity and automatic matching.

It reads Member 2's deployment metadata, orders compatible buys and sells by
price/time priority, skips self-trades, checks ERC-20 balances and allowances,
and submits `executeTrade(buyOrderId, sellOrderId, fillLots, executionPrice)`.
The Solidity contract remains the final validation and asset-safety boundary.

```bat
npm install
npm start
```

Endpoints:

- `GET /health`
- `GET /api/v1/status`
- `GET /api/v1/markets`
- `GET /api/v1/markets/{id}/orderbook`
- `GET /api/v1/orders?owner=0x...`
- `GET /api/v1/trades?marketId=1`

See the root `START_HERE.md` for deployment and testing.
