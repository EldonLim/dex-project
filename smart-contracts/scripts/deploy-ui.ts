import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { network } from "hardhat";

const deploymentPath = fileURLToPath(new URL("../deployments/localhost.json", import.meta.url));
const environmentPath = fileURLToPath(new URL("../deployments/localhost.env", import.meta.url));
const commandEnvironmentPath = fileURLToPath(new URL("../deployments/localhost.cmd", import.meta.url));
const frontendEnvironmentPath = fileURLToPath(new URL("../../frontend/.env.local", import.meta.url));
const rootEnvironmentPath = fileURLToPath(new URL("../../.env", import.meta.url));

async function localDatabaseVariables(): Promise<Record<string, string>> {
  let source = "";
  try {
    source = await readFile(rootEnvironmentPath, "utf8");
  } catch {
    // START_HERE creates this file. Defaults keep direct script use predictable.
  }
  const parsed = new Map<string, string>();
  for (const line of source.split(/\r?\n/)) {
    const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line.trim());
    if (match) parsed.set(match[1], match[2]);
  }
  const databaseName = parsed.get("DATABASE_NAME") ?? "dex";
  const databasePort = parsed.get("DATABASE_PORT") ?? "5432";
  return {
    DATABASE_NAME: databaseName,
    DATABASE_PORT: databasePort,
    SPRING_DATASOURCE_URL: parsed.get("SPRING_DATASOURCE_URL")
      ?? `jdbc:postgresql://localhost:${databasePort}/${databaseName}`,
    SPRING_DATASOURCE_USERNAME: parsed.get("SPRING_DATASOURCE_USERNAME") ?? "dex",
    SPRING_DATASOURCE_PASSWORD: parsed.get("SPRING_DATASOURCE_PASSWORD") ?? "dex-local-only",
  };
}

async function main() {
  const { ethers } = await network.connect();
  const [deployer, traderOne, traderTwo] = await ethers.getSigners();
  if (!deployer || !traderOne || !traderTwo) {
    throw new Error("The interactive deployment requires three funded Hardhat accounts");
  }

  const Token = await ethers.getContractFactory("MockToken");
  const tokenA = await Token.deploy("Token A", "TKA", ethers.parseUnits("1000000", 18));
  await tokenA.waitForDeployment();
  const tokenB = await Token.deploy("Token B", "TKB", ethers.parseUnits("1000000", 18));
  await tokenB.waitForDeployment();

  const TokenRegistry = await ethers.getContractFactory("TokenRegistry");
  const tokenRegistry = await TokenRegistry.deploy();
  await tokenRegistry.waitForDeployment();
  await (await tokenRegistry.registerToken(await tokenA.getAddress())).wait();
  await (await tokenRegistry.registerToken(await tokenB.getAddress())).wait();

  const MarketRegistry = await ethers.getContractFactory("MarketRegistry");
  const marketRegistry = await MarketRegistry.deploy(await tokenRegistry.getAddress());
  await marketRegistry.waitForDeployment();

  // The backend starts at the OrderBook deployment block, so reconstructive
  // market events must be emitted after this block.
  const OrderBook = await ethers.getContractFactory("OrderBook");
  const orderBook = await OrderBook.deploy(await marketRegistry.getAddress());
  await orderBook.waitForDeployment();
  const orderBookReceipt = await orderBook.deploymentTransaction()?.wait();
  if (!orderBookReceipt) throw new Error("OrderBook deployment receipt is unavailable");
  const deploymentBlock = await ethers.provider.getBlock(orderBookReceipt.blockNumber);
  if (!deploymentBlock?.hash) throw new Error("OrderBook deployment block is unavailable");

  const baseLotSizeAtoms = ethers.parseUnits("1", 18);
  const marketTransaction = await marketRegistry.registerMarket(
    await tokenA.getAddress(),
    await tokenB.getAddress(),
    baseLotSizeAtoms,
  );
  const marketReceipt = await marketTransaction.wait();
  if (!marketReceipt) throw new Error("Market registration receipt is unavailable");
  const marketId = await marketRegistry.getMarketId(await tokenA.getAddress(), await tokenB.getAddress());

  const testAllocation = ethers.parseUnits("10000", 18);
  for (const trader of [traderOne, traderTwo]) {
    await (await tokenA.transfer(trader.address, testAllocation)).wait();
    await (await tokenB.transfer(trader.address, testAllocation)).wait();
  }

  // This disposable key is written only to ignored local configuration and is
  // used solely by the Java settlement executor on this Hardhat chain.
  const executor = ethers.Wallet.createRandom();
  await (await deployer.sendTransaction({ to: executor.address, value: ethers.parseEther("1") })).wait();

  const networkInfo = await ethers.provider.getNetwork();
  const chainId = networkInfo.chainId;
  const blockHash = deploymentBlock.hash.toLowerCase();
  const deploymentId = `hardhat-${chainId}-${blockHash.slice(2, 18)}`;
  const contracts = {
    MockTokenA: await tokenA.getAddress(),
    MockTokenB: await tokenB.getAddress(),
    TokenRegistry: await tokenRegistry.getAddress(),
    MarketRegistry: await marketRegistry.getAddress(),
    OrderBook: await orderBook.getAddress(),
  };

  const deployment = {
    schemaVersion: 1,
    deploymentId,
    network: "localhost",
    rpcUrl: "http://127.0.0.1:8545",
    chainId: chainId.toString(),
    deploymentBlock: {
      number: orderBookReceipt.blockNumber.toString(),
      hash: blockHash,
    },
    contracts,
    transactions: {
      orderBookDeployment: orderBookReceipt.hash.toLowerCase(),
      marketRegistration: marketReceipt.hash.toLowerCase(),
    },
    fixture: {
      marketId: marketId.toString(),
      traderOne: traderOne.address.toLowerCase(),
      traderTwo: traderTwo.address.toLowerCase(),
      executor: executor.address.toLowerCase(),
      baseLotSizeAtoms: baseLotSizeAtoms.toString(),
      tokenAllocationAtoms: testAllocation.toString(),
    },
  };

  const databaseVariables = await localDatabaseVariables();
  const backendVariables: Record<string, string> = {
    DEX_BLOCKCHAIN_MODE: "ETHEREUM",
    DEX_DEPLOYMENT_ID: deploymentId,
    DEX_RPC_URL: "http://127.0.0.1:8545",
    DEX_EXPECTED_CHAIN_ID: chainId.toString(),
    DEX_CONTRACT_ADDRESS: contracts.OrderBook.toLowerCase(),
    DEX_MARKET_REGISTRY_ADDRESS: contracts.MarketRegistry.toLowerCase(),
    DEX_DEPLOYMENT_BLOCK: orderBookReceipt.blockNumber.toString(),
    DEX_EXECUTOR_PRIVATE_KEY: executor.privateKey,
    DEX_INDEXER_ENABLED: "true",
    DEX_MATCHER_ENABLED: "true",
    DEX_CORS_ORIGINS: "http://localhost:5173",
    ...databaseVariables,
  };
  const unixEnvironment = [
    "# Generated local-only configuration. Do not commit this file.",
    ...Object.entries(backendVariables).map(([key, value]) => `${key}=${value}`),
    "",
  ].join("\n");
  const commandEnvironment = [
    "@echo off",
    "rem Generated local-only configuration. Do not commit this file.",
    ...Object.entries(backendVariables).map(([key, value]) => `set \"${key}=${value}\"`),
    "",
  ].join("\r\n");
  const frontendEnvironment = [
    "# Generated public local-deployment configuration. Do not commit this file.",
    `VITE_DEPLOYMENT_ID=${deploymentId}`,
    `VITE_CHAIN_ID=${chainId}`,
    "VITE_CHAIN_NAME=Hardhat Local",
    "VITE_RPC_URL=http://127.0.0.1:8545",
    "VITE_BLOCK_EXPLORER_URL=",
    `VITE_DEPLOYMENT_BLOCK=${orderBookReceipt.blockNumber}`,
    `VITE_TOKEN_REGISTRY_ADDRESS=${contracts.TokenRegistry}`,
    `VITE_MARKET_REGISTRY_ADDRESS=${contracts.MarketRegistry}`,
    `VITE_ORDER_BOOK_ADDRESS=${contracts.OrderBook}`,
    "VITE_BACKEND_URL=http://127.0.0.1:8080/api/v1",
    "",
  ].join("\n");

  await mkdir(dirname(deploymentPath), { recursive: true });
  await writeFile(deploymentPath, `${JSON.stringify(deployment, null, 2)}\n`, "utf8");
  await writeFile(environmentPath, unixEnvironment, { encoding: "utf8", mode: 0o600 });
  await chmod(environmentPath, 0o600);
  await writeFile(commandEnvironmentPath, commandEnvironment, "utf8");
  await writeFile(frontendEnvironmentPath, frontendEnvironment, "utf8");

  console.log("Deployment ID:", deploymentId);
  console.log("Chain ID:", chainId.toString());
  console.log("Token A:", contracts.MockTokenA);
  console.log("Token B:", contracts.MockTokenB);
  console.log("TokenRegistry:", contracts.TokenRegistry);
  console.log("MarketRegistry:", contracts.MarketRegistry);
  console.log("OrderBook:", contracts.OrderBook);
  console.log("Deployment block:", orderBookReceipt.blockNumber);
  console.log("Registered Market ID:", marketId.toString());
  console.log("Funded local traders:", traderOne.address, traderTwo.address);
  console.log("Java executor:", executor.address);
  console.log("Backend environment:", environmentPath);
  console.log("Windows backend environment:", commandEnvironmentPath);
  console.log("Frontend environment:", frontendEnvironmentPath);
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
