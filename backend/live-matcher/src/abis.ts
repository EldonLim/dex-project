export const orderBookAbi = [
  "function nextOrderId() view returns (uint256)",
  "function getOrder(uint256 orderId) view returns (tuple(uint256 id,address owner,uint256 marketId,uint8 side,uint256 originalLots,uint256 remainingLots,uint256 limitPrice,uint8 status))",
  "function executeTrade(uint256 buyOrderId,uint256 sellOrderId,uint256 fillLots,uint256 executionPrice)",
  "event TradeExecuted(uint256 indexed buyOrderId,uint256 indexed sellOrderId,uint256 indexed marketId,address buyer,address seller,uint256 fillLots,uint256 executionPrice,uint256 baseAtoms,uint256 quoteAtoms)",
] as const;

export const marketRegistryAbi = [
  "function nextMarketId() view returns (uint256)",
  "function getMarket(uint256 marketId) view returns (address baseToken,address quoteToken,uint256 baseLotAtoms,bool isRegistered)",
] as const;

export const erc20Abi = [
  "function name() view returns (string)",
  "function symbol() view returns (string)",
  "function decimals() view returns (uint8)",
  "function balanceOf(address owner) view returns (uint256)",
  "function allowance(address owner,address spender) view returns (uint256)",
] as const;
