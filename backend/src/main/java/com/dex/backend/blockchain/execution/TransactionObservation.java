package com.dex.backend.blockchain.execution;

import com.dex.backend.blockchain.model.Hash256;

import java.math.BigInteger;
import java.util.Objects;

public record TransactionObservation(
        Hash256 transactionHash,
        TransactionStatus status,
        BigInteger blockNumber,
        Hash256 blockHash,
        String failureReason
) {

    public TransactionObservation {
        Objects.requireNonNull(
                transactionHash, "transactionHash must not be null"
        );
        Objects.requireNonNull(status, "status must not be null");

        if (failureReason != null && failureReason.isBlank()) {
            failureReason = null;
        }

        if (blockNumber != null && blockNumber.signum() < 0) {
            throw new IllegalArgumentException(
                    "blockNumber must be non-negative"
            );
        }

        switch (status) {
            case NOT_FOUND, PENDING -> {
                if (blockNumber != null || blockHash != null) {
                    throw new IllegalArgumentException(
                            status + " must not include block information"
                    );
                }

                if (failureReason != null) {
                    throw new IllegalArgumentException(
                            status + " must not include a failure reason"
                    );
                }
            }
            case SUCCEEDED, REVERTED -> {
                if (blockNumber == null || blockHash == null) {
                    throw new IllegalArgumentException(
                            status + " requires both blockNumber and blockHash"
                    );
                }

                if (status == TransactionStatus.SUCCEEDED
                        && failureReason != null) {
                    throw new IllegalArgumentException(
                            "SUCCEEDED must not include a failure reason"
                    );
                }
            }
        }
    }

    public static TransactionObservation notFound(Hash256 transactionHash) {
        return new TransactionObservation(
                transactionHash,
                TransactionStatus.NOT_FOUND,
                null,
                null,
                null
        );
    }

    public static TransactionObservation pending(Hash256 transactionHash) {
        return new TransactionObservation(
                transactionHash,
                TransactionStatus.PENDING,
                null,
                null,
                null
        );
    }

    public static TransactionObservation succeeded(
            Hash256 transactionHash,
            BigInteger blockNumber,
            Hash256 blockHash
    ) {
        return new TransactionObservation(
                transactionHash,
                TransactionStatus.SUCCEEDED,
                blockNumber,
                blockHash,
                null
        );
    }

    public static TransactionObservation reverted(
            Hash256 transactionHash,
            BigInteger blockNumber,
            Hash256 blockHash,
            String failureReason
    ) {
        return new TransactionObservation(
                transactionHash,
                TransactionStatus.REVERTED,
                blockNumber,
                blockHash,
                failureReason
        );
    }
}