package com.dex.backend.api.market;

public record TokenResponse(
        String address,
        String symbol,
        int decimals
) {
}
