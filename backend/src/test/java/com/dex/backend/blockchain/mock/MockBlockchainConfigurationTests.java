package com.dex.backend.blockchain.mock;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.config.DexProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockBlockchainConfigurationTests {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MockBlockchainConfiguration.class)
                    .withBean(
                            DexProperties.class,
                            MockBlockchainConfigurationTests::properties
                    );

    @Test
    void mockModeCreatesExactlyOneGateway() {
        contextRunner
                .withPropertyValues("dex.blockchain.mode=MOCK")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BlockchainGateway.class);
                    assertThat(context).hasSingleBean(MockBlockchainGateway.class);

                    BlockchainGateway gateway =
                            context.getBean(BlockchainGateway.class);

                    assertThat(gateway).isInstanceOf(MockBlockchainGateway.class);
                    assertThat(gateway.getChainIdentity().deploymentId())
                            .isEqualTo("configuration-test");
                });
    }

    @Test
    void ethereumModeDoesNotCreateMockGateway() {
        contextRunner
                .withPropertyValues("dex.blockchain.mode=ETHEREUM")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(MockBlockchainGateway.class);
                    assertThat(context).doesNotHaveBean(BlockchainGateway.class);
                });
    }

    @Test
    void missingModeDoesNotCreateMockGateway() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MockBlockchainGateway.class);
            assertThat(context).doesNotHaveBean(BlockchainGateway.class);
        });
    }

    private static DexProperties properties() {
        DexProperties properties =
                mock(DexProperties.class, RETURNS_DEEP_STUBS);

        when(properties.blockchain().deploymentId())
                .thenReturn("configuration-test");

        return properties;
    }
}