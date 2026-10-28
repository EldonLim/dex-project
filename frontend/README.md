# Run Canopy DEX

Use **Windows Command Prompt (CMD)**. Install a Hardhat-compatible Node.js version and MetaMask in Chrome or Edge first.

Open three terminals in the extracted **project root** (the folder containing `smart-contracts`, `frontend` and `backend`).

## 1. Terminal 1: start the blockchain

```cmd
cd smart-contracts
npm ci
npx hardhat node
```

Keep this terminal running. Use the printed test accounts only on the local chain.

## 2. Terminal 2: deploy contracts

```cmd
cd smart-contracts
npx hardhat run scripts/deploy.ts --network localhost
```

Save the printed token and contract addresses. Deploy once per fresh Hardhat node.

## 3. Terminal 3: start the frontend

```cmd
cd frontend
npm ci
if not exist .env copy .env.example .env
notepad .env
```

Set the following values using the addresses from Terminal 2:

```dotenv
VITE_CHAIN_ID=31337
VITE_RPC_URL=http://127.0.0.1:8545
VITE_TOKEN_REGISTRY_ADDRESS=<TokenRegistry address>
VITE_MARKET_REGISTRY_ADDRESS=<MarketRegistry address>
VITE_ORDER_BOOK_ADDRESS=<OrderBook address>
```

Replace placeholders with actual addresses, without angle brackets. Save the file, then run:

```cmd
npm run dev
```

Open the printed Local URL, normally **http://localhost:5173**, in the browser with MetaMask. Keep Terminal 3 running. Restart Vite after changing `.env`.

## 4. Configure MetaMask

1. Add a custom network with these values:

   | Field | Value |
   | --- | --- |
   | Network name | Hardhat Local |
   | RPC URL | `http://127.0.0.1:8545` |
   | Chain ID | `31337` |
   | Currency | ETH |
   | Block explorer | Leave empty |

2. Save and select **Hardhat Local**.
3. Import **Account #0** using its private key from Terminal 1.
4. Rename it **Hardhat Deployer** and select it.
5. On the website, click **Connect wallet** and authorize this account.
6. Check that the website address matches Account #0 and shows TKA/TKB balances.

**Wrong account connected?** Select Hardhat Deployer in MetaMask and connect it to the site. If the old account persists, remove the site's permission in MetaMask, reload the website, reconnect and select only Hardhat Deployer.

## 5. Test placement and cancellation

1. Select **Sell**, amount **10**, limit price **2**.
2. Click **Approve TKA**, confirm in MetaMask and wait for confirmation.
3. Click **Place sell**, confirm and check **Order book** / **My orders**.
4. Under **My orders**, click **Cancel** and confirm. Verify status becomes **CANCELLED**.
5. To test buying, select **Buy**, amount **5**, price **2**, approve **10 TKB**, then place the order.

**Current limitation:** the supplied backend has no automatic matcher yet. Compatible orders stay open until a settlement transaction is executed. Contract tests do not fill the website's orders.

## 6. Optional: prepare two traders

1. Import Hardhat Accounts **#1** and **#2** into MetaMask as Alice and Bob.
2. As the deployer, import TKA and TKB using the token addresses printed during deployment (18 decimals).
3. Send **500 TKA to Alice** and **1,000 TKB to Bob**.
4. Connect Alice: approve TKA and place a **100 TKA sell at 2 TKB**.
5. Connect Bob: approve 80 TKB and place a **40 TKA buy at 2 TKB**.
6. When Member 1's matcher is available, start it using their instructions. Verify a **40 TKA / 80 TKB** trade, Alice's **60 TKA remainder**, and Bob's **FILLED** order.

## 7. Run tests

From `smart-contracts`:

```cmd
npx hardhat test test/DEX.test.ts
```

From `frontend`:

```cmd
npm test
npm run build
```

## 8. Stop or restart

- Press **Ctrl+C** in each server terminal to stop.
- For later runs, skip `npm ci` unless dependencies changed.
- Restarting Hardhat resets the local chain: redeploy, check frontend addresses and redistribute test tokens.
- Restarting only the frontend preserves chain data.

## Quick fixes

| Issue | Action |
| --- | --- |
| `Copy-Item` not recognized | Use CMD's `copy` command as shown above. |
| `Wallet unavailable` | Use the browser profile with MetaMask enabled and refresh. |
| `Failed to fetch` / missing market | Check Terminal 1, deployment addresses and `.env`; restart Vite after edits. |
| Wrong network | Select Hardhat Local, chain ID 31337. |
| Zero token balances | Connect the deployer or transfer test tokens to the trader. |
| MetaMask shows `$0.00` | Check the DEX token balances; do not buy real ETH for local testing. |
| `Matcher offline` | Expected with the current backend scaffold. |
