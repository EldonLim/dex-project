package com.dex.backend.blockchain.execution;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record PreparedSettlement(
        SettlementRequest request,
        EthereumAddress executorAddress,
        BigInteger nonce,
        Hash256 transactionHash,
        String signedTransactionHex
) {

    private static final Pattern SIGNED_TRANSACTION_PATTERN =
            Pattern.compile("0x(?:[0-9a-f]{2})+");

    public PreparedSettlement {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(
                executorAddress, "executorAddress must not be null"
        );
        Objects.requireNonNull(nonce, "nonce must not be null");
        Objects.requireNonNull(
                transactionHash, "transactionHash must not be null"
        );
        Objects.requireNonNull(
                signedTransactionHex, "signedTransactionHex must not be null"
        );

        if (nonce.signum() < 0) {
            throw new IllegalArgumentException("nonce must be non-negative");
        }

        signedTransactionHex =
                signedTransactionHex.toLowerCase(Locale.ROOT);

        if (!SIGNED_TRANSACTION_PATTERN.matcher(signedTransactionHex).matches()) {
            throw new IllegalArgumentException(
                    "signedTransactionHex must have a 0x prefix and "
                            + "contain one or more complete hexadecimal bytes"
            );
        }
    }

    @Override
    public String toString() {
        return "PreparedSettlement["
                + "request=" + request
                + ", executorAddress=" + executorAddress
                + ", nonce=" + nonce
                + ", transactionHash=" + transactionHash
                + ", signedTransactionHex=<redacted>"
                + "]";
    }
}