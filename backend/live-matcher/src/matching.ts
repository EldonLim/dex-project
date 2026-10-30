import type { ChainOrder, MatchProposal } from "./types.js";

function active(order: ChainOrder): boolean {
  return order.status === 0 && order.remainingLots > 0n;
}

export function proposeMatch(orders: readonly ChainOrder[]): MatchProposal | null {
  const bids = orders
    .filter((order) => active(order) && order.side === 0)
    .sort((left, right) => {
      if (left.limitPrice !== right.limitPrice) return left.limitPrice > right.limitPrice ? -1 : 1;
      return left.id < right.id ? -1 : left.id > right.id ? 1 : 0;
    });

  const asks = orders
    .filter((order) => active(order) && order.side === 1)
    .sort((left, right) => {
      if (left.limitPrice !== right.limitPrice) return left.limitPrice < right.limitPrice ? -1 : 1;
      return left.id < right.id ? -1 : left.id > right.id ? 1 : 0;
    });

  for (const buy of bids) {
    for (const sell of asks) {
      if (buy.marketId !== sell.marketId) continue;
      if (buy.limitPrice < sell.limitPrice) break;
      if (buy.owner.toLowerCase() === sell.owner.toLowerCase()) continue;

      const fillLots = buy.remainingLots < sell.remainingLots
        ? buy.remainingLots
        : sell.remainingLots;

      // Sequential order IDs reflect placement time in Member 2's contract.
      // The older order supplies the execution price.
      const executionPrice = buy.id < sell.id ? buy.limitPrice : sell.limitPrice;

      return { buy, sell, fillLots, executionPrice };
    }
  }

  return null;
}
