package com.dex.backend.blockchain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EthereumAddressTests {

    private static final String VALID_ADDRESS = "0x" + "ab".repeat(20);

    @Test
    void normalizesUppercaseInput() {
        EthereumAddress address =
                new EthereumAddress("0X" + "AB".repeat(20));

        assertEquals(VALID_ADDRESS, address.value());
        assertEquals(VALID_ADDRESS, address.toString());
    }

    @Test
    void preservesAlreadyNormalizedInput() {
        EthereumAddress address = new EthereumAddress(VALID_ADDRESS);

        assertEquals(VALID_ADDRESS, address.value());
    }

    @Test
    void normalizedValuesAreEqual() {
        EthereumAddress lowercase = new EthereumAddress(VALID_ADDRESS);
        EthereumAddress uppercase =
                new EthereumAddress("0x" + "AB".repeat(20));

        assertEquals(lowercase, uppercase);
        assertEquals(lowercase.hashCode(), uppercase.hashCode());
    }

    @Test
    void rejectsNull() {
        assertThrows(
                NullPointerException.class,
                () -> new EthereumAddress(null)
        );
    }

    @Test
    void rejectsInvalidValues() {
        List<String> invalidValues = List.of(
                "",
                "*",
                "0x" + "a".repeat(39),          // Too short
                "0x" + "a".repeat(41),          // Too long
                "0x" + "a".repeat(39) + "g",    // Non-hex character
                "a".repeat(40),                // Missing prefix
                " " + VALID_ADDRESS,           // Leading whitespace
                VALID_ADDRESS + " ",           // Trailing whitespace
                "0x" + "a".repeat(19) + " "
                        + "a".repeat(20),       // Internal whitespace
                VALID_ADDRESS + "\n",
                "\t" + VALID_ADDRESS
        );

        for (String value : invalidValues) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EthereumAddress(value),
                    () -> "Expected rejection for: [" + value + "]"
            );
        }
    }
}