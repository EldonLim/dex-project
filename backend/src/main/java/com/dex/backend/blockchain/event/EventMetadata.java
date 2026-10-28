package com.dex.backend.blockchain.event;

import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;

import java.time.Instant;
import java.util.Objects;

public record EventMetadata(
        String deploymentId,
        EthereumAddress contractAddress,
        Hash256 transactionHash,
        Hash256 blockHash,
        ChainPosition position,
        Instant blockTimestamp
) {

    public EventMetadata {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }

        Objects.requireNonNull(
                contractAddress, "contractAddress must not be null"
        );
        Objects.requireNonNull(
                transactionHash, "transactionHash must not be null"
        );
        Objects.requireNonNull(blockHash, "blockHash must not be null");
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(
                blockTimestamp, "blockTimestamp must not be null"
        );
    }
}