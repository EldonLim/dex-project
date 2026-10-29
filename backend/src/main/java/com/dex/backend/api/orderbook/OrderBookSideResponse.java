package com.dex.backend.api.orderbook;

import java.util.List;

public record OrderBookSideResponse(
        List<OrderBookOrderResponse> items,
        boolean hasMore
) {
}
