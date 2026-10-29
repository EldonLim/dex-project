package com.dex.backend.api.order;

public record OrderCreationPositionResponse(
        String blockNumber,
        String transactionIndex,
        String logIndex
) {
}
