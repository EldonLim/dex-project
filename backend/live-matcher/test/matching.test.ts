import assert from "node:assert/strict";
import test from "node:test";
import { proposeMatch } from "../src/matching.js";
import type { ChainOrder } from "../src/types.js";

function order(overrides: Partial<ChainOrder> & Pick<ChainOrder, "id" | "owner" | "side" | "limitPrice">): ChainOrder {
  return {
    marketId: 1n,
    originalLots: 10n,
    remainingLots: 10n,
    status: 0,
    ...overrides,
  };
}

test("matches a crossing buy and sell with a partial fill", () => {
  const sell = order({ id: 1n, owner: "0xseller", side: 1, limitPrice: 2n, remainingLots: 4n });
  const buy = order({ id: 2n, owner: "0xbuyer", side: 0, limitPrice: 3n, remainingLots: 2n });
  const match = proposeMatch([buy, sell]);
  assert.ok(match);
  assert.equal(match.fillLots, 2n);
  assert.equal(match.executionPrice, 2n);
});

test("uses price priority and then order ID time priority", () => {
  const asks = [
    order({ id: 3n, owner: "0xseller3", side: 1, limitPrice: 3n }),
    order({ id: 1n, owner: "0xseller1", side: 1, limitPrice: 2n }),
    order({ id: 2n, owner: "0xseller2", side: 1, limitPrice: 2n }),
  ];
  const buy = order({ id: 4n, owner: "0xbuyer", side: 0, limitPrice: 4n });
  assert.equal(proposeMatch([buy, ...asks])?.sell.id, 1n);
});

test("rejects non-crossing and self-trading pairs", () => {
  const buy = order({ id: 1n, owner: "0xsame", side: 0, limitPrice: 2n });
  const selfSell = order({ id: 2n, owner: "0xSAME", side: 1, limitPrice: 1n });
  const expensiveSell = order({ id: 3n, owner: "0xother", side: 1, limitPrice: 3n });
  assert.equal(proposeMatch([buy, selfSell, expensiveSell]), null);
});
