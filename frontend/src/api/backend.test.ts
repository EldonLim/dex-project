import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendApiClient, BackendApiError, deploymentIdentityError } from "./backend";
import type { Market } from "../types";

const baseUrl = "http://127.0.0.1:8080/api/v1";
const address = (digit: string) => `0x${digit.repeat(40)}`;
const hash = (digit: string) => `0x${digit.repeat(64)}`;

function respond(body: unknown, status = 200) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" },
  })));
}

const marketPayload = {
  marketId: "900719925474099312345",
  baseToken: { address: address("1"), symbol: "TKA", decimals: 18 },
  quoteToken: { address: address("2"), symbol: "TKB", decimals: 6 },
  baseLotSizeAtoms: "1000000000000000000",
  quantityUnit: "LOTS",
  priceUnit: "QUOTE_ATOMS_PER_LOT",
  active: true,
};

const market: Market = {
  id: BigInt(marketPayload.marketId),
  baseToken: marketPayload.baseToken,
  quoteToken: marketPayload.quoteToken,
  baseLotAtoms: BigInt(marketPayload.baseLotSizeAtoms),
  active: true,
};

afterEach(() => vi.unstubAllGlobals());

describe("BackendApiClient", () => {
  it("maps markets and exact integers beyond JavaScript safe precision", async () => {
    respond({ items: [marketPayload] });
    const result = await new BackendApiClient(baseUrl).markets();
    expect(result).toEqual([market]);
    expect(result[0]?.id).toBe(900719925474099312345n);
  });

  it("maps the compact bid and ask order-book contract", async () => {
    respond({
      marketId: marketPayload.marketId,
      limit: 200,
      bids: {
        items: [{
          orderId: "900719925474099399999",
          side: "BUY",
          limitPriceQuoteAtomsPerLot: "2500000000000000000",
          remainingLots: "40",
          creationPosition: { blockNumber: "12", transactionIndex: "0", logIndex: "1" },
        }],
        hasMore: true,
      },
      asks: { items: [], hasMore: false },
    });
    const result = await new BackendApiClient(baseUrl).orderBook(market.id);
    expect(result.bids[0]?.id).toBe(900719925474099399999n);
    expect(result.bids[0]?.limitPrice).toBe(2500000000000000000n);
    expect(result.bidsHaveMore).toBe(true);
    expect(result.asksHaveMore).toBe(false);
  });

  it("maps full partially-filled and cancelled wallet orders with cursors", async () => {
    const common = {
      marketId: marketPayload.marketId,
      owner: address("3"),
      side: "SELL",
      limitPriceQuoteAtomsPerLot: "2000000",
      originalLots: "100",
      filledLots: "40",
      remainingLots: "60",
      creationPosition: { blockNumber: "20", transactionIndex: "1", logIndex: "2" },
      creationTransactionHash: hash("a"),
      blockTimestamp: "2026-01-01T12:00:00Z",
    };
    respond({
      items: [
        { ...common, orderId: "10", status: "PARTIALLY_FILLED" },
        { ...common, orderId: "11", remainingLots: "0", status: "CANCELLED" },
      ],
      nextCursor: "opaque-cursor",
    });
    const result = await new BackendApiClient(baseUrl).walletOrders(address("3"), market.id);
    expect(result.items.map((item) => item.status)).toEqual(["PARTIALLY_FILLED", "CANCELLED"]);
    expect(result.items[0]?.creationPosition.blockNumber).toBe(20n);
    expect(result.nextCursor).toBe("opaque-cursor");
  });

  it("maps nullable and present trade IDs and derives exact token atoms", async () => {
    const common = {
      marketId: marketPayload.marketId,
      buyOrderId: "10",
      sellOrderId: "11",
      fillLots: "40",
      executionPriceQuoteAtomsPerLot: "2500000",
      buyOrderRemainingLots: "0",
      sellOrderRemainingLots: "60",
      executionPosition: { blockNumber: "20", transactionIndex: "1", logIndex: "3" },
      transactionHash: hash("b"),
      blockTimestamp: "2026-01-01T12:00:00Z",
    };
    respond({ items: [{ ...common, tradeId: null }, { ...common, tradeId: "99" }], nextCursor: null });
    const result = await new BackendApiClient(baseUrl).trades(market);
    expect(result.items[0]?.tradeId).toBeNull();
    expect(result.items[1]?.tradeId).toBe(99n);
    expect(result.items[0]?.baseAtoms).toBe(40n * market.baseLotAtoms);
    expect(result.items[0]?.quoteAtoms).toBe(100000000n);
  });

  it("maps status and detects any deployment identity mismatch", async () => {
    respond({
      status: "HEALTHY",
      blockchainMode: "ETHEREUM",
      deployment: {
        deploymentId: "hardhat-local",
        registered: true,
        chainId: "31337",
        dexAddress: address("4"),
        marketRegistryAddress: address("5"),
        deploymentBlockNumber: "1",
        deploymentBlockHash: hash("c"),
      },
      indexer: { state: "UP_TO_DATE", lastIndexedBlockNumber: "90071992547409930000" },
      matcher: { state: "IDLE" },
      executor: { state: "IDLE" },
    });
    const status = await new BackendApiClient(baseUrl).status();
    const expected = {
      deploymentId: "hardhat-local",
      chainId: 31337,
      dexAddress: address("4"),
      marketRegistryAddress: address("5"),
    };
    expect(status.indexedBlock).toBe(90071992547409930000n);
    expect(deploymentIdentityError(status, expected)).toBeUndefined();
    expect(deploymentIdentityError(status, { ...expected, dexAddress: address("6") })).toMatch(/OrderBook/);
  });

  it("keeps a degraded backend online while preserving its operational states", async () => {
    respond({
      status: "DEGRADED",
      blockchainMode: "ETHEREUM",
      deployment: {
        deploymentId: "hardhat-local",
        registered: true,
        chainId: "31337",
        dexAddress: address("4"),
        marketRegistryAddress: address("5"),
        deploymentBlockNumber: "1",
        deploymentBlockHash: hash("c"),
      },
      indexer: { state: "PAUSED", lastIndexedBlockNumber: null },
      matcher: { state: "HALTED" },
      executor: { state: "IDLE" },
    });

    const status = await new BackendApiClient(baseUrl).status();
    expect(status).toMatchObject({
      online: true,
      operationalStatus: "DEGRADED",
      indexerState: "PAUSED",
      matcherState: "HALTED",
      executorState: "IDLE",
    });
    expect(status.indexedBlock).toBeUndefined();
  });

  it("returns sanitized Spring Problem Details as a typed client error", async () => {
    respond({
      type: "urn:dex:problem:invalid-request",
      title: "Invalid request",
      status: 400,
      detail: "The request parameters are invalid.",
      instance: "/api/v1/orders",
    }, 400);
    const error = await new BackendApiClient(baseUrl).markets().catch((candidate: unknown) => candidate);
    expect(error).toBeInstanceOf(BackendApiError);
    expect(error).toMatchObject({
      message: "The request parameters are invalid.",
      status: 400,
      problemType: "urn:dex:problem:invalid-request",
    });
  });

  it("rejects malformed decimal strings instead of losing precision", async () => {
    respond({ items: [{ ...marketPayload, marketId: "1.5" }] });
    await expect(new BackendApiClient(baseUrl).markets()).rejects.toThrow(/market ID/);
  });
});
