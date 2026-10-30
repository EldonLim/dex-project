import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { Contract } from "ethers";
import { loadConfig } from "./config.js";
import { erc20Abi, orderBookAbi } from "./abis.js";
import { LiveMatcher } from "./matcher.js";

const config = loadConfig();
const matcher = new LiveMatcher(config);

function json(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "access-control-allow-origin": "http://localhost:5173",
    "access-control-allow-methods": "GET,OPTIONS",
  });
  response.end(JSON.stringify(body, (_, value) => typeof value === "bigint" ? value.toString() : value));
}

async function tokenMeta(address: string) {
  const token = new Contract(address, erc20Abi, matcher.provider);
  const [name, symbol, decimals] = await Promise.all([token.name(), token.symbol(), token.decimals()]);
  return { address, name: String(name), symbol: String(symbol), decimals: Number(decimals) };
}

async function route(request: IncomingMessage, response: ServerResponse): Promise<void> {
  if (request.method === "OPTIONS") return json(response, 204, null);
  const url = new URL(request.url || "/", `http://${request.headers.host || "localhost"}`);

  if (url.pathname === "/health" || url.pathname === "/api/v1/status") {
    const state = matcher.snapshot();
    return json(response, state.lastError ? 503 : 200, {
      status: state.lastError ? "DEGRADED" : "HEALTHY",
      matcher: state.running ? (state.matching ? "MATCHING" : "RUNNING") : "STOPPED",
      chainId: state.chainId ?? config.chainId,
      indexedBlock: state.indexedBlock,
      stale: false,
      orderBook: config.orderBookAddress,
      ...state,
    });
  }

  if (url.pathname === "/api/v1/markets") {
    const nextMarketId = BigInt(await matcher.marketRegistry.nextMarketId());
    const markets = [];
    for (let id = 1n; id < nextMarketId; id += 1n) {
      const market = await matcher.marketRegistry.getMarket(id);
      markets.push({
        id,
        baseToken: await tokenMeta(String(market.baseToken)),
        quoteToken: await tokenMeta(String(market.quoteToken)),
        baseLotAtoms: BigInt(market.baseLotAtoms),
        active: Boolean(market.isRegistered),
      });
    }
    return json(response, 200, { items: markets });
  }

  const orderBookMatch = /^\/api\/v1\/markets\/(\d+)\/orderbook$/.exec(url.pathname);
  if (orderBookMatch) {
    const marketId = BigInt(orderBookMatch[1]);
    const orders = (await matcher.readOrders()).filter(
      (order) => order.marketId === marketId && order.status === 0 && order.remainingLots > 0n,
    );
    return json(response, 200, {
      marketId,
      bids: orders.filter((order) => order.side === 0),
      asks: orders.filter((order) => order.side === 1),
    });
  }

  if (url.pathname === "/api/v1/orders") {
    const owner = url.searchParams.get("owner")?.toLowerCase();
    const orders = (await matcher.readOrders()).filter((order) => !owner || order.owner.toLowerCase() === owner);
    return json(response, 200, { items: orders });
  }

  if (url.pathname === "/api/v1/trades") {
    const orderBook = new Contract(config.orderBookAddress, orderBookAbi, matcher.provider);
    const marketId = url.searchParams.get("marketId");
    const filter = marketId
      ? orderBook.filters.TradeExecuted(null, null, BigInt(marketId))
      : orderBook.filters.TradeExecuted();
    const events = await orderBook.queryFilter(filter, config.deploymentBlock, "latest");
    return json(response, 200, { items: events.map((event: any) => ({
      transactionHash: event.transactionHash,
      blockNumber: event.blockNumber,
      buyOrderId: event.args.buyOrderId,
      sellOrderId: event.args.sellOrderId,
      marketId: event.args.marketId,
      buyer: event.args.buyer,
      seller: event.args.seller,
      fillLots: event.args.fillLots,
      executionPrice: event.args.executionPrice,
      baseAtoms: event.args.baseAtoms,
      quoteAtoms: event.args.quoteAtoms,
    })) });
  }

  return json(response, 404, { error: "Not found" });
}

const server = createServer((request, response) => {
  void route(request, response).catch((error) => {
    console.error(error);
    json(response, 500, { error: error instanceof Error ? error.message : String(error) });
  });
});

matcher.start();
server.listen(config.port, "127.0.0.1", () => {
  console.log(`Live matcher listening at http://127.0.0.1:${config.port}`);
  console.log(`OrderBook: ${config.orderBookAddress}`);
});

function shutdown(): void {
  matcher.stop();
  server.close(() => process.exit(0));
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
