package com.dex.backend.blockchain.model;

import java.math.BigInteger;
import java.util.Objects;

public record MarketSnapshot(
        String deploymentId,
        BigInteger marketId,
        EthereumAddress baseTokenAddress,
        EthereumAddress quoteTokenAddress,
        BigInteger baseLotSizeAtoms,
        boolean active
) {

    public MarketSnapshot {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }

        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(
                baseTokenAddress, "baseTokenAddress must not be null"
        );
        Objects.requireNonNull(
                quoteTokenAddress, "quoteTokenAddress must not be null"
        );
        Objects.requireNonNull(
                baseLotSizeAtoms, "baseLotSizeAtoms must not be null"
        );

        if (marketId.signum() < 0) {
            throw new IllegalArgumentException("marketId must be non-negative");
        }

        if (baseTokenAddress.equals(quoteTokenAddress)) {
            throw new IllegalArgumentException(
                    "Base and quote token addresses must differ"
            );
        }

        if (baseLotSizeAtoms.signum() <= 0) {
            throw new IllegalArgumentException(
                    "baseLotSizeAtoms must be positive"
            );
        }
    }
}