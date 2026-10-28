package com.dex.backend.blockchain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Hash256(String value) {
    private static final Pattern HASH_PATTERN = Pattern.compile("0x[0-9a-f]{64}");
    
    public Hash256 {
        Objects.requireNonNull(value, "Hash256 value must be non null");

        value = value.toLowerCase(Locale.ROOT);

        if (!HASH_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Hash256 must be 0x followed by exactly 64 hexadecimal characters");
        }
    }

    @Override 
    public String toString() {
        return value;
    }
}
