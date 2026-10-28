package com.dex.backend.blockchain.model;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

public record BlockReference(
        BigInteger number,
        Hash256 hash,
        Instant timestamp
) {

    public BlockReference {
        Objects.requireNonNull(number, "number must not be null");
        Objects.requireNonNull(hash, "hash must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        if (number.signum() < 0) {
            throw new IllegalArgumentException("number must be non-negative");
        }
    }
}