export type Side = "BUY" | "SELL";
export type OrderStatus = "OPEN" | "FILLED" | "CANCELLED";

export interface TokenMeta {
  address: string;
  name: string;
  symbol: string;
  decimals: number;
}

export interface Market {
  id: bigint;
  baseToken: TokenMeta;
  quoteToken: TokenMeta;
  baseLotAtoms: bigint;
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
}

export interface Trade {
  transactionHash: string;
  blockNumber: number;
  buyOrderId: bigint;
  sellOrderId: bigint;
  marketId: bigint;
  buyer: string;
  seller: string;
  fillLots: bigint;
  executionPrice: bigint;
  baseAtoms: bigint;
  quoteAtoms: bigint;
}

export type TxPhase = "awaiting-signature" | "submitted" | "confirmed" | "rejected" | "failed";

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
  indexedBlock?: number;
  chainId?: number;
  stale: boolean;
  checkedAt: number;
}
