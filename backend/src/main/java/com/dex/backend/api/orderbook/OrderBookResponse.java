package com.dex.backend.api.orderbook;

public record OrderBookResponse(
        String marketId,
        int limit,
        OrderBookSideResponse bids,
        OrderBookSideResponse asks
) {
}
