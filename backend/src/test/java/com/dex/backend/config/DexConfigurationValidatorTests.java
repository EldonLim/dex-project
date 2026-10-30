package com.dex.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:5173/",
            "http://localhost:5173/frontend",
            "http://localhost:5173?source=test",
            "http://localhost:5173#fragment",
            "http://user@localhost:5173",
            "ftp://localhost:5173",
            "http://*.example.com"
    })
    void corsOriginWithNonOriginComponentsThrows(String value) {
        DexProperties properties = properties(
                BlockchainMode.MOCK,
                null,
                null,
                null,
                null,
                false,
                null,
                Duration.ofSeconds(2),
                List.of(URI.create(value))
        );

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void corsOriginWithHttpsAndPortSucceeds() {
        DexProperties properties = properties(
                BlockchainMode.MOCK,
                null,
                null,
                null,
                null,
                false,
                null,
                Duration.ofSeconds(2),
                List.of(URI.create("https://frontend.example:8443"))
        );

        assertDoesNotThrow(
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
                new DexProperties.Matcher(
                        matcherEnabled,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30)
                ),
                new DexProperties.Executor(privateKey),
                new DexProperties.Api(corsOrigins)
        );
    }

    @Test
    void zeroMatcherIneligibilityDurationThrows() {
        DexProperties properties = propertiesWithMatcherDuration(Duration.ZERO);

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void negativeMatcherIneligibilityDurationThrows() {
        DexProperties properties = propertiesWithMatcherDuration(
                Duration.ofSeconds(-1)
        );

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void zeroMatcherPollIntervalThrows() {
        DexProperties properties = propertiesWithMatcherPollInterval(
                Duration.ZERO
        );

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void negativeMatcherPollIntervalThrows() {
        DexProperties properties = propertiesWithMatcherPollInterval(
                Duration.ofSeconds(-1)
        );

        assertThrows(
                IllegalStateException.class,
                () -> new DexConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    private static DexProperties propertiesWithMatcherDuration(
            Duration ineligibilityDuration
    ) {
        return new DexProperties(
                new DexProperties.Blockchain(
                        BlockchainMode.MOCK,
                        "test-deployment",
                        null,
                        null,
                        null,
                        null
                ),
                new DexProperties.Indexer(true, Duration.ofSeconds(2), 0, 500),
                new DexProperties.Matcher(
                        false,
                        Duration.ofSeconds(2),
                        ineligibilityDuration
                ),
                new DexProperties.Executor(null),
                new DexProperties.Api(CORS_ORIGINS)
        );
    }

    private static DexProperties propertiesWithMatcherPollInterval(
            Duration matcherPollInterval
    ) {
        return new DexProperties(
                new DexProperties.Blockchain(
                        BlockchainMode.MOCK,
                        "test-deployment",
                        null,
                        null,
                        null,
                        null
                ),
                new DexProperties.Indexer(
                        true,
                        Duration.ofSeconds(2),
                        0,
                        500
                ),
                new DexProperties.Matcher(
                        false,
                        matcherPollInterval,
                        Duration.ofSeconds(30)
                ),
                new DexProperties.Executor(null),
                new DexProperties.Api(CORS_ORIGINS)
        );
    }
}
