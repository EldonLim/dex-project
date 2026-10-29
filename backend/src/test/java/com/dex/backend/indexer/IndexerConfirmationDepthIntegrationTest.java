package com.dex.backend.indexer;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.mock.MockBlockchainFixtures;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "dex.blockchain.deployment-id=indexer-confirmation-test",
        "dex.indexer.block-batch-size=500",
        "dex.indexer.confirmation-depth=1"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class IndexerConfirmationDepthIntegrationTest {

    private static final String DEPLOYMENT_ID = "indexer-confirmation-test";

    @Autowired
    private IndexingBatchCoordinator coordinator;

    @Autowired
    private IndexerCheckpointRepository checkpointRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void leavesTheUnconfirmedHeadBlockUnprocessed() {
        IndexingBatchResult result = coordinator.indexNextBatch();

        assertEquals(IndexingBatchStatus.PROCESSED, result.status());
        assertEquals(
                new BlockRange(BigInteger.valueOf(100), BigInteger.valueOf(102)),
                result.range().orElseThrow()
        );
        assertEquals(2, result.appliedEvents());

        IndexerCheckpointEntity checkpoint = checkpointRepository.findById(DEPLOYMENT_ID)
                .orElseThrow();
        assertEquals(BigInteger.valueOf(102), checkpoint.getLastProcessedBlockNumber());
        assertEquals(BigInteger.valueOf(103), checkpoint.getLatestObservedBlockNumber());
        assertTrue(orderRepository.existsById(
                new OrderEntityId(DEPLOYMENT_ID, MockBlockchainFixtures.ALICE_ORDER_ID)
        ));
        assertFalse(orderRepository.existsById(
                new OrderEntityId(DEPLOYMENT_ID, MockBlockchainFixtures.BOB_ORDER_ID)
        ));

        assertEquals(IndexingBatchStatus.UP_TO_DATE, coordinator.indexNextBatch().status());
    }
}
