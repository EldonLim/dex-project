package com.dex.backend.blockchain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Hash256Tests {

    private static final String VALID_HASH = "0x" + "ab".repeat(32);

    @Test
    void normalizesUppercaseInput() {
        Hash256 hash = new Hash256("0X" + "AB".repeat(32));

        assertEquals(VALID_HASH, hash.value());
        assertEquals(VALID_HASH, hash.toString());
    }

    @Test
    void preservesAlreadyNormalizedInput() {
        Hash256 hash = new Hash256(VALID_HASH);

        assertEquals(VALID_HASH, hash.value());
    }

    @Test
    void normalizedValuesAreEqual() {
        Hash256 lowercase = new Hash256(VALID_HASH);
        Hash256 uppercase = new Hash256("0x" + "AB".repeat(32));

        assertEquals(lowercase, uppercase);
        assertEquals(lowercase.hashCode(), uppercase.hashCode());
    }

    @Test
    void rejectsNull() {
        assertThrows(
                NullPointerException.class,
                () -> new Hash256(null)
        );
    }

    @Test
    void rejectsInvalidValues() {
        List<String> invalidValues = List.of(
                "",
                "*",
                "0x" + "a".repeat(63),          // Too short
                "0x" + "a".repeat(65),          // Too long
                "0x" + "a".repeat(63) + "g",    // Non-hex character
                "a".repeat(64),                // Missing prefix
                " " + VALID_HASH,              // Leading whitespace
                VALID_HASH + " ",              // Trailing whitespace
                "0x" + "a".repeat(31) + " "
                        + "a".repeat(32),       // Internal whitespace
                VALID_HASH + "\n",
                "\t" + VALID_HASH
        );

        for (String value : invalidValues) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Hash256(value),
                    () -> "Expected rejection for: [" + value + "]"
            );
        }
    }
}