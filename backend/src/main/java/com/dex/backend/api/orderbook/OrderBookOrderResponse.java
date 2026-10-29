package com.dex.backend.api.orderbook;

public record OrderBookOrderResponse(
        String orderId,
        String side,
        String limitPriceQuoteAtomsPerLot,
        String remainingLots,
        CreationPositionResponse creationPosition
) {
}
