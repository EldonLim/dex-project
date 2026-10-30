package com.dex.backend.matcher;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatcherPollingWorkerTests {

    private static final String DEPLOYMENT_ID = "matcher-worker-test";

    private final MatcherCycleCoordinator coordinator =
            mock(MatcherCycleCoordinator.class);

    @Test
    void mapsSuccessfulCycleResultsToSafeRuntimeStates() {
        MatcherPollingWorker worker = worker();
        when(coordinator.runCycle())
                .thenReturn(MatcherCycleStatus.WAITING_FOR_INDEXER)
                .thenReturn(MatcherCycleStatus.IDLE)
                .thenReturn(MatcherCycleStatus.TEMPORARILY_INELIGIBLE)
                .thenReturn(MatcherCycleStatus.STALE)
                .thenReturn(MatcherCycleStatus.RESERVED)
                .thenReturn(MatcherCycleStatus.ALREADY_RESERVED);

        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.WAITING_FOR_INDEXER, worker.getState());
        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.IDLE, worker.getState());
        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.IDLE, worker.getState());
        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.IDLE, worker.getState());
        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.RESERVED, worker.getState());
        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.RESERVED, worker.getState());

        verify(coordinator, times(6)).runCycle();
    }

    @Test
    void overlappingInvocationIsSkipped() throws Exception {
        CountDownLatch enteredCoordinator = new CountDownLatch(1);
        CountDownLatch releaseCoordinator = new CountDownLatch(1);
        when(coordinator.runCycle()).thenAnswer(invocation -> {
            enteredCoordinator.countDown();
            assertTrue(releaseCoordinator.await(5, TimeUnit.SECONDS));
            return MatcherCycleStatus.IDLE;
        });
        MatcherPollingWorker worker = worker();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Boolean> first = executor.submit(worker::pollOnce);
            assertTrue(enteredCoordinator.await(5, TimeUnit.SECONDS));
            assertTrue(worker.isRunning());
            assertEquals(MatcherWorkerState.RUNNING, worker.getState());

            assertFalse(worker.pollOnce());

            releaseCoordinator.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertFalse(worker.isRunning());
            assertEquals(MatcherWorkerState.IDLE, worker.getState());
            verify(coordinator, times(1)).runCycle();
        } finally {
            releaseCoordinator.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retryableFailureRetriesAndSuccessfulCycleClearsRetryState() {
        when(coordinator.runCycle())
                .thenThrow(new BlockchainGatewayException("RPC unavailable", true))
                .thenReturn(MatcherCycleStatus.IDLE);
        MatcherPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.RETRYING, worker.getState());
        assertFalse(worker.isHalted());

        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.IDLE, worker.getState());
        verify(coordinator, times(2)).runCycle();
    }

    @Test
    void nonRetryableGatewayFailureHaltsFutureCycles() {
        when(coordinator.runCycle()).thenThrow(
                new BlockchainGatewayException("invalid chain response", false)
        );
        MatcherPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertTrue(worker.isHalted());
        assertEquals(MatcherWorkerState.HALTED, worker.getState());
        assertFalse(worker.pollOnce());

        verify(coordinator, times(1)).runCycle();
    }

    @Test
    void safetyFailureHaltsFutureCycles() {
        when(coordinator.runCycle()).thenThrow(
                new MatcherSafetyException("deployment identity mismatch")
        );
        MatcherPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertFalse(worker.pollOnce());
        assertTrue(worker.isHalted());
        assertEquals(MatcherWorkerState.HALTED, worker.getState());

        verify(coordinator, times(1)).runCycle();
    }

    @Test
    void unexpectedOperationalFailureRetriesLater() {
        when(coordinator.runCycle())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(MatcherCycleStatus.RESERVED);
        MatcherPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.RETRYING, worker.getState());
        assertTrue(worker.pollOnce());
        assertEquals(MatcherWorkerState.RESERVED, worker.getState());
    }

    private MatcherPollingWorker worker() {
        return new MatcherPollingWorker(coordinator, properties());
    }

    private static DexProperties properties() {
        return new DexProperties(
                new DexProperties.Blockchain(
                        BlockchainMode.MOCK,
                        DEPLOYMENT_ID,
                        null,
                        null,
                        null,
                        null
                ),
                new DexProperties.Indexer(true, Duration.ofSeconds(2), 0, 500),
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
}
