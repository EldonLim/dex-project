package com.dex.backend.blockchain.event;

import java.math.BigInteger;
import java.util.Objects;

public record OrderCancelledEvent(
        EventMetadata metadata,
        BigInteger orderId
) implements DexEvent {

    public OrderCancelledEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");

        requireNonNegative(orderId, "orderId");
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
