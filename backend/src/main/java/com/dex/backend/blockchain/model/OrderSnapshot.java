package com.dex.backend.blockchain.model;

import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;

import java.math.BigInteger;
import java.util.Objects;

public record OrderSnapshot(
        String deploymentId,
        BigInteger orderId,
        BigInteger marketId,
        EthereumAddress owner,
        OrderSide side,
        BigInteger limitPriceQuoteAtomsPerLot,
        BigInteger originalLots,
        BigInteger remainingLots,
        OrderStatus status
) {

    public OrderSnapshot {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }

        requireNonNegative(orderId, "orderId");
        requireNonNegative(marketId, "marketId");

        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(status, "status must not be null");

        requirePositive(
                limitPriceQuoteAtomsPerLot,
                "limitPriceQuoteAtomsPerLot"
        );
        requirePositive(originalLots, "originalLots");
        requireNonNegative(remainingLots, "remainingLots");

        int remainingComparedToOriginal =
                remainingLots.compareTo(originalLots);

        if (remainingComparedToOriginal > 0) {
            throw new IllegalArgumentException(
                    "remainingLots must not exceed originalLots"
            );
        }

        switch (status) {
            case OPEN -> {
                if (remainingComparedToOriginal != 0) {
                    throw new IllegalArgumentException(
                            "OPEN orders must have remainingLots equal to originalLots"
                    );
                }
            }
            case PARTIALLY_FILLED -> {
                if (remainingLots.signum() == 0
                        || remainingComparedToOriginal >= 0) {
                    throw new IllegalArgumentException(
                            "PARTIALLY_FILLED orders must have remainingLots "
                                    + "greater than zero and less than originalLots"
                    );
                }
            }
            case FILLED -> {
                if (remainingLots.signum() != 0) {
                    throw new IllegalArgumentException(
                            "FILLED orders must have zero remainingLots"
                    );
                }
            }
            case CANCELLED -> {
                // General quantity validation permits 0..originalLots.
            }
        }
    }

    public boolean isMatchable() {
        return (status == OrderStatus.OPEN
                || status == OrderStatus.PARTIALLY_FILLED)
                && remainingLots.signum() > 0;
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

    private static void requirePositive(
            BigInteger value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");

        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be positive"
            );
        }
    }
}