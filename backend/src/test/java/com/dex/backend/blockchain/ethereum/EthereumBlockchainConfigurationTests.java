package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EthereumBlockchainConfigurationTests {

    private static final String PRIVATE_KEY = "0x" + "0".repeat(63) + "1";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            EthereumBlockchainConfiguration.class
                    )
                    .withBean(Web3j.class, () -> mock(Web3j.class));

    @Test
    void ethereumModeCreatesExactlyOneCompleteGateway() {
        contextRunner
                .withBean(DexProperties.class, () -> properties(PRIVATE_KEY))
                .withPropertyValues("dex.blockchain.mode=ETHEREUM")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BlockchainGateway.class);
                    assertThat(context)
                            .hasSingleBean(EthereumBlockchainGateway.class);
                    assertThat(context.getBean(BlockchainGateway.class)
                            .getExecutorAddress().value())
                            .isEqualTo(
                                    "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
                            );
                });
    }

    @Test
    void mockOrMissingModeDoesNotCreateEthereumGateway() {
        contextRunner
                .withBean(DexProperties.class, () -> properties(null))
                .withPropertyValues("dex.blockchain.mode=MOCK")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(BlockchainGateway.class));

        contextRunner
                .withBean(DexProperties.class, () -> properties(null))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(BlockchainGateway.class));
    }

    @Test
    void indexingOnlyEthereumModeCanStartWithoutExecutorCredentials() {
        contextRunner
                .withBean(DexProperties.class, () -> properties(null))
                .withPropertyValues("dex.blockchain.mode=ETHEREUM")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BlockchainGateway gateway = context.getBean(
                            BlockchainGateway.class
                    );
                    assertThat(gateway)
                            .isInstanceOf(EthereumBlockchainGateway.class);
                    assertThatThrownBy(gateway::getExecutorAddress)
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    private static DexProperties properties(String privateKey) {
        DexProperties properties = mock(
                DexProperties.class,
                RETURNS_DEEP_STUBS
        );
        when(properties.blockchain().mode()).thenReturn(BlockchainMode.ETHEREUM);
        when(properties.blockchain().deploymentId())
                .thenReturn("configuration-test");
        when(properties.blockchain().expectedChainId())
                .thenReturn(BigInteger.valueOf(31337));
        when(properties.blockchain().dexAddress())
                .thenReturn("0x" + "d".repeat(40));
        when(properties.blockchain().marketRegistryAddress())
                .thenReturn("0x" + "c".repeat(40));
        when(properties.blockchain().deploymentBlock())
                .thenReturn(BigInteger.valueOf(100));
        when(properties.executor().privateKey()).thenReturn(privateKey);
        return properties;
    }
}
