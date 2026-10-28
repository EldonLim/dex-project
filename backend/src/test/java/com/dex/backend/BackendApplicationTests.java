package com.dex.backend;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.mock.MockBlockchainGateway;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

	@Autowired
	private BlockchainGateway blockchainGateway;

	@Test
	void usesMockBlockchainGateway() {
		assertInstanceOf(MockBlockchainGateway.class, blockchainGateway);
	}

	@Test
	void contextLoads() {
	}

}
