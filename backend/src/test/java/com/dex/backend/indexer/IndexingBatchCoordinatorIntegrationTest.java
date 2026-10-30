package com.dex.backend.indexer;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.event.EventMetadata;
import com.dex.backend.blockchain.event.MarketRegisteredEvent;
import com.dex.backend.blockchain.event.OrderPlacedEvent;
import com.dex.backend.blockchain.mock.MockBlockchainFixtures;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.indexer.projection.EventProjectionException;
import com.dex.backend.order.OrderSide;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "dex.blockchain.deployment-id=indexer-batch-test",
        "dex.indexer.block-batch-size=2",
        "dex.indexer.confirmation-depth=0"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class IndexingBatchCoordinatorIntegrationTest {

    private static final String DEPLOYMENT_ID = "indexer-batch-test";

    @Autowired
    private IndexingBatchCoordinator coordinator;

    @Autowired
    private IndexingBatchTransactionService transactionService;

    @Autowired
    private ChainDeploymentRepository deploymentRepository;

    @Autowired
    private IndexerCheckpointRepository checkpointRepository;

    @Autowired
    private MarketRepository marketRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void indexesBoundedBatchesAndResumesFromTheCheckpoint() {
        IndexingBatchResult first = coordinator.indexNextBatch();

        assertEquals(IndexingBatchStatus.PROCESSED, first.status());
        assertEquals(
                new BlockRange(BigInteger.valueOf(100), BigInteger.valueOf(101)),
                first.range().orElseThrow()
        );
        assertEquals(1, first.appliedEvents());
        assertEquals(0, first.replayedEvents());
        assertTrue(marketRepository.existsById(
                new MarketEntityId(DEPLOYMENT_ID, MockBlockchainFixtures.MARKET_ID)
        ));

        IndexerCheckpointEntity firstCheckpoint = checkpointRepository.findById(DEPLOYMENT_ID)
                .orElseThrow();
        assertEquals(BigInteger.valueOf(101), firstCheckpoint.getLastProcessedBlockNumber());
        assertEquals(MockBlockchainFixtures.BLOCK_101.hash(), firstCheckpoint.getLastProcessedBlockHash());
        assertEquals(BigInteger.valueOf(103), firstCheckpoint.getLatestObservedBlockNumber());

        IndexingBatchResult second = coordinator.indexNextBatch();

        assertEquals(IndexingBatchStatus.PROCESSED, second.status());
        assertEquals(
                new BlockRange(BigInteger.valueOf(102), BigInteger.valueOf(103)),
                second.range().orElseThrow()
        );
        assertEquals(2, second.appliedEvents());
        assertTrue(orderRepository.existsById(
                new OrderEntityId(DEPLOYMENT_ID, MockBlockchainFixtures.ALICE_ORDER_ID)
        ));
        assertTrue(orderRepository.existsById(
                new OrderEntityId(DEPLOYMENT_ID, MockBlockchainFixtures.BOB_ORDER_ID)
        ));

        IndexerCheckpointEntity completedCheckpoint = checkpointRepository.findById(DEPLOYMENT_ID)
                .orElseThrow();
        assertEquals(BigInteger.valueOf(103), completedCheckpoint.getLastProcessedBlockNumber());
        assertEquals(MockBlockchainFixtures.BLOCK_103.hash(), completedCheckpoint.getLastProcessedBlockHash());
        assertEquals(3, processedEventCount(DEPLOYMENT_ID));

        IndexingBatchResult third = coordinator.indexNextBatch();
        assertEquals(IndexingBatchStatus.UP_TO_DATE, third.status());
        assertTrue(third.range().isEmpty());

        ChainDeploymentEntity deployment = deploymentRepository.findById(DEPLOYMENT_ID).orElseThrow();
        assertEquals(MockBlockchainFixtures.CHAIN_ID, deployment.getChainId());
        assertEquals(MockBlockchainFixtures.DEX_ADDRESS, deployment.getDexAddress());

        jdbcTemplate.update(
                "UPDATE indexer_checkpoints SET last_processed_block_hash = ? WHERE deployment_id = ?",
                "0x" + "f".repeat(64),
                DEPLOYMENT_ID
        );

        assertThrows(IndexerSafetyException.class, coordinator::indexNextBatch);
        assertEquals(3, processedEventCount(DEPLOYMENT_ID));
    }

    @Test
    void projectionFailureRollsBackTheWholeBatchAndCheckpoint() {
        String deploymentId = "atomic-batch-test";
        EthereumAddress dexAddress = address('8');
        Hash256 deploymentHash = hash(7000);
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.MOCK,
                BigInteger.valueOf(70_000),
                dexAddress,
                dexAddress,
                BigInteger.valueOf(100),
                deploymentHash
        ));

        MarketRegisteredEvent market = new MarketRegisteredEvent(
                metadata(deploymentId, dexAddress, 100, 7001),
                BigInteger.ONE,
                address('1'),
                address('2'),
                "BASE",
                "QUOTE",
                18,
                6,
                BigInteger.TEN
        );
        OrderPlacedEvent orderWithMissingMarket = new OrderPlacedEvent(
                metadata(deploymentId, dexAddress, 101, 7002),
                BigInteger.ONE,
                BigInteger.valueOf(999),
                address('3'),
                OrderSide.BUY,
                BigInteger.TEN,
                BigInteger.ONE
        );
        BlockRange range = new BlockRange(BigInteger.valueOf(100), BigInteger.valueOf(101));
        BlockReference processedBlock = new BlockReference(
                BigInteger.valueOf(101),
                hash(8101),
                Instant.parse("2026-03-01T00:01:41Z")
        );

        assertThrows(
                EventProjectionException.class,
                () -> transactionService.applyBatch(
                        deploymentId,
                        range,
                        processedBlock,
                        processedBlock,
                        List.of(orderWithMissingMarket, market)
                )
        );

        assertFalse(marketRepository.existsById(new MarketEntityId(deploymentId, BigInteger.ONE)));
        assertFalse(checkpointRepository.existsById(deploymentId));
        assertEquals(0, processedEventCount(deploymentId));
    }

    private int processedEventCount(String deploymentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE deployment_id = ?",
                Integer.class,
                deploymentId
        );
        return count == null ? 0 : count;
    }

    private static EventMetadata metadata(
            String deploymentId,
            EthereumAddress dexAddress,
            long blockNumber,
            long transactionNumber
    ) {
        return new EventMetadata(
                deploymentId,
                dexAddress,
                hash(transactionNumber),
                hash(8000 + blockNumber),
                new ChainPosition(
                        BigInteger.valueOf(blockNumber),
                        BigInteger.ZERO,
                        BigInteger.ZERO
                ),
                Instant.parse("2026-03-01T00:00:00Z").plusSeconds(blockNumber)
        );
    }

    private static EthereumAddress address(char value) {
        return new EthereumAddress("0x" + String.valueOf(value).repeat(40));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + "%064x".formatted(value));
    }
}
