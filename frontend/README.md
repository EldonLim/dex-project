# DEX frontend

Use the project-root **[START_HERE.md](../START_HERE.md)** guide. The normal
frontend commands are:

```bat
copy .env.example .env
npm install
npm run dev
```

Open <http://localhost:5173/>. The frontend reads markets, orders, trades, and
operational status from Spring's confirmed PostgreSQL projection. It reads
wallet balances, allowances, and registry ownership from Hardhat and sends user
transactions through MetaMask. The official API base URL is
`http://127.0.0.1:8080/api/v1`.

Automatic settlement may batch up to 10 matches into one blockchain
transaction. No frontend API change is needed: Spring returns every emitted
trade as a separate row, distinguished by transaction hash plus log index.
The dashboard reads the configured batch limit from `/api/v1/status` and marks
trade rows that share one atomic settlement transaction as `Batch n/total`.

Tests:

```bat
npm run build
npm test -- --run
```
