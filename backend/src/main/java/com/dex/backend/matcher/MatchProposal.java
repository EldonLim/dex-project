package com.dex.backend.matcher;

import java.math.BigInteger;
import java.util.Objects;

public record MatchProposal(
        String deploymentId,
        BigInteger marketId,
        BigInteger buyOrderId,
        BigInteger sellOrderId,
        BigInteger fillLots,
        BigInteger executionPriceQuoteAtomsPerLot
) {

    public MatchProposal {
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

        requirePositive(fillLots, "fillLots");
        requirePositive(
                executionPriceQuoteAtomsPerLot,
                "executionPriceQuoteAtomsPerLot"
        );
    }

    private static void requireNonNegative(BigInteger value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");

        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
    }

    private static void requirePositive(BigInteger value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");

        if (value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
