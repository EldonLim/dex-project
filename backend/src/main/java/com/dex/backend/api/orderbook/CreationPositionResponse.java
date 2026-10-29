package com.dex.backend.api.orderbook;

public record CreationPositionResponse(
        String blockNumber,
        String transactionIndex,
        String logIndex
) {
}
