package com.dex.backend.config;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DexConfigurationValidatorTests {

    private static final URI RPC_URL = URI.create("http://localhost:8545");
    private static final BigInteger CHAIN_ID = BigInteger.valueOf(31_337);
    private static final String CONTRACT_ADDRESS =
            "0x1111111111111111111111111111111111111111";
    private static final String PRIVATE_KEY = "1".repeat(64);
    private static final List<URI> CORS_ORIGINS =
            List.of(URI.create("http://localhost:5173"));

    @Test
    void mockConfigurationSucceedsWithoutRpcValues() {
        DexProperties properties = properties(
                BlockchainMode.MOCK,
                null,
                null,
                null,
                null,
                false,
                null,
                Duration.ofSeconds(2),
                CORS_ORIGINS
        );

        assertDoesNotThrow(
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void completeEthereumConfigurationSucceeds() {
        DexProperties properties = ethereumProperties(true, PRIVATE_KEY);

        assertDoesNotThrow(
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void ethereumModeWithoutRpcUrlThrows() {
        DexProperties properties = properties(
                BlockchainMode.ETHEREUM,
                null,
                CHAIN_ID,
                CONTRACT_ADDRESS,
                BigInteger.ZERO,
                false,
                null,
                Duration.ofSeconds(2),
                CORS_ORIGINS
        );

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void ethereumMatchingWithoutPrivateKeyThrows() {
        DexProperties properties = ethereumProperties(true, null);

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void zeroPollIntervalThrows() {
        DexProperties properties = properties(
                BlockchainMode.MOCK,
                null,
                null,
                null,
                null,
                false,
                null,
                Duration.ZERO,
                CORS_ORIGINS
        );

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void wildcardCorsOriginThrows() {
        DexProperties properties = properties(
                BlockchainMode.MOCK,
                null,
                null,
                null,
                null,
                false,
                null,
                Duration.ofSeconds(2),
                List.of(URI.create("*"))
        );

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    private static DexProperties ethereumProperties(
            boolean matcherEnabled,
            String privateKey
    ) {
        return properties(
                BlockchainMode.ETHEREUM,
                RPC_URL,
                CHAIN_ID,
                CONTRACT_ADDRESS,
                BigInteger.ZERO,
                matcherEnabled,
                privateKey,
                Duration.ofSeconds(2),
                CORS_ORIGINS
        );
    }

    private static DexProperties properties(
            BlockchainMode mode,
            URI rpcUrl,
            BigInteger expectedChainId,
            String dexAddress,
            BigInteger deploymentBlock,
            boolean matcherEnabled,
            String privateKey,
            Duration pollInterval,
            List<URI> corsOrigins
    ) {
        return new DexProperties(
                new DexProperties.Blockchain(
                        mode,
                        "test-deployment",
                        rpcUrl,
                        expectedChainId,
                        dexAddress,
                        deploymentBlock
                ),
                new DexProperties.Indexer(true, pollInterval, 0, 500),
                new DexProperties.Matcher(matcherEnabled),
                new DexProperties.Executor(privateKey),
                new DexProperties.Api(corsOrigins)
        );
    }
}
