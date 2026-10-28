package com.dex.backend.order;

import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

public record Order(
        String deploymentId,
        BigInteger orderId,
        BigInteger marketId,
        EthereumAddress owner,
        OrderSide side,
        BigInteger limitPriceQuoteAtomsPerLot,
        BigInteger originalLots,
        BigInteger remainingLots,
        OrderStatus status,
        ChainPosition creationPosition,
        Hash256 creationTransactionHash,
        Hash256 creationBlockHash,
        Instant blockTimestamp
) {

    public Order {
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
        Objects.requireNonNull(
                creationPosition, "creationPosition must not be null"
        );
        Objects.requireNonNull(
                creationTransactionHash,
                "creationTransactionHash must not be null"
        );
        Objects.requireNonNull(
                creationBlockHash, "creationBlockHash must not be null"
        );
        Objects.requireNonNull(
                blockTimestamp, "blockTimestamp must not be null"
        );

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
                // General quantity validation already permits 0..originalLots.
            }
        }
    }

    public BigInteger filledLots() {
        return originalLots.subtract(remainingLots);
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