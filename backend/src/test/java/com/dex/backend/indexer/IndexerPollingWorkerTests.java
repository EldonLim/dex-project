package com.dex.backend.indexer;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.indexer.projection.EventProjectionException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexerPollingWorkerTests {

    private static final String DEPLOYMENT_ID = "worker-test";

    private final IndexingBatchCoordinator coordinator = mock(IndexingBatchCoordinator.class);
    private final IndexerOperationalStateService operationalStateService =
            mock(IndexerOperationalStateService.class);

    @Test
    void oneInvocationProcessesOneCoordinatorBatch() {
        when(coordinator.indexNextBatch()).thenReturn(result(IndexingBatchStatus.UP_TO_DATE));
        IndexerPollingWorker worker = worker();

        assertTrue(worker.pollOnce());

        verify(coordinator, times(1)).indexNextBatch();
    }

    @Test
    void overlappingInvocationIsSkipped() throws Exception {
        CountDownLatch enteredCoordinator = new CountDownLatch(1);
        CountDownLatch releaseCoordinator = new CountDownLatch(1);

        when(coordinator.indexNextBatch()).thenAnswer(invocation -> {
            enteredCoordinator.countDown();
            assertTrue(releaseCoordinator.await(5, TimeUnit.SECONDS));
            return result(IndexingBatchStatus.UP_TO_DATE);
        });

        IndexerPollingWorker worker = worker();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Boolean> firstInvocation = executor.submit(worker::pollOnce);
            assertTrue(enteredCoordinator.await(5, TimeUnit.SECONDS));

            assertFalse(worker.pollOnce());

            releaseCoordinator.countDown();
            assertTrue(firstInvocation.get(5, TimeUnit.SECONDS));
            verify(coordinator, times(1)).indexNextBatch();
        } finally {
            releaseCoordinator.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retryableGatewayFailureIsRecordedAndCanBeRetried() {
        when(coordinator.indexNextBatch())
                .thenThrow(new BlockchainGatewayException("RPC unavailable", true))
                .thenReturn(result(IndexingBatchStatus.UP_TO_DATE));
        when(operationalStateService.recordTransientFailure(
                eq(DEPLOYMENT_ID),
                contains("RPC unavailable")
        )).thenReturn(true);
        IndexerPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertTrue(worker.pollOnce());

        verify(coordinator, times(2)).indexNextBatch();
        verify(operationalStateService, never()).pause(
                eq(DEPLOYMENT_ID),
                contains("RPC unavailable")
        );
    }

    @Test
    void safetyFailurePausesAndHaltsFuturePolling() {
        when(coordinator.indexNextBatch()).thenThrow(
                new IndexerSafetyException("checkpoint hash mismatch")
        );
        when(operationalStateService.pause(
                eq(DEPLOYMENT_ID),
                contains("checkpoint hash mismatch")
        )).thenReturn(true);
        IndexerPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertFalse(worker.pollOnce());

        verify(coordinator, times(1)).indexNextBatch();
        verify(operationalStateService).pause(
                eq(DEPLOYMENT_ID),
                contains("checkpoint hash mismatch")
        );
    }

    @Test
    void projectionFailurePausesAndHaltsFuturePolling() {
        when(coordinator.indexNextBatch()).thenThrow(
                new EventProjectionException("confirmed event contradicts projection")
        );
        when(operationalStateService.pause(
                eq(DEPLOYMENT_ID),
                contains("contradicts projection")
        )).thenReturn(true);
        IndexerPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertFalse(worker.pollOnce());

        verify(coordinator, times(1)).indexNextBatch();
        verify(operationalStateService).pause(
                eq(DEPLOYMENT_ID),
                contains("contradicts projection")
        );
    }

    @Test
    void nonRetryableGatewayFailurePausesAndHaltsFuturePolling() {
        when(coordinator.indexNextBatch()).thenThrow(
                new BlockchainGatewayException("invalid RPC response", false)
        );
        when(operationalStateService.pause(
                eq(DEPLOYMENT_ID),
                contains("invalid RPC response")
        )).thenReturn(false);
        IndexerPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertFalse(worker.pollOnce());

        verify(coordinator, times(1)).indexNextBatch();
        verify(operationalStateService).pause(
                eq(DEPLOYMENT_ID),
                contains("invalid RPC response")
        );
    }

    @Test
    void storedPausedStatusHaltsFuturePolling() {
        when(coordinator.indexNextBatch()).thenReturn(result(IndexingBatchStatus.PAUSED));
        IndexerPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertFalse(worker.pollOnce());

        verify(coordinator, times(1)).indexNextBatch();
    }

    @Test
    void unexpectedRuntimeFailureIsRecordedAndCanBeRetried() {
        when(coordinator.indexNextBatch())
                .thenThrow(new IllegalStateException("database temporarily unavailable"))
                .thenReturn(result(IndexingBatchStatus.UP_TO_DATE));
        when(operationalStateService.recordTransientFailure(
                eq(DEPLOYMENT_ID),
                contains("database temporarily unavailable")
        )).thenReturn(true);
        IndexerPollingWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertTrue(worker.pollOnce());

        verify(coordinator, times(2)).indexNextBatch();
        verify(operationalStateService).recordTransientFailure(
                eq(DEPLOYMENT_ID),
                contains("database temporarily unavailable")
        );
    }

    private IndexerPollingWorker worker() {
        return new IndexerPollingWorker(
                coordinator,
                operationalStateService,
                new DexProperties(
                        new DexProperties.Blockchain(
                                BlockchainMode.MOCK,
                                DEPLOYMENT_ID,
                                null,
                                null,
                                null,
                                null
                        ),
                        new DexProperties.Indexer(true, Duration.ofSeconds(2), 0, 500),
                        new DexProperties.Matcher(false),
                        new DexProperties.Executor(null),
                        new DexProperties.Api(List.of(URI.create("http://localhost:5173")))
                )
        );
    }

    private static IndexingBatchResult result(IndexingBatchStatus status) {
        return IndexingBatchResult.withoutRange(status);
    }
}
