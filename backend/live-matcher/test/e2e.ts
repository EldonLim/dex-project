import assert from "node:assert/strict";
import { Contract, JsonRpcProvider, NonceManager, Wallet, parseUnits } from "ethers";
import { loadConfig } from "../src/config.js";
import { LiveMatcher } from "../src/matcher.js";

const traderKey = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";
const tokenAbi = [
  "function transfer(address,uint256) returns (bool)",
  "function approve(address,uint256) returns (bool)",
  "function balanceOf(address) view returns (uint256)",
];
const orderAbi = [
  "function placeOrder(uint256,uint8,uint256,uint256) returns (uint256)",
  "function cancelOrder(uint256)",
  "function getOrder(uint256) view returns (tuple(uint256 id,address owner,uint256 marketId,uint8 side,uint256 originalLots,uint256 remainingLots,uint256 limitPrice,uint8 status))",
  "function nextOrderId() view returns (uint256)",
];

async function main() {
  const config = loadConfig();
  const provider = new JsonRpcProvider(config.rpcUrl, config.chainId, { staticNetwork: true });
  const sellerWallet = new Wallet(config.privateKey, provider);
  const buyerWallet = new Wallet(traderKey, provider);
  const seller = new NonceManager(sellerWallet);
  const buyer = new NonceManager(buyerWallet);
  const deployment = JSON.parse(await (await import("node:fs/promises")).readFile(
    new URL("../../../smart-contracts/deployments/localhost.json", import.meta.url), "utf8",
  ));
  const tokenA = new Contract(deployment.contracts.MockTokenA, tokenAbi, seller);
  const tokenB = new Contract(deployment.contracts.MockTokenB, tokenAbi, seller);
  const buyerTokenB = tokenB.connect(buyer) as Contract;
  const sellerBook = new Contract(config.orderBookAddress, orderAbi, seller);
  const buyerBook = sellerBook.connect(buyer) as Contract;

  await (await tokenB.transfer(buyerWallet.address, parseUnits("100", 18))).wait();
  await (await tokenA.approve(config.orderBookAddress, parseUnits("100", 18))).wait();
  await (await buyerTokenB.approve(config.orderBookAddress, parseUnits("100", 18))).wait();

  // Make the acceptance script repeatable on a chain used by an earlier run.
  const existingNextId = BigInt(await sellerBook.nextOrderId());
  for (let id = 1n; id < existingNextId; id += 1n) {
    const existing = await sellerBook.getOrder(id);
    if (Number(existing.status) !== 0) continue;
    const owner = String(existing.owner).toLowerCase();
    if (owner === sellerWallet.address.toLowerCase()) {
      await (await sellerBook.cancelOrder(id)).wait();
    } else if (owner === buyerWallet.address.toLowerCase()) {
      await (await buyerBook.cancelOrder(id)).wait();
    }
  }

  const firstOrderId = BigInt(await sellerBook.nextOrderId());
  await (await sellerBook.placeOrder(1n, 1, 4n, parseUnits("2", 18))).wait();
  const buyOrderId = firstOrderId + 1n;
  await (await buyerBook.placeOrder(1n, 0, 2n, parseUnits("3", 18))).wait();

  const buyerBaseBefore = BigInt(await tokenA.balanceOf(buyerWallet.address));
  const sellerQuoteBefore = BigInt(await tokenB.balanceOf(sellerWallet.address));
  const matcher = new LiveMatcher(config);
  const executed = await matcher.runCycle();

  const sellOrder = await sellerBook.getOrder(firstOrderId);
  const buyOrder = await sellerBook.getOrder(buyOrderId);
  assert.ok(executed >= 1, "matcher should execute at least one trade");
  assert.equal(BigInt(sellOrder.remainingLots), 2n, "sell order should be partially filled");
  assert.equal(Number(sellOrder.status), 0, "partially filled order remains OPEN on chain");
  assert.equal(BigInt(buyOrder.remainingLots), 0n, "buy order should be fully filled");
  assert.equal(Number(buyOrder.status), 1, "buy order should be FILLED");
  assert.equal(BigInt(await tokenA.balanceOf(buyerWallet.address)) - buyerBaseBefore, parseUnits("2", 18));
  assert.equal(BigInt(await tokenB.balanceOf(sellerWallet.address)) - sellerQuoteBefore, parseUnits("4", 18));

  console.log("E2E PASS: orders were placed from two wallets, automatically matched, partially filled, and settled atomically.");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
