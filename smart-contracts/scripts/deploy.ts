import { network } from "hardhat";

async function main() {
  const { ethers } = await network.connect();

  const [deployer] = await ethers.getSigners();

  console.log("Deployer:", deployer.address);

  // 1. Deploy two test tokens.
  const Token = await ethers.getContractFactory("MockToken");

  const tokenA = await Token.deploy(
    "Token A",
    "TKA",
    ethers.parseUnits("1000000", 18)
  );
  await tokenA.waitForDeployment();

  const tokenB = await Token.deploy(
    "Token B",
    "TKB",
    ethers.parseUnits("1000000", 18)
  );
  await tokenB.waitForDeployment();

  // 2. Deploy TokenRegistry.
  const TokenRegistry = await ethers.getContractFactory(
    "TokenRegistry"
  );

  const tokenRegistry = await TokenRegistry.deploy();
  await tokenRegistry.waitForDeployment();

  // 3. Register both tokens.
  await (
    await tokenRegistry.registerToken(
      await tokenA.getAddress()
    )
  ).wait();

  await (
    await tokenRegistry.registerToken(
      await tokenB.getAddress()
    )
  ).wait();

  // 4. Deploy MarketRegistry.
  const MarketRegistry = await ethers.getContractFactory(
    "MarketRegistry"
  );

  const marketRegistry = await MarketRegistry.deploy(
    await tokenRegistry.getAddress()
  );
  await marketRegistry.waitForDeployment();

  // 5. Register TOKEN_A/TOKEN_B.
  // Example: 1 whole TOKEN_A per lot, assuming 18 decimals.
  const tx = await marketRegistry.registerMarket(
    await tokenA.getAddress(),
    await tokenB.getAddress(),
    ethers.parseUnits("1", 18)
  );
  await tx.wait();

  // 6. Deploy OrderBook.
  const OrderBook = await ethers.getContractFactory("OrderBook");

  const orderBook = await OrderBook.deploy(
    await marketRegistry.getAddress()
  );
  await orderBook.waitForDeployment();

  const networkInfo = await ethers.provider.getNetwork();

  // 7. Print deployment information.
  console.log("Network name:", networkInfo.name);
  console.log("Chain ID:", networkInfo.chainId.toString());
  console.log("Token A:", await tokenA.getAddress());
  console.log("Token B:", await tokenB.getAddress());
  console.log("TokenRegistry:", await tokenRegistry.getAddress());
  console.log("MarketRegistry:", await marketRegistry.getAddress());
  console.log("OrderBook:", await orderBook.getAddress());
  

  console.log(
  "Registered Market ID:",
  await marketRegistry.getMarketId(
    await tokenA.getAddress(),
    await tokenB.getAddress()
  )
);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});