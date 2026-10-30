# DEX frontend

Use the project-root **[START_HERE.md](../START_HERE.md)** guide. The normal
frontend commands are:

```bat
copy .env.example .env
npm install
npm run dev
```

Open <http://localhost:5173/>. The frontend reads markets, orders, trades,
balances, and allowances from Hardhat, sends user transactions through
MetaMask, and reads matcher status from `http://127.0.0.1:8081/health`.

Tests:

```bat
npm run build
npm test -- --run
```
