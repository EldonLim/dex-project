package com.dex.backend.indexer;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class IndexerOperationalStateServiceIntegrationTest {

    private static final String DEPLOYMENT_ID = "operational-state-test";
    private static final BigInteger PROCESSED_BLOCK = BigInteger.valueOf(120);
    private static final Hash256 PROCESSED_HASH = hash(120);

    @Autowired
    private IndexerOperationalStateService operationalStateService;

    @Autowired
    private IndexingBatchTransactionService transactionService;

    @Autowired
    private ChainDeploymentRepository deploymentRepository;

    @Autowired
    private IndexerCheckpointRepository checkpointRepository;

    @Test
    void recordsRecoversAndPausesWithoutMovingTheCheckpoint() {
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                DEPLOYMENT_ID,
                BlockchainMode.MOCK,
                BigInteger.valueOf(31_337),
                new EthereumAddress("0x" + "7".repeat(40)),
                BigInteger.valueOf(100),
                hash(100)
        ));
        checkpointRepository.saveAndFlush(new IndexerCheckpointEntity(
                DEPLOYMENT_ID,
                PROCESSED_BLOCK,
                PROCESSED_HASH,
                BigInteger.valueOf(123),
                hash(123),
                Instant.parse("2026-04-01T00:00:00Z"),
                null,
                null,
                false,
                null
        ));

        assertTrue(operationalStateService.recordTransientFailure(
                DEPLOYMENT_ID,
                "  temporary RPC failure  "
        ));

        IndexerCheckpointEntity failed = checkpointRepository.findById(DEPLOYMENT_ID).orElseThrow();
        assertEquals(PROCESSED_BLOCK, failed.getLastProcessedBlockNumber());
        assertEquals(PROCESSED_HASH, failed.getLastProcessedBlockHash());
        assertEquals("temporary RPC failure", failed.getLastErrorMessage());
        assertFalse(failed.isPaused());

        BlockReference latest = new BlockReference(
                BigInteger.valueOf(124),
                hash(124),
                Instant.parse("2026-04-01T00:02:04Z")
        );
        transactionService.recordSuccessfulObservation(DEPLOYMENT_ID, latest);

        IndexerCheckpointEntity recovered = checkpointRepository.findById(DEPLOYMENT_ID).orElseThrow();
        assertNull(recovered.getLastErrorAt());
        assertNull(recovered.getLastErrorMessage());
        assertEquals(PROCESSED_BLOCK, recovered.getLastProcessedBlockNumber());

        assertTrue(operationalStateService.pause(
                DEPLOYMENT_ID,
                "  checkpoint hash mismatch  "
        ));

        IndexerCheckpointEntity paused = checkpointRepository.findById(DEPLOYMENT_ID).orElseThrow();
        assertTrue(paused.isPaused());
        assertEquals("checkpoint hash mismatch", paused.getPauseReason());
        assertEquals("checkpoint hash mismatch", paused.getLastErrorMessage());
        assertEquals(PROCESSED_BLOCK, paused.getLastProcessedBlockNumber());
        assertEquals(PROCESSED_HASH, paused.getLastProcessedBlockHash());
    }

    @Test
    void reportsWhenNoCheckpointExists() {
        assertFalse(operationalStateService.recordTransientFailure(
                "missing-operational-checkpoint",
                "temporary failure"
        ));
        assertFalse(operationalStateService.pause(
                "missing-operational-checkpoint",
                "unsafe chain identity"
        ));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + "%064x".formatted(value));
    }
}
