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

Tests:

```bat
npm run build
npm test -- --run
```
