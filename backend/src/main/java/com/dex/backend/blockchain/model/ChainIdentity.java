package com.dex.backend.blockchain.model;

import java.math.BigInteger;
import java.util.Objects;

public record ChainIdentity(
        String deploymentId,
        BigInteger chainId,
        EthereumAddress dexAddress,
        BigInteger deploymentBlockNumber,
        Hash256 deploymentBlockHash
) {

    public ChainIdentity {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }

        Objects.requireNonNull(chainId, "chainId must not be null");
        Objects.requireNonNull(dexAddress, "dexAddress must not be null");
        Objects.requireNonNull(
                deploymentBlockNumber,
                "deploymentBlockNumber must not be null"
        );
        Objects.requireNonNull(
                deploymentBlockHash,
                "deploymentBlockHash must not be null"
        );

        if (chainId.signum() < 0) {
            throw new IllegalArgumentException("chainId must be non-negative");
        }

        if (deploymentBlockNumber.signum() < 0) {
            throw new IllegalArgumentException(
                    "deploymentBlockNumber must be non-negative"
            );
        }
    }
}