package com.dex.backend.blockchain.event;

import com.dex.backend.blockchain.model.EthereumAddress;

import java.math.BigInteger;
import java.util.Objects;

public record TradeExecutedEvent(
        EventMetadata metadata,
        BigInteger tradeId,
        BigInteger marketId,
        BigInteger buyOrderId,
        BigInteger sellOrderId,
        BigInteger fillLots,
        BigInteger executionPriceQuoteAtomsPerLot,
        EthereumAddress buyer,
        EthereumAddress seller,
        BigInteger baseAmountAtoms,
        BigInteger quoteAmountAtoms
) implements DexEvent {

    public TradeExecutedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");

        requireOptionalNonNegative(tradeId, "tradeId");
        requireNonNegative(marketId, "marketId");
        requireNonNegative(buyOrderId, "buyOrderId");
        requireNonNegative(sellOrderId, "sellOrderId");
        requirePositive(fillLots, "fillLots");
        requirePositive(
                executionPriceQuoteAtomsPerLot,
                "executionPriceQuoteAtomsPerLot"
        );
        Objects.requireNonNull(buyer, "buyer must not be null");
        Objects.requireNonNull(seller, "seller must not be null");
        requirePositive(baseAmountAtoms, "baseAmountAtoms");
        requirePositive(quoteAmountAtoms, "quoteAmountAtoms");

        if (buyOrderId.equals(sellOrderId)) {
            throw new IllegalArgumentException(
                    "buyOrderId and sellOrderId must differ"
            );
        }
    }

    @Override
    public DexEventType type() {
        return DexEventType.TRADE_EXECUTED;
    }

    private static void requireOptionalNonNegative(
            BigInteger value,
            String fieldName
    ) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be non-negative when present"
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

    private static void requirePositive(
            BigInteger value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
