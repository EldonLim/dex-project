package com.dex.backend.market;

import com.dex.backend.blockchain.model.EthereumAddress;

import java.math.BigInteger;
import java.util.Objects;

public record Market(
        String deploymentId,
        BigInteger marketId,
        EthereumAddress baseTokenAddress,
        EthereumAddress quoteTokenAddress,
        String baseSymbol,
        String quoteSymbol,
        int baseDecimals,
        int quoteDecimals,
        BigInteger baseLotSizeAtoms,
        boolean active
) {

    public Market {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }

        Objects.requireNonNull(marketId, "marketId must not be null");

        if (marketId.signum() < 0) {
            throw new IllegalArgumentException("marketId must be non-negative");
        }

        Objects.requireNonNull(baseTokenAddress, "baseTokenAddress must not be null");
        Objects.requireNonNull(quoteTokenAddress, "quoteTokenAddress must not be null");

        if (baseTokenAddress.equals(quoteTokenAddress)) {
            throw new IllegalArgumentException(
                    "Base and quote token addresses must differ"
            );
        }

        baseSymbol = normalizeSymbol(baseSymbol, "baseSymbol");
        quoteSymbol = normalizeSymbol(quoteSymbol, "quoteSymbol");

        validateDecimals(baseDecimals, "baseDecimals");
        validateDecimals(quoteDecimals, "quoteDecimals");

        Objects.requireNonNull(baseLotSizeAtoms, "baseLotSizeAtoms must not be null");

        if (baseLotSizeAtoms.signum() <= 0) {
            throw new IllegalArgumentException("baseLotSizeAtoms must be positive");
        }
    }

    public BigInteger baseAmountAtoms(BigInteger lots) {
        Objects.requireNonNull(lots, "lots must not be null");

        if (lots.signum() < 0) {
            throw new IllegalArgumentException("lots must be non-negative");
        }

        return lots.multiply(baseLotSizeAtoms);
    }

    private static String normalizeSymbol(String symbol, String fieldName) {
        Objects.requireNonNull(symbol, fieldName + " must not be null");

        String normalized = symbol.strip();

        if (normalized.isBlank() || normalized.length() > 32) {
            throw new IllegalArgumentException(
                    fieldName + " must be nonblank and at most 32 characters"
            );
        }

        return normalized;
    }

    private static void validateDecimals(int decimals, String fieldName) {
        if (decimals < 0 || decimals > 255) {
            throw new IllegalArgumentException(
                    fieldName + " must be between 0 and 255"
            );
        }
    }
}