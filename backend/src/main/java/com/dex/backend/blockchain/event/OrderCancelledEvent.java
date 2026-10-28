package com.dex.backend.blockchain.event;

import java.math.BigInteger;
import java.util.Objects;

public record OrderCancelledEvent(
        EventMetadata metadata,
        BigInteger orderId,
        BigInteger marketId,
        BigInteger remainingLots
) implements DexEvent {

    public OrderCancelledEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");

        requireNonNegative(orderId, "orderId");
        requireNonNegative(marketId, "marketId");
        requireNonNegative(remainingLots, "remainingLots");
    }

    @Override
    public DexEventType type() {
        return DexEventType.ORDER_CANCELLED;
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