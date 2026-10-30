package com.dex.backend.api.trade;

public record TradeExecutionPositionResponse(
        String blockNumber,
        String transactionIndex,
        String logIndex
) {
}
