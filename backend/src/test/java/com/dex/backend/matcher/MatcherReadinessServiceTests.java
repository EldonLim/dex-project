package com.dex.backend.matcher;

import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatcherReadinessServiceTests {

    private static final String DEPLOYMENT_ID = "readiness-test";

    private final ChainDeploymentRepository deploymentRepository =
            mock(ChainDeploymentRepository.class);
    private final IndexerCheckpointRepository checkpointRepository =
            mock(IndexerCheckpointRepository.class);

    @Test
    void disabledIndexerIsNeverReady() {
        assertFalse(service(false, 0).isReady());

        verify(deploymentRepository, never()).existsById(DEPLOYMENT_ID);
    }

    @Test
    void missingDeploymentOrCheckpointIsNotReady() {
        when(deploymentRepository.existsById(DEPLOYMENT_ID)).thenReturn(false);
        assertFalse(service(true, 0).isReady());

        when(deploymentRepository.existsById(DEPLOYMENT_ID)).thenReturn(true);
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.empty());
        assertFalse(service(true, 0).isReady());
    }

    @Test
    void pausedOrErroredCheckpointIsNotReady() {
        registered();
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 100, true, null)
        ));
        assertFalse(service(true, 0).isReady());

        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 100, false, Instant.parse("2026-01-01T01:00:00Z"))
        ));
        assertFalse(service(true, 0).isReady());
    }

    @Test
    void eligibleLagMustBeZeroAfterConfirmationDepth() {
        registered();
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(102, 105, false, null)
        ));
        assertFalse(service(true, 2).isReady());

        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(103, 105, false, null)
        ));
        assertTrue(service(true, 2).isReady());

        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(104, 105, false, null)
        ));
        assertTrue(service(true, 2).isReady());
    }

    private void registered() {
        when(deploymentRepository.existsById(DEPLOYMENT_ID)).thenReturn(true);
    }

    private MatcherReadinessService service(
            boolean indexerEnabled,
            int confirmationDepth
    ) {
        return new MatcherReadinessService(
                deploymentRepository,
                checkpointRepository,
                properties(indexerEnabled, confirmationDepth)
        );
    }

    private static DexProperties properties(
            boolean indexerEnabled,
            int confirmationDepth
    ) {
        return new DexProperties(
                new DexProperties.Blockchain(
                        BlockchainMode.MOCK,
                        DEPLOYMENT_ID,
                        null,
                        null,
                        null,
                        null
                ),
                new DexProperties.Indexer(
                        indexerEnabled,
                        Duration.ofSeconds(2),
                        confirmationDepth,
                        500
                ),
                new DexProperties.Matcher(
                        true,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30)
                ),
                new DexProperties.Executor(null),
                new DexProperties.Api(List.of(
                        URI.create("http://localhost:5173")
                ))
        );
    }

    private static IndexerCheckpointEntity checkpoint(
            long lastProcessed,
            long latestObserved,
            boolean paused,
            Instant errorAt
    ) {
        return new IndexerCheckpointEntity(
                DEPLOYMENT_ID,
                BigInteger.valueOf(lastProcessed),
                hash(lastProcessed),
                BigInteger.valueOf(latestObserved),
                hash(latestObserved),
                Instant.parse("2026-01-01T00:00:00Z"),
                errorAt,
                errorAt == null ? null : "temporary failure",
                paused,
                paused ? "safety failure" : null
        );
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
