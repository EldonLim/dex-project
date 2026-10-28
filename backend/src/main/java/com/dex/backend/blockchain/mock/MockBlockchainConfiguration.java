package com.dex.backend.blockchain.mock;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.config.DexProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "dex.blockchain",
        name = "mode",
        havingValue = "MOCK"
)
public class MockBlockchainConfiguration {

    @Bean
    BlockchainGateway mockBlockchainGateway(DexProperties properties) {
        return new MockBlockchainGateway(
                properties.blockchain().deploymentId()
        );
    }
}