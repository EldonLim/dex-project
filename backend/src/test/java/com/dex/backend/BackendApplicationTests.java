package com.dex.backend;

import java.time.Duration;

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
import com.dex.backend.matcher.MatcherPollingWorker;

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
		assertTrue(properties.matcher().pollInterval().equals(Duration.ofSeconds(2)));
		assertTrue(properties.matcher().ineligibilityDuration().equals(Duration.ofSeconds(30)));
		assertFalse(applicationContext.containsBean("indexerPollingWorker"));
		assertTrue(applicationContext.getBeansOfType(IndexerPollingWorker.class).isEmpty());
		assertFalse(applicationContext.containsBean("matcherPollingWorker"));
		assertTrue(applicationContext.getBeansOfType(MatcherPollingWorker.class).isEmpty());
	}

}
