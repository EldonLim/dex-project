export const erc20Abi = [
  "function name() view returns (string)",
  "function symbol() view returns (string)",
  "function decimals() view returns (uint8)",
  "function balanceOf(address owner) view returns (uint256)",
  "function allowance(address owner, address spender) view returns (uint256)",
  "function approve(address spender, uint256 value) returns (bool)",
] as const;

export const tokenRegistryAbi = [
  "function owner() view returns (address)",
  "function registerToken(address token)",
  "function isApprovedToken(address token) view returns (bool)",
  "function getRegisteredTokens() view returns (address[])",
] as const;

export const marketRegistryAbi = [
  "function owner() view returns (address)",
  "function nextMarketId() view returns (uint256)",
  "function registerMarket(address base, address quote, uint256 baseLotAtoms) returns (uint256)",
  "function getMarket(uint256 marketId) view returns (address baseToken, address quoteToken, uint256 baseLotAtoms, bool isRegistered)",
] as const;

export const orderBookAbi = [
  "function nextOrderId() view returns (uint256)",
  "function placeOrder(uint256 marketId, uint8 side, uint256 lots, uint256 limitPrice) returns (uint256)",
  "function cancelOrder(uint256 orderId)",
  "function getOrder(uint256 orderId) view returns ((uint256 id, address owner, uint256 marketId, uint8 side, uint256 originalLots, uint256 remainingLots, uint256 limitPrice, uint8 status))",
  "event OrderPlaced(uint256 indexed orderId, address indexed owner, uint256 indexed marketId, uint8 side, uint256 lots, uint256 limitPrice)",
  "event OrderCancelled(uint256 indexed orderId)",
  "event TradeExecuted(uint256 indexed buyOrderId, uint256 indexed sellOrderId, uint256 indexed marketId, address buyer, address seller, uint256 fillLots, uint256 executionPrice, uint256 baseAtoms, uint256 quoteAtoms)",
] as const;
