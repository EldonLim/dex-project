import { formatUnits, parseUnits } from "ethers";
import type { Market } from "../types";

export interface ParsedOrderInput {
  lots: bigint;
  limitPrice: bigint;
  baseAtoms: bigint;
  quoteAtoms: bigint;
}

export function parseOrderInput(amount: string, price: string, market: Market): ParsedOrderInput {
  const baseAtoms = parseUnits(amount.trim(), market.baseToken.decimals);
  const priceAtomsPerBase = parseUnits(price.trim(), market.quoteToken.decimals);
  if (baseAtoms <= 0n || priceAtomsPerBase <= 0n) {
    throw new Error("Amount and price must both be greater than zero.");
  }
  if (baseAtoms % market.baseLotAtoms !== 0n) {
    throw new Error(`Amount must be a whole multiple of ${formatAmount(market.baseLotAtoms, market.baseToken.decimals)} ${market.baseToken.symbol} (one lot).`);
  }

  const baseScale = 10n ** BigInt(market.baseToken.decimals);
  const priceNumerator = priceAtomsPerBase * market.baseLotAtoms;
  if (priceNumerator % baseScale !== 0n) {
    throw new Error("That price is more precise than this market's lot size supports.");
  }

  const lots = baseAtoms / market.baseLotAtoms;
  const limitPrice = priceNumerator / baseScale;
  if (lots <= 0n || limitPrice <= 0n) {
    throw new Error("Amount and price must resolve to non-zero on-chain values.");
  }
  return { lots, limitPrice, baseAtoms, quoteAtoms: lots * limitPrice };
}

export function formatAmount(value: bigint, decimals: number, maxFraction = 6): string {
  const formatted = formatUnits(value, decimals);
  const [whole, fraction = ""] = formatted.split(".");
  const trimmed = fraction.slice(0, maxFraction).replace(/0+$/, "");
  return trimmed ? `${whole}.${trimmed}` : whole;
}

export function formatOrderBase(lots: bigint, market: Market): string {
  return formatAmount(lots * market.baseLotAtoms, market.baseToken.decimals);
}

export function formatPrice(limitPrice: bigint, market: Market): string {
  const baseScale = 10n ** BigInt(market.baseToken.decimals);
  const numerator = limitPrice * baseScale;
  const atomsPerBase = numerator / market.baseLotAtoms;
  return formatAmount(atomsPerBase, market.quoteToken.decimals);
}

export function shortAddress(address: string, leading = 6): string {
  return `${address.slice(0, leading)}…${address.slice(-4)}`;
}
