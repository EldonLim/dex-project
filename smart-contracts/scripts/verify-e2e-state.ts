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
    executor: string;
    sellOrderId: string;
    buyers: Array<{
      name: string;
      address: string;
      buyOrderId: string;
      buyLots: string;
    }>;
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
const buyOrders = await Promise.all(
  metadata.fixture.buyers.map(async (buyer) => ({
    buyer,
    order: await orderBook.getOrder(buyer.buyOrderId),
  })),
);
const baseLotSize = BigInt(metadata.fixture.baseLotSizeAtoms);
const executionPrice = BigInt(
  metadata.fixture.executionPriceQuoteAtomsPerLot,
);

const checks: Array<[string, boolean]> = [
  ["Alice sell remainder is 10 lots", sellOrder.remainingLots === 10n],
  ["Alice sell order remains open", sellOrder.status === 0n],
  [
    "Alice retains 10 base lots",
    (await tokenA.balanceOf(metadata.fixture.alice)) === baseLotSize * 10n,
  ],
  [
    "Alice receives 90 lots of quote value",
    (await tokenB.balanceOf(metadata.fixture.alice)) === executionPrice * 90n,
  ],
  [
    "Executor submitted exactly one transaction",
    (await ethers.provider.getTransactionCount(
      metadata.fixture.executor,
      "pending",
    )) === 1,
  ],
];

for (const { buyer, order } of buyOrders) {
  const buyLots = BigInt(buyer.buyLots);
  checks.push(
    [`${buyer.name} buy remainder is zero`, order.remainingLots === 0n],
    [`${buyer.name} buy order is filled`, order.status === 1n],
    [
      `${buyer.name} receives ${buyer.buyLots} base lots`,
      (await tokenA.balanceOf(buyer.address)) === baseLotSize * buyLots,
    ],
    [
      `${buyer.name} spends the funded quote balance`,
      (await tokenB.balanceOf(buyer.address)) === 0n,
    ],
  );
}

const failures = checks.filter(([, passed]) => !passed);
if (failures.length > 0) {
  for (const [description] of failures) {
    console.error(`FAILED: ${description}`);
  }
  process.exitCode = 1;
} else {
  console.log(
    "On-chain Alice-100/Bob-40/Carol-30/Dave-20 batch settlement checks passed",
  );
}
