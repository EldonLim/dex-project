import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { network } from "hardhat";

interface DeploymentMetadata {
  contracts: {
    tokenA: string;
    tokenB: string;
    orderBook: string;
  };
  fixture: {
    alice: string;
    bob: string;
    executor: string;
    sellOrderId: string;
    buyOrderId: string;
    baseLotSizeAtoms: string;
    executionPriceQuoteAtomsPerLot: string;
  };
}

const metadataPath = resolve(
  process.env.DEX_DEPLOYMENT_OUTPUT ?? "deployments/localhost.json",
);
const metadata = JSON.parse(
  await readFile(metadataPath, "utf8"),
) as DeploymentMetadata;
const { ethers } = await network.getOrCreate();
const orderBook = await ethers.getContractAt(
  "OrderBook",
  metadata.contracts.orderBook,
);
const tokenA = await ethers.getContractAt(
  "MockToken",
  metadata.contracts.tokenA,
);
const tokenB = await ethers.getContractAt(
  "MockToken",
  metadata.contracts.tokenB,
);

const sellOrder = await orderBook.getOrder(metadata.fixture.sellOrderId);
const buyOrder = await orderBook.getOrder(metadata.fixture.buyOrderId);
const baseLotSize = BigInt(metadata.fixture.baseLotSizeAtoms);
const executionPrice = BigInt(
  metadata.fixture.executionPriceQuoteAtomsPerLot,
);

const checks: Array<[string, boolean]> = [
  ["Alice sell remainder is 60 lots", sellOrder.remainingLots === 60n],
  ["Alice sell order remains open", sellOrder.status === 0n],
  ["Bob buy remainder is zero", buyOrder.remainingLots === 0n],
  ["Bob buy order is filled", buyOrder.status === 1n],
  [
    "Alice retains 60 base lots",
    (await tokenA.balanceOf(metadata.fixture.alice)) === baseLotSize * 60n,
  ],
  [
    "Alice receives 40 lots of quote value",
    (await tokenB.balanceOf(metadata.fixture.alice)) === executionPrice * 40n,
  ],
  [
    "Bob receives 40 base lots",
    (await tokenA.balanceOf(metadata.fixture.bob)) === baseLotSize * 40n,
  ],
  [
    "Bob spends the funded quote balance",
    (await tokenB.balanceOf(metadata.fixture.bob)) === 0n,
  ],
  [
    "Executor submitted exactly one transaction",
    (await ethers.provider.getTransactionCount(
      metadata.fixture.executor,
      "pending",
    )) === 1,
  ],
];

const failures = checks.filter(([, passed]) => !passed);
if (failures.length > 0) {
  for (const [description] of failures) {
    console.error(`FAILED: ${description}`);
  }
  process.exitCode = 1;
} else {
  console.log("On-chain Alice-100/Bob-40 settlement checks passed");
}
