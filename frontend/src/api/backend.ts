import type {
  BackendHealth,
  ChainPosition,
  CursorPage,
  Market,
  Order,
  OrderBookOrder,
  OrderStatus,
  Side,
  Trade,
} from "../types";

type JsonObject = Record<string, unknown>;

export class BackendApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly problemType?: string,
  ) {
    super(message);
    this.name = "BackendApiError";
  }
}

function object(value: unknown, label: string): JsonObject {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new BackendApiError(`Backend returned an invalid ${label}.`);
  }
  return value as JsonObject;
}

function string(value: unknown, label: string): string {
  if (typeof value !== "string") throw new BackendApiError(`Backend returned an invalid ${label}.`);
  return value;
}

function nullableString(value: unknown, label: string): string | null {
  return value === null ? null : string(value, label);
}

function boolean(value: unknown, label: string): boolean {
  if (typeof value !== "boolean") throw new BackendApiError(`Backend returned an invalid ${label}.`);
  return value;
}

function number(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isInteger(value)) {
    throw new BackendApiError(`Backend returned an invalid ${label}.`);
  }
  return value;
}

function decimal(value: unknown, label: string): bigint {
  const raw = string(value, label);
  if (!/^(0|[1-9][0-9]*)$/.test(raw)) throw new BackendApiError(`Backend returned an invalid ${label}.`);
  return BigInt(raw);
}

function enumValue<T extends string>(value: unknown, allowed: readonly T[], label: string): T {
  const raw = string(value, label);
  if (!allowed.includes(raw as T)) throw new BackendApiError(`Backend returned an invalid ${label}.`);
  return raw as T;
}

function items(value: unknown, label: string): unknown[] {
  if (!Array.isArray(value)) throw new BackendApiError(`Backend returned an invalid ${label}.`);
  return value;
}

function position(value: unknown, label: string): ChainPosition {
  const raw = object(value, label);
  return {
    blockNumber: decimal(raw.blockNumber, `${label} block number`),
    transactionIndex: decimal(raw.transactionIndex, `${label} transaction index`),
    logIndex: decimal(raw.logIndex, `${label} log index`),
  };
}

function mapMarket(value: unknown): Market {
  const raw = object(value, "market");
  const base = object(raw.baseToken, "base token");
  const quote = object(raw.quoteToken, "quote token");
  return {
    id: decimal(raw.marketId, "market ID"),
    baseToken: {
      address: string(base.address, "base token address"),
      symbol: string(base.symbol, "base token symbol"),
      decimals: number(base.decimals, "base token decimals"),
    },
    quoteToken: {
      address: string(quote.address, "quote token address"),
      symbol: string(quote.symbol, "quote token symbol"),
      decimals: number(quote.decimals, "quote token decimals"),
    },
    baseLotAtoms: decimal(raw.baseLotSizeAtoms, "base lot size"),
    active: boolean(raw.active, "market active flag"),
  };
}

function mapBookOrder(value: unknown): OrderBookOrder {
  const raw = object(value, "order-book order");
  return {
    id: decimal(raw.orderId, "order ID"),
    side: enumValue<Side>(raw.side, ["BUY", "SELL"], "order side"),
    limitPrice: decimal(raw.limitPriceQuoteAtomsPerLot, "limit price"),
    remainingLots: decimal(raw.remainingLots, "remaining lots"),
    creationPosition: position(raw.creationPosition, "creation position"),
  };
}

function mapOrder(value: unknown): Order {
  const raw = object(value, "order");
  return {
    id: decimal(raw.orderId, "order ID"),
    marketId: decimal(raw.marketId, "order market ID"),
    owner: string(raw.owner, "order owner"),
    side: enumValue<Side>(raw.side, ["BUY", "SELL"], "order side"),
    limitPrice: decimal(raw.limitPriceQuoteAtomsPerLot, "limit price"),
    originalLots: decimal(raw.originalLots, "original lots"),
    remainingLots: decimal(raw.remainingLots, "remaining lots"),
    status: enumValue<OrderStatus>(
      raw.status,
      ["OPEN", "PARTIALLY_FILLED", "FILLED", "CANCELLED"],
      "order status",
    ),
    creationPosition: position(raw.creationPosition, "creation position"),
    creationTransactionHash: string(raw.creationTransactionHash, "creation transaction hash"),
    blockTimestamp: string(raw.blockTimestamp, "order block timestamp"),
  };
}

function mapTrade(value: unknown, market: Market): Trade {
  const raw = object(value, "trade");
  const executionPosition = position(raw.executionPosition, "execution position");
  const fillLots = decimal(raw.fillLots, "fill lots");
  const executionPrice = decimal(raw.executionPriceQuoteAtomsPerLot, "execution price");
  return {
    tradeId: raw.tradeId === null ? null : decimal(raw.tradeId, "trade ID"),
    marketId: decimal(raw.marketId, "trade market ID"),
    buyOrderId: decimal(raw.buyOrderId, "buy order ID"),
    sellOrderId: decimal(raw.sellOrderId, "sell order ID"),
    fillLots,
    executionPrice,
    baseAtoms: fillLots * market.baseLotAtoms,
    quoteAtoms: fillLots * executionPrice,
    blockNumber: executionPosition.blockNumber,
    logIndex: executionPosition.logIndex,
    transactionHash: string(raw.transactionHash, "transaction hash"),
    blockTimestamp: string(raw.blockTimestamp, "trade block timestamp"),
  };
}

function mapCursorPage<T>(value: unknown, mapper: (item: unknown) => T, label: string): CursorPage<T> {
  const raw = object(value, label);
  return {
    items: items(raw.items, `${label} items`).map(mapper),
    nextCursor: nullableString(raw.nextCursor, `${label} cursor`),
  };
}

export interface OrderBookSnapshot {
  marketId: bigint;
  limit: number;
  bids: OrderBookOrder[];
  asks: OrderBookOrder[];
  bidsHaveMore: boolean;
  asksHaveMore: boolean;
}

export interface ExpectedDeployment {
  deploymentId: string;
  chainId: number;
  dexAddress: string;
  marketRegistryAddress: string;
}

export function deploymentIdentityError(
  status: BackendHealth,
  expected: ExpectedDeployment,
): string | undefined {
  if (!status.online) return undefined;
  if (!status.registered) return "The backend has not registered its configured deployment yet.";
  if (expected.deploymentId && status.deploymentId !== expected.deploymentId) {
    return "The frontend and backend deployment IDs do not match.";
  }
  if (status.chainId !== BigInt(expected.chainId)) return "The frontend and backend chain IDs do not match.";
  if (status.dexAddress?.toLowerCase() !== expected.dexAddress.toLowerCase()) {
    return "The frontend and backend OrderBook addresses do not match.";
  }
  if (status.marketRegistryAddress?.toLowerCase() !== expected.marketRegistryAddress.toLowerCase()) {
    return "The frontend and backend MarketRegistry addresses do not match.";
  }
  return undefined;
}

export class BackendApiClient {
  constructor(private readonly baseUrl: string) {
    if (!baseUrl) throw new Error("VITE_BACKEND_URL is required");
  }

  private async get(path: string): Promise<unknown> {
    const response = await fetch(`${this.baseUrl}${path}`, { signal: AbortSignal.timeout(5000) });
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new BackendApiError("Backend returned an unreadable response.", response.status);
    }
    if (!response.ok) {
      const problem = object(body, "problem response");
      const detail = typeof problem.detail === "string" ? problem.detail : "The backend rejected the request.";
      const type = typeof problem.type === "string" ? problem.type : undefined;
      throw new BackendApiError(detail, response.status, type);
    }
    return body;
  }

  async markets(): Promise<Market[]> {
    const raw = object(await this.get("/markets"), "markets response");
    return items(raw.items, "markets").map(mapMarket);
  }

  async orderBook(marketId: bigint, limit = 200): Promise<OrderBookSnapshot> {
    const raw = object(await this.get(`/markets/${marketId}/orderbook?limit=${limit}`), "order-book response");
    const bids = object(raw.bids, "bid side");
    const asks = object(raw.asks, "ask side");
    return {
      marketId: decimal(raw.marketId, "order-book market ID"),
      limit: number(raw.limit, "order-book limit"),
      bids: items(bids.items, "bids").map(mapBookOrder),
      asks: items(asks.items, "asks").map(mapBookOrder),
      bidsHaveMore: boolean(bids.hasMore, "bids has-more flag"),
      asksHaveMore: boolean(asks.hasMore, "asks has-more flag"),
    };
  }

  async walletOrders(
    owner: string,
    marketId: bigint,
    cursor?: string,
    limit = 50,
  ): Promise<CursorPage<Order>> {
    const query = new URLSearchParams({ owner, marketId: marketId.toString(), limit: limit.toString() });
    if (cursor) query.set("cursor", cursor);
    return mapCursorPage(await this.get(`/orders?${query}`), mapOrder, "orders response");
  }

  async trades(
    market: Market,
    cursor?: string,
    limit = 50,
  ): Promise<CursorPage<Trade>> {
    const query = new URLSearchParams({ limit: limit.toString() });
    if (cursor) query.set("cursor", cursor);
    return mapCursorPage(
      await this.get(`/markets/${market.id}/trades?${query}`),
      (item) => mapTrade(item, market),
      "trades response",
    );
  }

  async status(): Promise<BackendHealth> {
    const raw = object(await this.get("/status"), "status response");
    const deployment = object(raw.deployment, "deployment status");
    const indexer = object(raw.indexer, "indexer status");
    const matcher = object(raw.matcher, "matcher status");
    const executor = object(raw.executor, "executor status");
    return {
      online: true,
      operationalStatus: enumValue<"HEALTHY" | "STARTING" | "DEGRADED">(
        raw.status,
        ["HEALTHY", "STARTING", "DEGRADED"],
        "backend status",
      ),
      deploymentId: string(deployment.deploymentId, "deployment ID"),
      registered: boolean(deployment.registered, "deployment registration flag"),
      chainId: deployment.chainId === null ? undefined : decimal(deployment.chainId, "chain ID"),
      dexAddress: deployment.dexAddress === null ? undefined : string(deployment.dexAddress, "OrderBook address"),
      marketRegistryAddress: deployment.marketRegistryAddress === null
        ? undefined
        : string(deployment.marketRegistryAddress, "MarketRegistry address"),
      indexedBlock: indexer.lastIndexedBlockNumber === null
        ? undefined
        : decimal(indexer.lastIndexedBlockNumber, "last indexed block"),
      indexerState: string(indexer.state, "indexer state"),
      matcherState: string(matcher.state, "matcher state"),
      executorState: string(executor.state, "executor state"),
      checkedAt: Date.now(),
    };
  }
}
