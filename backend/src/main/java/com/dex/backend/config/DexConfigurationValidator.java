package com.dex.backend.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

@Component
public class DexConfigurationValidator implements InitializingBean {

    private static final Pattern ADDRESS =
            Pattern.compile("^0x[0-9a-f]{40}$");

    private static final Pattern PRIVATE_KEY =
            Pattern.compile("^(0x)?[0-9a-fA-F]{64}$");

    private final DexProperties properties;

    public DexConfigurationValidator(DexProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validateIndexer();
        validateCors();

        if (properties.blockchain().mode() == BlockchainMode.ETHEREUM) {
            validateEthereum();
        }
    }

    private void validateIndexer() {
        Duration interval = properties.indexer().pollInterval();

        require(
                !interval.isZero() && !interval.isNegative(),
                "DEX_INDEXER_POLL_INTERVAL must be positive"
        );
    }

    private void validateCors() {
        for (URI origin : properties.api().corsOrigins()) {
            String path = origin.getRawPath();
            int port = origin.getPort();

            require(
                    origin.isAbsolute()
                            && ("http".equals(origin.getScheme())
                            || "https".equals(origin.getScheme()))
                            && origin.getHost() != null
                            && origin.getUserInfo() == null
                            && (path == null || path.isEmpty())
                            && origin.getRawQuery() == null
                            && origin.getRawFragment() == null
                            && (port == -1 || (port >= 1 && port <= 65_535)),
                    "DEX_CORS_ORIGINS must contain only HTTP(S) origins "
                            + "with a host and optional port"
            );
        }
    }

    private void validateEthereum() {
        DexProperties.Blockchain blockchain = properties.blockchain();

        require(
                blockchain.rpcUrl() != null
                        && blockchain.rpcUrl().isAbsolute()
                        && ("http".equals(blockchain.rpcUrl().getScheme())
                        || "https".equals(blockchain.rpcUrl().getScheme())),
                "DEX_RPC_URL must be an absolute HTTP(S) URL in Ethereum mode"
        );

        require(
                blockchain.expectedChainId() != null
                        && blockchain.expectedChainId().signum() > 0,
                "DEX_EXPECTED_CHAIN_ID must be positive in Ethereum mode"
        );

        require(
                StringUtils.hasText(blockchain.dexAddress())
                        && ADDRESS.matcher(blockchain.dexAddress()).matches(),
                "DEX_CONTRACT_ADDRESS must be a normalized lowercase address"
        );

        require(
                blockchain.deploymentBlock() != null
                        && blockchain.deploymentBlock().signum() >= 0,
                "DEX_DEPLOYMENT_BLOCK must be non-negative in Ethereum mode"
        );

        if (properties.matcher().enabled()) {
            String privateKey = properties.executor().privateKey();

            require(
                    StringUtils.hasText(privateKey)
                            && PRIVATE_KEY.matcher(privateKey).matches(),
                    "DEX_EXECUTOR_PRIVATE_KEY is required when Ethereum matching is enabled"
            );
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(
                    "Invalid DEX configuration: " + message
            );
        }
    }
}
