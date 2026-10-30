package com.dex.backend.api.trade;

public record TradeResponse(
        String tradeId,
        String marketId,
        String buyOrderId,
        String sellOrderId,
        String fillLots,
        String executionPriceQuoteAtomsPerLot,
        String buyOrderRemainingLots,
        String sellOrderRemainingLots,
        TradeExecutionPositionResponse executionPosition,
        String transactionHash,
        String blockTimestamp
) {
}
