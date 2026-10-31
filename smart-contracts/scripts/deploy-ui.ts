import { mkdir, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { network } from "hardhat";

async function main() {
  const { ethers } = await network.connect();
  const [deployer, traderOne, traderTwo] = await ethers.getSigners();

  console.log("Deployer:", deployer.address);

  const Token = await ethers.getContractFactory("MockToken");
  const tokenA = await Token.deploy(
    "Token A",
    "TKA",
    ethers.parseUnits("1000000", 18),
  );
  await tokenA.waitForDeployment();

  const tokenB = await Token.deploy(
    "Token B",
    "TKB",
    ethers.parseUnits("1000000", 18),
  );
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

  const marketTransaction = await marketRegistry.registerMarket(
    await tokenA.getAddress(),
    await tokenB.getAddress(),
    ethers.parseUnits("1", 18),
  );
  await marketTransaction.wait();

  const OrderBook = await ethers.getContractFactory("OrderBook");
  const orderBook = await OrderBook.deploy(await marketRegistry.getAddress());
  await orderBook.waitForDeployment();

  const networkInfo = await ethers.provider.getNetwork();
  const testAllocation = ethers.parseUnits("10000", 18);
  for (const trader of [traderOne, traderTwo]) {
    await (await tokenA.transfer(trader.address, testAllocation)).wait();
    await (await tokenB.transfer(trader.address, testAllocation)).wait();
  }

  const deploymentReceipt = await tokenA.deploymentTransaction()?.wait();
  const deployment = {
    network: "localhost",
    chainId: Number(networkInfo.chainId),
    deploymentBlock: deploymentReceipt?.blockNumber ?? 0,
    contracts: {
      MockTokenA: await tokenA.getAddress(),
      MockTokenB: await tokenB.getAddress(),
      TokenRegistry: await tokenRegistry.getAddress(),
      MarketRegistry: await marketRegistry.getAddress(),
      OrderBook: await orderBook.getAddress(),
    },
  };
  const deploymentPath = fileURLToPath(
    new URL("../deployments/localhost.json", import.meta.url),
  );
  await mkdir(dirname(deploymentPath), { recursive: true });
  await writeFile(
    deploymentPath,
    `${JSON.stringify(deployment, null, 2)}\n`,
    "utf8",
  );

  console.log("Network name:", networkInfo.name);
  console.log("Chain ID:", networkInfo.chainId.toString());
  console.log("Token A:", await tokenA.getAddress());
  console.log("Token B:", await tokenB.getAddress());
  console.log("TokenRegistry:", await tokenRegistry.getAddress());
  console.log("MarketRegistry:", await marketRegistry.getAddress());
  console.log("OrderBook:", await orderBook.getAddress());
  console.log("Deployment metadata:", deploymentPath);
  console.log("Funded local traders:", traderOne.address, traderTwo.address);
  console.log(
    "Registered Market ID:",
    await marketRegistry.getMarketId(
      await tokenA.getAddress(),
      await tokenB.getAddress(),
    ),
  );
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
