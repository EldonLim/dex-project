import { describe, expect, it } from "vitest";
import type { Market } from "../types";
import { formatOrderBase, formatPrice, parseOrderInput } from "./units";

const market: Market = {
  id: 1n,
  baseToken: { address: "0x0000000000000000000000000000000000000001", name: "Token A", symbol: "TKA", decimals: 18 },
  quoteToken: { address: "0x0000000000000000000000000000000000000002", name: "Token B", symbol: "TKB", decimals: 18 },
  baseLotAtoms: 10n ** 16n,
};

describe("DEX unit conversions", () => {
  it("converts human amount and price to exact lots and quote atoms", () => {
    const parsed = parseOrderInput("40", "2", market);
    expect(parsed.lots).toBe(4000n);
    expect(parsed.limitPrice).toBe(2n * 10n ** 16n);
    expect(parsed.quoteAtoms).toBe(80n * 10n ** 18n);
  });

  it("rejects quantities that are not whole lots", () => {
    expect(() => parseOrderInput("0.001", "2", market)).toThrow(/whole multiple/);
  });

  it("formats on-chain values back into UI units", () => {
    expect(formatOrderBase(4000n, market)).toBe("40");
    expect(formatPrice(2n * 10n ** 16n, market)).toBe("2");
  });
});
