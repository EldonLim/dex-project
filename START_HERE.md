# Run the complete Java-backed DEX locally

The official local application uses Hardhat, PostgreSQL, Spring Boot, React,
and MetaMask. Spring is the only automatic matcher and settlement executor.

## 1. Install once

Install Node.js 22 LTS, Java 21, Docker Desktop with Docker Compose, and
MetaMask. From the project root, install the contract and frontend packages.

Windows Command Prompt:

```bat
cd smart-contracts
npm install
cd ..\frontend
npm install
cd ..
copy .env.example .env
```

macOS or Linux:

```bash
cd smart-contracts
npm install
cd ../frontend
npm install
cd ..
cp .env.example .env
```

## 2. Start the local blockchain (terminal 1)

```text
cd smart-contracts
npm run node:local
```

Keep this terminal open. Restarting Hardhat resets all local blockchain data.

## 3. Deploy and generate application configuration (terminal 2)

From the project root:

```text
cd smart-contracts
npm run deploy:ui
```

This deploys TKA, TKB, the registries, and OrderBook; registers one empty
TKA/TKB market; funds Hardhat accounts 1 and 2 with both tokens; funds a
disposable Java executor; and generates matching ignored configuration for
Spring and Vite. Run it once after every Hardhat restart.

## 4. Start PostgreSQL (terminal 3)

From the project root:

```text
docker compose up -d --wait postgres
```

## 5. Start the Java backend (terminal 4)

Windows Command Prompt, from the project root:

```bat
call smart-contracts\deployments\localhost.cmd
cd backend
mvnw.cmd spring-boot:run
```

macOS or Linux, from the project root:

```bash
set -a
source .env
source smart-contracts/deployments/localhost.env
set +a
cd backend
./mvnw spring-boot:run
```

Wait for Spring to report that it started. Verify:

- <http://localhost:8080/actuator/health> returns `UP`.
- <http://localhost:8080/api/v1/status> reports the indexer, matcher, and
  executor states.

Keep this terminal open. Do not start `backend/live-matcher`; Spring now owns
matching and settlement.

## 6. Start the website (terminal 5)

```text
cd frontend
npm run dev
```

Open <http://localhost:5173/>. The site reads confirmed markets, orders, and
trades from Spring on port 8080. MetaMask still signs approvals, placements,
cancellations, and owner-only registry transactions directly.

## 7. Configure MetaMask once

Add a custom network:

| Field | Value |
| --- | --- |
| Network name | Hardhat Local |
| RPC URL | `http://127.0.0.1:8545` |
| Chain ID | `31337` |
| Currency symbol | `ETH` |

Import these public Hardhat test accounts only:

```text
Account 1 private key
0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d

Account 2 private key
0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a
```

Never use these well-known local keys on a real network.

## 8. Test an automatically matched trade

1. Connect Account 1 to the website.
2. Select **Sell**, enter amount `4` TKA and price `2` TKB, approve TKA, then
   place the sell.
3. Switch MetaMask to Account 2 and reconnect if prompted.
4. Select **Buy**, enter amount `2` TKA and price `3` TKB, approve TKB, then
   place the buy.
5. Wait for the transaction notice to say the backend indexed the transaction.

The prices cross because the buy limit (`3`) is at least the sell limit (`2`).
The Java matcher fills `2` lots at the older sell order's price (`2`). The buy
becomes filled, the sell keeps `2` lots remaining, and the contract atomically
transfers 2 TKA to the buyer and 4 TKB to the seller. The UI then reads the
confirmed result from Spring's PostgreSQL projection.

## 9. Run automated verification

Contracts:

```text
cd smart-contracts
npm run verify
npm run e2e:backend
```

Frontend:

```text
cd frontend
npm run build
npm test -- --run
```

Spring backend tests require Docker Desktop because they use PostgreSQL
Testcontainers.

Windows:

```bat
cd backend
mvnw.cmd verify
```

macOS or Linux:

```bash
cd backend
./mvnw verify
```

## Stop everything

Stop Hardhat, Spring, and Vite with `Ctrl-C`, then from the project root run:

```text
docker compose down
```

Use `docker compose down --volumes` only when you intentionally want to remove
the local PostgreSQL projection and rebuild it.

## Common fixes

- **Backend offline:** confirm PostgreSQL is healthy and Spring is running on
  port 8080.
- **Deployment identity mismatch:** restart Hardhat, rerun `deploy:ui`, restart
  Spring with the newly generated environment, and restart Vite.
- **Wrong wallet:** switch accounts inside MetaMask, then disconnect/reconnect
  the site.
- **Wrong network:** choose **Hardhat Local** in MetaMask.
- **No balance:** use imported Hardhat Account 1 or 2 and redeploy after
  restarting Hardhat.
- **Projection still starting:** wait for `/api/v1/status` to report an indexed
  block and refresh the page.
