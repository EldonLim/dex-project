package com.dex.backend.blockchain.model;

import java.math.BigInteger;
import java.util.Objects;

public record BlockRange(
        BigInteger fromInclusive,
        BigInteger toInclusive
) {

    public BlockRange {
        Objects.requireNonNull(
                fromInclusive, "fromInclusive must not be null"
        );
        Objects.requireNonNull(
                toInclusive, "toInclusive must not be null"
        );

        if (fromInclusive.signum() < 0 || toInclusive.signum() < 0) {
            throw new IllegalArgumentException(
                    "Block range boundaries must be non-negative"
            );
        }

        if (fromInclusive.compareTo(toInclusive) > 0) {
            throw new IllegalArgumentException(
                    "fromInclusive must not exceed toInclusive"
            );
        }
    }

    public BigInteger size() {
        return toInclusive.subtract(fromInclusive).add(BigInteger.ONE);
    }
}