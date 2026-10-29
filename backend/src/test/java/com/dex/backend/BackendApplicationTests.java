package com.dex.backend;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.mock.MockBlockchainGateway;
import com.dex.backend.config.DexProperties;
import com.dex.backend.indexer.IndexerPollingWorker;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

	@Autowired
	private BlockchainGateway blockchainGateway;

	@Autowired
	private DexProperties properties;

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void usesMockBlockchainGateway() {
		assertInstanceOf(MockBlockchainGateway.class, blockchainGateway);
	}

	@Test
	void contextLoads() {
	}

	@Test
	void disablesScheduledIndexingInTheTestProfile() {
		assertFalse(properties.indexer().enabled());
		assertFalse(applicationContext.containsBean("indexerPollingWorker"));
		assertTrue(applicationContext.getBeansOfType(IndexerPollingWorker.class).isEmpty());
	}

}
