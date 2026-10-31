import { chmod, mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { network } from "hardhat";

const { ethers, networkName } = await network.getOrCreate();

const [deployer, alice, bob] = await ethers.getSigners();
if (deployer === undefined || alice === undefined || bob === undefined) {
  throw new Error("Local deployment requires at least three funded accounts");
}

const outputPath = resolve(
  process.env.DEX_DEPLOYMENT_OUTPUT ?? "deployments/localhost.json",
);
const environmentPath = resolve(
  process.env.DEX_DEPLOYMENT_ENV_OUTPUT ?? "deployments/localhost.env",
);
const rpcUrl = process.env.DEX_RPC_URL ?? "http://127.0.0.1:8545";
const tokenDecimals = 18;
const baseLotSizeAtoms = ethers.parseUnits("1", tokenDecimals);
const executionPriceQuoteAtomsPerLot = ethers.parseUnits("2", tokenDecimals);
const initialSupply = ethers.parseUnits("1000000", tokenDecimals);

const Token = await ethers.getContractFactory("MockToken");
const tokenA = await Token.deploy("Token A", "TKA", initialSupply);
await tokenA.waitForDeployment();
const tokenB = await Token.deploy("Token B", "TKB", initialSupply);
await tokenB.waitForDeployment();

const TokenRegistry = await ethers.getContractFactory("TokenRegistry");
const tokenRegistry = await TokenRegistry.deploy();
await tokenRegistry.waitForDeployment();
await (await tokenRegistry.registerToken(await tokenA.getAddress())).wait();
await (await tokenRegistry.registerToken(await tokenB.getAddress())).wait();

const MarketRegistry = await ethers.getContractFactory("MarketRegistry");
const marketRegistry = await MarketRegistry.deploy(
  await tokenRegistry.getAddress(),
);
await marketRegistry.waitForDeployment();

// Deploy OrderBook before registering the market. The backend starts scanning
// at this deployment block, so every reconstructive market/order event follows
// the configured start position.
const OrderBook = await ethers.getContractFactory("OrderBook");
const orderBook = await OrderBook.deploy(await marketRegistry.getAddress());
await orderBook.waitForDeployment();
const orderBookDeployment = orderBook.deploymentTransaction();
if (orderBookDeployment === null) {
  throw new Error("OrderBook deployment transaction is unavailable");
}
const orderBookReceipt = await orderBookDeployment.wait();
if (orderBookReceipt === null) {
  throw new Error("OrderBook deployment receipt is unavailable");
}
const deploymentBlock = await ethers.provider.getBlock(
  orderBookReceipt.blockNumber,
);
if (deploymentBlock === null || deploymentBlock.hash === null) {
  throw new Error("OrderBook deployment block is unavailable");
}

const marketTransaction = await marketRegistry.registerMarket(
  await tokenA.getAddress(),
  await tokenB.getAddress(),
  baseLotSizeAtoms,
);
const marketReceipt = await marketTransaction.wait();
if (marketReceipt === null) {
  throw new Error("Market registration receipt is unavailable");
}
const marketId = await marketRegistry.getMarketId(
  await tokenA.getAddress(),
  await tokenB.getAddress(),
);

const aliceBaseAmount = baseLotSizeAtoms * 100n;
const bobQuoteAmount = executionPriceQuoteAtomsPerLot * 40n;
await (await tokenA.transfer(alice.address, aliceBaseAmount)).wait();
await (await tokenB.transfer(bob.address, bobQuoteAmount)).wait();
await (
  await tokenA.connect(alice).approve(
    await orderBook.getAddress(),
    aliceBaseAmount,
  )
).wait();
await (
  await tokenB.connect(bob).approve(
    await orderBook.getAddress(),
    bobQuoteAmount,
  )
).wait();

const sellOrderId = await orderBook.nextOrderId();
await (
  await orderBook.connect(alice).placeOrder(
    marketId,
    1,
    100n,
    executionPriceQuoteAtomsPerLot,
  )
).wait();
const buyOrderId = await orderBook.nextOrderId();
await (
  await orderBook.connect(bob).placeOrder(
    marketId,
    0,
    40n,
    executionPriceQuoteAtomsPerLot,
  )
).wait();

// This key exists only in the ignored local environment file. It is randomly
// generated for each deployment and funded solely on the disposable local node.
const executor = ethers.Wallet.createRandom();
await (
  await deployer.sendTransaction({
    to: executor.address,
    value: ethers.parseEther("1"),
  })
).wait();

const networkInfo = await ethers.provider.getNetwork();
const chainId = networkInfo.chainId;
const deploymentBlockHash = deploymentBlock.hash.toLowerCase();
const deploymentId = `hardhat-${chainId}-${deploymentBlockHash.slice(2, 18)}`;

const metadata = {
  schemaVersion: 1,
  deploymentId,
  network: networkName,
  rpcUrl,
  chainId: chainId.toString(),
  deploymentBlock: {
    number: orderBookReceipt.blockNumber.toString(),
    hash: deploymentBlockHash,
  },
  contracts: {
    tokenA: (await tokenA.getAddress()).toLowerCase(),
    tokenB: (await tokenB.getAddress()).toLowerCase(),
    tokenRegistry: (await tokenRegistry.getAddress()).toLowerCase(),
    marketRegistry: (await marketRegistry.getAddress()).toLowerCase(),
    orderBook: (await orderBook.getAddress()).toLowerCase(),
  },
  transactions: {
    orderBookDeployment: orderBookReceipt.hash.toLowerCase(),
    marketRegistration: marketReceipt.hash.toLowerCase(),
  },
  fixture: {
    marketId: marketId.toString(),
    sellOrderId: sellOrderId.toString(),
    buyOrderId: buyOrderId.toString(),
    alice: alice.address.toLowerCase(),
    bob: bob.address.toLowerCase(),
    executor: executor.address.toLowerCase(),
    baseLotSizeAtoms: baseLotSizeAtoms.toString(),
    executionPriceQuoteAtomsPerLot:
      executionPriceQuoteAtomsPerLot.toString(),
    sellLots: "100",
    buyLots: "40",
  },
};

const environment = [
  "# Generated local-only configuration. Do not commit this file.",
  "DEX_BLOCKCHAIN_MODE=ETHEREUM",
  `DEX_DEPLOYMENT_ID=${deploymentId}`,
  `DEX_RPC_URL=${rpcUrl}`,
  `DEX_EXPECTED_CHAIN_ID=${chainId}`,
  `DEX_CONTRACT_ADDRESS=${metadata.contracts.orderBook}`,
  `DEX_MARKET_REGISTRY_ADDRESS=${metadata.contracts.marketRegistry}`,
  `DEX_DEPLOYMENT_BLOCK=${orderBookReceipt.blockNumber}`,
  `DEX_EXECUTOR_PRIVATE_KEY=${executor.privateKey}`,
  "",
].join("\n");

await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(metadata, null, 2)}\n`, "utf8");
await writeFile(environmentPath, environment, {
  encoding: "utf8",
  mode: 0o600,
});
await chmod(environmentPath, 0o600);

console.log(`Deployment ID: ${deploymentId}`);
console.log(`Chain ID: ${chainId}`);
console.log(`OrderBook: ${metadata.contracts.orderBook}`);
console.log(`MarketRegistry: ${metadata.contracts.marketRegistry}`);
console.log(`Deployment block: ${orderBookReceipt.blockNumber}`);
console.log(`Metadata: ${outputPath}`);
console.log(`Private local environment: ${environmentPath}`);
