package com.dex.backend.trade;

import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.Hash256;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

public record Trade(
        String deploymentId,
        Hash256 transactionHash,
        BigInteger tradeId,
        BigInteger marketId,
        BigInteger buyOrderId,
        BigInteger sellOrderId,
        BigInteger fillLots,
        BigInteger executionPriceQuoteAtomsPerLot,
        BigInteger buyOrderRemainingLots,
        BigInteger sellOrderRemainingLots,
        ChainPosition position,
        Hash256 blockHash,
        Instant blockTimestamp
) {

    public Trade {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }

        Objects.requireNonNull(
                transactionHash, "transactionHash must not be null"
        );
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(blockHash, "blockHash must not be null");
        Objects.requireNonNull(
                blockTimestamp, "blockTimestamp must not be null"
        );

        if (tradeId != null) {
            requireNonNegative(tradeId, "tradeId");
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

        requireNonNegative(buyOrderRemainingLots, "buyOrderRemainingLots");
        requireNonNegative(sellOrderRemainingLots, "sellOrderRemainingLots");
    }

    public BigInteger quoteAmountAtoms() {
        return fillLots.multiply(executionPriceQuoteAtomsPerLot);
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