export type Side = "BUY" | "SELL";
export type OrderStatus = "OPEN" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED";

export interface TokenMeta {
  address: string;
  symbol: string;
  decimals: number;
}

export interface Market {
  id: bigint;
  baseToken: TokenMeta;
  quoteToken: TokenMeta;
  baseLotAtoms: bigint;
  active: boolean;
}

export interface ChainPosition {
  blockNumber: bigint;
  transactionIndex: bigint;
  logIndex: bigint;
}

export interface OrderBookOrder {
  id: bigint;
  side: Side;
  remainingLots: bigint;
  limitPrice: bigint;
  creationPosition: ChainPosition;
}

export interface Order {
  id: bigint;
  owner: string;
  marketId: bigint;
  side: Side;
  originalLots: bigint;
  remainingLots: bigint;
  limitPrice: bigint;
  status: OrderStatus;
  creationPosition: ChainPosition;
  creationTransactionHash: string;
  blockTimestamp: string;
}

export interface Trade {
  transactionHash: string;
  blockNumber: bigint;
  logIndex: bigint;
  tradeId: bigint | null;
  buyOrderId: bigint;
  sellOrderId: bigint;
  marketId: bigint;
  fillLots: bigint;
  executionPrice: bigint;
  baseAtoms: bigint;
  quoteAtoms: bigint;
  blockTimestamp: string;
}

export type TxPhase = "awaiting-signature" | "submitted" | "indexing" | "confirmed" | "rejected" | "failed";

export interface TxNotice {
  id: string;
  label: string;
  phase: TxPhase;
  hash?: string;
  message: string;
  createdAt: number;
}

export interface BackendHealth {
  online: boolean;
  operationalStatus?: "HEALTHY" | "STARTING" | "DEGRADED";
  deploymentId?: string;
  registered?: boolean;
  chainId?: bigint;
  dexAddress?: string;
  marketRegistryAddress?: string;
  indexedBlock?: bigint;
  indexerState?: string;
  matcherState?: string;
  executorState?: string;
  identityError?: string;
  checkedAt: number;
}

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}
