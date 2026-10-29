package com.dex.backend.api.order;

public record OrderResponse(
        String orderId,
        String marketId,
        String owner,
        String side,
        String limitPriceQuoteAtomsPerLot,
        String originalLots,
        String filledLots,
        String remainingLots,
        String status,
        OrderCreationPositionResponse creationPosition,
        String creationTransactionHash,
        String blockTimestamp
) {
}
