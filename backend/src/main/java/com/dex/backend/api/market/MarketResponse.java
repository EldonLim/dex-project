package com.dex.backend.api.market;

public record MarketResponse(
        String marketId,
        TokenResponse baseToken,
        TokenResponse quoteToken,
        String baseLotSizeAtoms,
        String quantityUnit,
        String priceUnit,
        boolean active
) {
}
