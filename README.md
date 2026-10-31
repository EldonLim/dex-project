# SC4053 Ethereum DEX

This package combines the team's Spring Boot backend and automatic matcher,
Solidity contracts, PostgreSQL projection, and React frontend.

Start with **[START_HERE.md](START_HERE.md)** for the exact Windows CMD run
commands and MetaMask test flow.

| Folder | Purpose |
| --- | --- |
| `backend/` | Member 1's Spring Boot APIs, indexer, database projection, and tests |
| `backend/live-matcher/` | Optional legacy TypeScript development matcher; not used by the normal application |
| `smart-contracts/` | Member 2's ERC-20, registries, order book, and settlement contracts |
| `frontend/` | Member 3's React trading website and MetaMask integration |

The live path is self-custodial: users sign approvals and orders in MetaMask;
Spring indexes confirmed events, serves the read API, selects compatible
orders, and calls the contract's atomic `executeTrade` settlement function.
