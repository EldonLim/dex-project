package com.dex.backend.blockchain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EthereumAddress(String value) {
    private static final Pattern ADDRESS_PATTERN =
        Pattern.compile("0x[0-9a-f]{40}");

    public EthereumAddress {
        Objects.requireNonNull(value, "Ethereum address must not be null");

        value = value.toLowerCase(Locale.ROOT);

        if (!ADDRESS_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Ethereum address must be 0x followed by exactly 40 hexadecimal characters");
        }
    }

    @Override 
    public String toString() {
        return value;
    }
} 
