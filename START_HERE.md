# Run the complete DEX on Windows

## 1. Install once

Install Node.js 22 or later and MetaMask. Then open **Command Prompt (CMD)** in
the project root and run:

```bat
cd smart-contracts
npm install
cd ..\backend\live-matcher
npm install
cd ..\..\frontend
npm install
copy .env.example .env
cd ..
```

## 2. Start the local blockchain (CMD terminal 1)

```bat
cd smart-contracts
npx hardhat node
```

Keep this terminal open. Restarting it resets all local blockchain data.

## 3. Deploy and fund the test wallets (CMD terminal 2)

```bat
cd smart-contracts
npx hardhat run scripts/deploy.ts --network localhost
```

This deploys TKA, TKB, the registries, and OrderBook. It also gives Hardhat
accounts 1 and 2 test TKA/TKB. Run this once after each blockchain restart.

## 4. Start automatic matching (CMD terminal 3)

```bat
cd backend\live-matcher
npm start
```

The healthy endpoint is <http://127.0.0.1:8081/health>. Keep this terminal open.

## 5. Start the website (CMD terminal 4)

```bat
cd frontend
npm run dev
```

Open <http://localhost:5173/>.

## 6. Configure MetaMask once

Add a custom network:

| Field | Value |
| --- | --- |
| Network name | Hardhat Local |
| RPC URL | `http://127.0.0.1:8545` |
| Chain ID | `31337` |
| Currency symbol | `ETH` |

Import these **local test accounts only**:

```text
Account 1 private key
0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d

Account 2 private key
0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a
```

Never use these public Hardhat keys on a real network.

## 7. Test an automatically matched trade

1. Connect Account 1 to the website.
2. Select **Sell**, enter amount `4` TKA and price `2` TKB, approve TKA, then place the sell.
3. Switch MetaMask to Account 2 and reconnect if prompted.
4. Select **Buy**, enter amount `2` TKA and price `3` TKB, approve TKB, then place the buy.
5. Wait about two seconds and refresh the page.

The prices cross because the buy limit (`3`) is at least the sell limit (`2`).
The matcher fills `2` lots at the older sell order's price (`2`). The buy becomes
filled, the sell keeps `2` lots remaining, and the contract transfers 2 TKA to
the buyer and 4 TKB to the seller in one atomic transaction.

## 8. Run automated tests

```bat
cd smart-contracts
npx hardhat test test/DEX.test.ts

cd ..\backend\live-matcher
npm run build
npm test
```

For the real-chain acceptance test, keep terminal 1 running, deploy first, then:

```bat
cd backend\live-matcher
npm run test:e2e
```

Frontend:

```bat
cd frontend
npm run build
npm test -- --run
```

The Spring Boot integration tests require Docker Desktop because they use
PostgreSQL Testcontainers:

```bat
cd backend
mvnw.cmd test
```

## Common fixes

- `Copy-Item is not recognized`: you are in CMD; use `copy .env.example .env`.
- `Wallet unavailable`: install/enable MetaMask and refresh the page.
- Wrong wallet: switch account inside MetaMask, then disconnect/reconnect the site.
- Wrong network: choose **Hardhat Local** in MetaMask.
- `Matcher offline`: confirm terminal 3 is running and `/health` says `HEALTHY`.
- No balance: use imported Hardhat Account 1 or 2 and redeploy after restarting the node.
- Old or wrong contract addresses: stop the node, start it again, deploy once, and refresh.
