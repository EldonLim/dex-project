package com.dex.backend.api.status;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.indexer.IndexerPollingWorker;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusQueryServiceTests {

    private static final String DEPLOYMENT_ID = "status-test";
    private static final Instant SYNCED_AT = Instant.parse("2026-01-01T12:00:00Z");

    private final ChainDeploymentRepository deploymentRepository =
            mock(ChainDeploymentRepository.class);
    private final IndexerCheckpointRepository checkpointRepository =
            mock(IndexerCheckpointRepository.class);
    private final IndexerPollingWorker pollingWorker = mock(IndexerPollingWorker.class);

    @BeforeEach
    void defaultToUninitializedState() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.empty());
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.empty());
    }

    @Test
    void disabledIndexerIsDegradedAndTakesStatePrecedence() {
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 110, SYNCED_AT, true)
        ));
        when(pollingWorker.isRunning()).thenReturn(true);
        when(pollingWorker.isHalted()).thenReturn(true);

        BackendStatusResponse response = service(false, false, 0, true).getStatus();

        assertEquals(BackendOperationalStatus.DEGRADED, response.status());
        assertEquals(IndexerStatus.DISABLED, response.indexer().state());
        assertFalse(response.indexer().enabled());
        assertTrue(response.indexer().paused());
    }

    @Test
    void absentDeploymentAndCheckpointReportStartingWithNullRuntimeFields() {
        BackendStatusResponse response = service(true, false, 0, false).getStatus();

        assertEquals(BackendOperationalStatus.STARTING, response.status());
        assertEquals(BlockchainMode.MOCK, response.blockchainMode());
        assertEquals(DEPLOYMENT_ID, response.deployment().deploymentId());
        assertFalse(response.deployment().registered());
        assertNull(response.deployment().chainId());
        assertEquals(IndexerStatus.STARTING, response.indexer().state());
        assertNull(response.indexer().indexingLagBlocks());
        assertEquals(MatcherStatus.DISABLED, response.matcher().state());
    }

    @Test
    void runningWorkerReportsRunningBeforeStoredRetryState() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment()));
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 110, SYNCED_AT, false, Instant.parse("2026-01-01T12:01:00Z"))
        ));
        when(pollingWorker.isRunning()).thenReturn(true);

        BackendStatusResponse response = service(true, false, 0, true).getStatus();

        assertEquals(BackendOperationalStatus.HEALTHY, response.status());
        assertEquals(IndexerStatus.RUNNING, response.indexer().state());
    }

    @Test
    void eligibleConfirmedLagReportsSyncing() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment()));
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 105, SYNCED_AT, false)
        ));

        BackendStatusResponse response = service(true, false, 2, false).getStatus();

        assertEquals(BackendOperationalStatus.HEALTHY, response.status());
        assertEquals(IndexerStatus.SYNCING, response.indexer().state());
        assertEquals("3", response.indexer().indexingLagBlocks());
    }

    @Test
    void confirmationDepthClampsEligibleLagAtZeroAndReportsUpToDate() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment()));
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(104, 105, SYNCED_AT, false)
        ));

        BackendStatusResponse response = service(true, false, 2, false).getStatus();

        assertEquals(BackendOperationalStatus.HEALTHY, response.status());
        assertEquals(IndexerStatus.UP_TO_DATE, response.indexer().state());
        assertEquals("0", response.indexer().indexingLagBlocks());
    }

    @Test
    void storedTransientErrorReportsRetryingWithoutExposingItsMessage() {
        Instant failedAt = Instant.parse("2026-01-01T12:05:00Z");
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment()));
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 100, SYNCED_AT, false, failedAt)
        ));

        BackendStatusResponse response = service(true, false, 0, false).getStatus();

        assertEquals(BackendOperationalStatus.DEGRADED, response.status());
        assertEquals(IndexerStatus.RETRYING, response.indexer().state());
        assertEquals(failedAt.toString(), response.indexer().lastErrorAt());
    }

    @Test
    void pausedCheckpointReportsPausedBeforeRunningOrRetrying() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment()));
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 100, SYNCED_AT, true)
        ));
        when(pollingWorker.isRunning()).thenReturn(true);

        BackendStatusResponse response = service(true, false, 0, true).getStatus();

        assertEquals(BackendOperationalStatus.DEGRADED, response.status());
        assertEquals(IndexerStatus.PAUSED, response.indexer().state());
        assertTrue(response.indexer().paused());
    }

    @Test
    void haltedWorkerWithoutCheckpointReportsPaused() {
        when(pollingWorker.isHalted()).thenReturn(true);

        BackendStatusResponse response = service(true, false, 0, true).getStatus();

        assertEquals(BackendOperationalStatus.DEGRADED, response.status());
        assertEquals(IndexerStatus.PAUSED, response.indexer().state());
        assertTrue(response.indexer().paused());
    }

    @Test
    void configuredMatcherReportsNotImplementedAndDegradesOverallStatus() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment()));
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 100, SYNCED_AT, false)
        ));

        BackendStatusResponse response = service(true, true, 0, false).getStatus();

        assertEquals(BackendOperationalStatus.DEGRADED, response.status());
        assertTrue(response.matcher().enabled());
        assertEquals(MatcherStatus.NOT_IMPLEMENTED, response.matcher().state());
    }

    private StatusQueryService service(
            boolean indexerEnabled,
            boolean matcherEnabled,
            int confirmationDepth,
            boolean includeWorker
    ) {
        return new StatusQueryService(
                deploymentRepository,
                checkpointRepository,
                properties(indexerEnabled, matcherEnabled, confirmationDepth),
                includeWorker ? Optional.of(pollingWorker) : Optional.empty()
        );
    }

    private static DexProperties properties(
            boolean indexerEnabled,
            boolean matcherEnabled,
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
                new DexProperties.Matcher(matcherEnabled, Duration.ofSeconds(30)),
                new DexProperties.Executor(null),
                new DexProperties.Api(List.of(URI.create("http://localhost:5173")))
        );
    }

    private static ChainDeploymentEntity deployment() {
        return new ChainDeploymentEntity(
                DEPLOYMENT_ID,
                BlockchainMode.MOCK,
                BigInteger.valueOf(31_337),
                address(1),
                BigInteger.valueOf(90),
                hash(90)
        );
    }

    private static IndexerCheckpointEntity checkpoint(
            long lastIndexed,
            long latestObserved,
            Instant syncedAt,
            boolean paused
    ) {
        return checkpoint(lastIndexed, latestObserved, syncedAt, paused, null);
    }

    private static IndexerCheckpointEntity checkpoint(
            long lastIndexed,
            long latestObserved,
            Instant syncedAt,
            boolean paused,
            Instant lastErrorAt
    ) {
        return new IndexerCheckpointEntity(
                DEPLOYMENT_ID,
                BigInteger.valueOf(lastIndexed),
                hash(lastIndexed),
                BigInteger.valueOf(latestObserved),
                hash(latestObserved),
                syncedAt,
                lastErrorAt,
                lastErrorAt == null && !paused ? null : "sensitive stored failure",
                paused,
                paused ? "sensitive pause reason" : null
        );
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
