export interface ChainOrder {
  id: bigint;
  owner: string;
  marketId: bigint;
  side: 0 | 1;
  originalLots: bigint;
  remainingLots: bigint;
  limitPrice: bigint;
  status: 0 | 1 | 2;
}

export interface MatchProposal {
  buy: ChainOrder;
  sell: ChainOrder;
  fillLots: bigint;
  executionPrice: bigint;
}

export interface MatcherSnapshot {
  running: boolean;
  matching: boolean;
  chainId?: number;
  indexedBlock?: number;
  lastCycleAt?: string;
  lastMatchAt?: string;
  lastTransactionHash?: string;
  lastError?: string;
  matchesExecuted: number;
}
