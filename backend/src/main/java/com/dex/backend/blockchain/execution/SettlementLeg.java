package com.dex.backend.blockchain.execution;

import java.math.BigInteger;
import java.util.Objects;

public record SettlementLeg(
        BigInteger buyOrderId,
        BigInteger sellOrderId,
        BigInteger fillLots,
        BigInteger executionPriceQuoteAtomsPerLot
) {

    public SettlementLeg {
        requireNonNegative(buyOrderId, "buyOrderId");
        requireNonNegative(sellOrderId, "sellOrderId");
        if (buyOrderId.equals(sellOrderId)) {
            throw new IllegalArgumentException("buyOrderId and sellOrderId must differ");
        }
        requirePositive(fillLots, "fillLots");
        requirePositive(
                executionPriceQuoteAtomsPerLot,
                "executionPriceQuoteAtomsPerLot"
        );
    }

    private static void requireNonNegative(BigInteger value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requirePositive(BigInteger value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
