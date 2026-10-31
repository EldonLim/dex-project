package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.config.DexProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "dex.blockchain",
        name = "mode",
        havingValue = "ETHEREUM"
)
public class EthereumBlockchainConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(Web3j.class)
    Web3j ethereumWeb3j(DexProperties properties) {
        return Web3j.build(new HttpService(
                properties.blockchain().rpcUrl().toString()
        ));
    }

    @Bean
    BlockchainGateway ethereumBlockchainGateway(
            Web3j web3j,
            DexProperties properties
    ) {
        return EthereumBlockchainGateway.fromProperties(web3j, properties);
    }
}
