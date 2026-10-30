package com.dex.backend.api.status;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.indexer.IndexerPollingWorker;
import com.dex.backend.matcher.MatcherPollingWorker;
import com.dex.backend.matcher.MatcherWorkerState;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import com.dex.backend.settlement.SettlementExecutorWorker;
import com.dex.backend.settlement.SettlementExecutorWorkerState;
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
    private final MatcherPollingWorker matcherWorker = mock(MatcherPollingWorker.class);
    private final SettlementExecutorWorker executorWorker =
            mock(SettlementExecutorWorker.class);

    @BeforeEach
    void defaultToUninitializedState() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.empty());
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.empty());
        when(executorWorker.getState()).thenReturn(
                SettlementExecutorWorkerState.IDLE
        );
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
        assertNull(response.deployment().marketRegistryAddress());
        assertEquals(IndexerStatus.STARTING, response.indexer().state());
        assertNull(response.indexer().indexingLagBlocks());
        assertEquals(MatcherStatus.DISABLED, response.matcher().state());
        assertEquals(ExecutorStatus.DISABLED, response.executor().state());
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
    void configuredMatcherWithoutWorkerReportsStarting() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment()));
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(
                checkpoint(100, 100, SYNCED_AT, false)
        ));

        BackendStatusResponse response = service(true, true, 0, false).getStatus();

        assertEquals(BackendOperationalStatus.STARTING, response.status());
        assertTrue(response.matcher().enabled());
        assertEquals(MatcherStatus.STARTING, response.matcher().state());
    }

    @Test
    void runningMatcherTakesPrecedenceOverItsStoredState() {
        readyProjection();
        when(matcherWorker.isRunning()).thenReturn(true);
        when(matcherWorker.getState()).thenReturn(MatcherWorkerState.RETRYING);

        BackendStatusResponse response = service(
                true,
                true,
                0,
                false,
                true
        ).getStatus();

        assertEquals(BackendOperationalStatus.HEALTHY, response.status());
        assertEquals(MatcherStatus.RUNNING, response.matcher().state());
    }

    @Test
    void idleAndReservedMatcherStatesAreHealthy() {
        readyProjection();
        when(matcherWorker.getState()).thenReturn(MatcherWorkerState.IDLE);

        BackendStatusResponse idle = service(
                true,
                true,
                0,
                false,
                true
        ).getStatus();
        assertEquals(BackendOperationalStatus.HEALTHY, idle.status());
        assertEquals(MatcherStatus.IDLE, idle.matcher().state());

        when(matcherWorker.getState()).thenReturn(MatcherWorkerState.RESERVED);
        BackendStatusResponse reserved = service(
                true,
                true,
                0,
                false,
                true
        ).getStatus();
        assertEquals(BackendOperationalStatus.HEALTHY, reserved.status());
        assertEquals(MatcherStatus.RESERVED, reserved.matcher().state());
    }

    @Test
    void waitingMatcherReportsStartingWhenIndexerIsOtherwiseHealthy() {
        readyProjection();
        when(matcherWorker.getState()).thenReturn(
                MatcherWorkerState.WAITING_FOR_INDEXER
        );

        BackendStatusResponse response = service(
                true,
                true,
                0,
                false,
                true
        ).getStatus();

        assertEquals(BackendOperationalStatus.STARTING, response.status());
        assertEquals(
                MatcherStatus.WAITING_FOR_INDEXER,
                response.matcher().state()
        );
    }

    @Test
    void retryingAndHaltedMatcherStatesAreDegraded() {
        readyProjection();
        when(matcherWorker.getState()).thenReturn(MatcherWorkerState.RETRYING);
        BackendStatusResponse retrying = service(
                true,
                true,
                0,
                false,
                true
        ).getStatus();
        assertEquals(BackendOperationalStatus.DEGRADED, retrying.status());
        assertEquals(MatcherStatus.RETRYING, retrying.matcher().state());

        when(matcherWorker.isHalted()).thenReturn(true);
        BackendStatusResponse halted = service(
                true,
                true,
                0,
                false,
                true
        ).getStatus();
        assertEquals(BackendOperationalStatus.DEGRADED, halted.status());
        assertEquals(MatcherStatus.HALTED, halted.matcher().state());
    }

    @Test
    void healthyExecutorRuntimeStatesPreserveHealthyOverallStatus() {
        readyProjection();
        when(matcherWorker.getState()).thenReturn(MatcherWorkerState.IDLE);

        for (SettlementExecutorWorkerState state : new SettlementExecutorWorkerState[]{
                SettlementExecutorWorkerState.IDLE,
                SettlementExecutorWorkerState.SIGNED,
                SettlementExecutorWorkerState.WAITING_FOR_RECONCILIATION
        }) {
            when(executorWorker.getState()).thenReturn(state);
            BackendStatusResponse response = service(
                    true,
                    true,
                    0,
                    false,
                    true,
                    true
            ).getStatus();

            assertEquals(BackendOperationalStatus.HEALTHY, response.status());
            assertEquals(state.name(), response.executor().state().name());
        }
    }

    @Test
    void runningExecutorTakesPrecedenceOverStoredState() {
        readyProjection();
        when(matcherWorker.getState()).thenReturn(MatcherWorkerState.IDLE);
        when(executorWorker.isRunning()).thenReturn(true);
        when(executorWorker.getState()).thenReturn(
                SettlementExecutorWorkerState.RETRYING
        );

        BackendStatusResponse response = service(
                true,
                true,
                0,
                false,
                true,
                true
        ).getStatus();

        assertEquals(BackendOperationalStatus.HEALTHY, response.status());
        assertEquals(ExecutorStatus.RUNNING, response.executor().state());
    }

    @Test
    void retryingAndHaltedExecutorStatesAreDegraded() {
        readyProjection();
        when(matcherWorker.getState()).thenReturn(MatcherWorkerState.IDLE);
        when(executorWorker.getState()).thenReturn(
                SettlementExecutorWorkerState.RETRYING
        );

        BackendStatusResponse retrying = service(
                true,
                true,
                0,
                false,
                true,
                true
        ).getStatus();
        assertEquals(BackendOperationalStatus.DEGRADED, retrying.status());
        assertEquals(ExecutorStatus.RETRYING, retrying.executor().state());

        when(executorWorker.isHalted()).thenReturn(true);
        BackendStatusResponse halted = service(
                true,
                true,
                0,
                false,
                true,
                true
        ).getStatus();
        assertEquals(BackendOperationalStatus.DEGRADED, halted.status());
        assertEquals(ExecutorStatus.HALTED, halted.executor().state());
    }

    private StatusQueryService service(
            boolean indexerEnabled,
            boolean matcherEnabled,
            int confirmationDepth,
            boolean includeWorker
    ) {
        return service(
                indexerEnabled,
                matcherEnabled,
                confirmationDepth,
                includeWorker,
                false,
                false
        );
    }

    private StatusQueryService service(
            boolean indexerEnabled,
            boolean matcherEnabled,
            int confirmationDepth,
            boolean includeIndexerWorker,
            boolean includeMatcherWorker
    ) {
        return service(
                indexerEnabled,
                matcherEnabled,
                confirmationDepth,
                includeIndexerWorker,
                includeMatcherWorker,
                includeMatcherWorker
        );
    }

    private StatusQueryService service(
            boolean indexerEnabled,
            boolean matcherEnabled,
            int confirmationDepth,
            boolean includeIndexerWorker,
            boolean includeMatcherWorker,
            boolean includeExecutorWorker
    ) {
        return new StatusQueryService(
                deploymentRepository,
                checkpointRepository,
                properties(indexerEnabled, matcherEnabled, confirmationDepth),
                includeIndexerWorker
                        ? Optional.of(pollingWorker)
                        : Optional.empty(),
                includeMatcherWorker
                        ? Optional.of(matcherWorker)
                        : Optional.empty(),
                includeExecutorWorker
                        ? Optional.of(executorWorker)
                        : Optional.empty()
        );
    }

    private void readyProjection() {
        when(deploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(
                Optional.of(deployment())
        );
        when(checkpointRepository.findById(DEPLOYMENT_ID)).thenReturn(
                Optional.of(checkpoint(100, 100, SYNCED_AT, false))
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
                        matcherEnabled,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30)
                ),
                new DexProperties.Executor(null, Duration.ofSeconds(2)),
                new DexProperties.Api(List.of(URI.create("http://localhost:5173")))
        );
    }

    private static ChainDeploymentEntity deployment() {
        return new ChainDeploymentEntity(
                DEPLOYMENT_ID,
                BlockchainMode.MOCK,
                BigInteger.valueOf(31_337),
                address(1),
                address(2),
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
