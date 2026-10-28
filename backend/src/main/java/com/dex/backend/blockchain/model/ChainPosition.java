package com.dex.backend.blockchain.model;

import java.math.BigInteger;
import java.util.Objects;

public record ChainPosition(
    BigInteger blockNumber,
    BigInteger transactionIndex,
    BigInteger logIndex
) implements Comparable<ChainPosition> {
    
    public ChainPosition {
        Objects.requireNonNull(blockNumber, "Block number must not be null");
        Objects.requireNonNull(transactionIndex, "Transaction index must not be null");
        Objects.requireNonNull(logIndex, "Log index must not be null");

        if (blockNumber.signum() < 0) {
            throw new IllegalArgumentException("blockNumber must be non-negative");
        }
        if (transactionIndex.signum() < 0) {
            throw new IllegalArgumentException("transactionIndex must be non-negative");
        }
        if (logIndex.signum() < 0) {
            throw new IllegalArgumentException("logIndex must be non-negative");
        }
    }

    @Override 
    public int compareTo(ChainPosition other) {
        Objects.requireNonNull(other, "other must be non null");

        int result = blockNumber.compareTo(other.blockNumber());
        if (result != 0) {
            return result;
        }

        result = transactionIndex.compareTo(other.transactionIndex());
        if (result != 0) {
            return result;
        }

        return logIndex.compareTo(other.logIndex());
    }
}
