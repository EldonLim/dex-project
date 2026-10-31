package com.dex.backend.blockchain.execution;

import java.math.BigInteger;
import java.util.Objects;

public record SettlementRequest(
        String deploymentId,
        BigInteger marketId,
        BigInteger buyOrderId,
        BigInteger sellOrderId,
        BigInteger fillLots,
        BigInteger executionPriceQuoteAtomsPerLot
) {

    public SettlementRequest {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }

        requireNonNegative(marketId, "marketId");
        requireNonNegative(buyOrderId, "buyOrderId");
        requireNonNegative(sellOrderId, "sellOrderId");

        if (buyOrderId.equals(sellOrderId)) {
            throw new IllegalArgumentException(
                    "buyOrderId and sellOrderId must differ"
            );
        }

        Objects.requireNonNull(fillLots, "fillLots must not be null");

        if (fillLots.signum() <= 0) {
            throw new IllegalArgumentException("fillLots must be positive");
        }

        Objects.requireNonNull(
                executionPriceQuoteAtomsPerLot,
                "executionPriceQuoteAtomsPerLot must not be null"
        );

        if (executionPriceQuoteAtomsPerLot.signum() <= 0) {
            throw new IllegalArgumentException(
                    "executionPriceQuoteAtomsPerLot must be positive"
            );
        }
    }

    private static void requireNonNegative(
            BigInteger value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");

        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be non-negative"
            );
        }
    }
}
