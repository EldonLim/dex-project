package com.dex.backend.settlement;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.execution.ExecutionAttemptTransitionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

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

class SettlementExecutorWorkerTests {

    private final SettlementExecutionCoordinator coordinator =
            mock(SettlementExecutionCoordinator.class);

    @Test
    void schedulingUsesTheConfiguredFixedDelay() throws Exception {
        Scheduled scheduled = SettlementExecutorWorker.class
                .getMethod("pollOnSchedule")
                .getAnnotation(Scheduled.class);

        assertEquals(
                "${dex.executor.poll-interval:2s}",
                scheduled.initialDelayString()
        );
        assertEquals(
                "${dex.executor.poll-interval:2s}",
                scheduled.fixedDelayString()
        );
    }

    @Test
    void mapsSuccessfulCycleResultsToRuntimeStates() {
        when(coordinator.runCycle())
                .thenReturn(SettlementExecutorCycleStatus.IDLE)
                .thenReturn(SettlementExecutorCycleStatus.SIGNED)
                .thenReturn(
                        SettlementExecutorCycleStatus
                                .WAITING_FOR_RECONCILIATION
                );
        SettlementExecutorWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertEquals(SettlementExecutorWorkerState.IDLE, worker.getState());
        assertTrue(worker.pollOnce());
        assertEquals(SettlementExecutorWorkerState.SIGNED, worker.getState());
        assertTrue(worker.pollOnce());
        assertEquals(
                SettlementExecutorWorkerState.WAITING_FOR_RECONCILIATION,
                worker.getState()
        );
    }

    @Test
    void overlappingInvocationIsSkipped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(coordinator.runCycle()).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return SettlementExecutorCycleStatus.IDLE;
        });
        SettlementExecutorWorker worker = worker();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Boolean> first = executor.submit(worker::pollOnce);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(worker.isRunning());
            assertEquals(SettlementExecutorWorkerState.RUNNING, worker.getState());
            assertFalse(worker.pollOnce());

            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertFalse(worker.isRunning());
            verify(coordinator, times(1)).runCycle();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retryableFailureCanRecoverOnLaterCycle() {
        when(coordinator.runCycle())
                .thenThrow(new BlockchainGatewayException("RPC down", true))
                .thenReturn(SettlementExecutorCycleStatus.SIGNED);
        SettlementExecutorWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertEquals(SettlementExecutorWorkerState.RETRYING, worker.getState());
        assertFalse(worker.isHalted());
        assertTrue(worker.pollOnce());
        assertEquals(SettlementExecutorWorkerState.SIGNED, worker.getState());
    }

    @Test
    void temporarilyNoncanonicalReceiptRetriesWithoutHalting() {
        when(coordinator.runCycle()).thenThrow(
                new SettlementReceiptRetryException(
                        "Receipt block changed during a reorganization"
                )
        );
        SettlementExecutorWorker worker = worker();

        assertTrue(worker.pollOnce());
        assertEquals(SettlementExecutorWorkerState.RETRYING, worker.getState());
        assertFalse(worker.isHalted());
    }

    @Test
    void workerLogsOnlyFailureTypeAndNeverRawFailureDetails() {
        String sensitiveFailure =
                "private-key=secret signed-transaction=0xdeadbeef";
        when(coordinator.runCycle()).thenThrow(
                new BlockchainGatewayException(sensitiveFailure, true)
        );
        Logger logger = (Logger) LoggerFactory.getLogger(
                SettlementExecutorWorker.class
        );
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertTrue(worker().pollOnce());

            String messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + right);
            assertFalse(messages.contains(sensitiveFailure));
            assertFalse(messages.contains("0xdeadbeef"));
            assertTrue(messages.contains("BlockchainGatewayException"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void nonRetryableGatewayFailureHaltsFutureWork() {
        when(coordinator.runCycle()).thenThrow(
                new BlockchainGatewayException("bad signing config", false)
        );
        assertHaltedAfterFailure(worker());
    }

    @Test
    void lifecycleSafetyFailureHaltsFutureWork() {
        when(coordinator.runCycle()).thenThrow(
                new ExecutionAttemptTransitionException("state changed")
        );
        assertHaltedAfterFailure(worker());
    }

    @Test
    void executorSafetyFailureHaltsFutureWork() {
        when(coordinator.runCycle()).thenThrow(
                new SettlementExecutorSafetyException("identity mismatch")
        );
        assertHaltedAfterFailure(worker());
    }

    private void assertHaltedAfterFailure(SettlementExecutorWorker worker) {
        assertTrue(worker.pollOnce());
        assertTrue(worker.isHalted());
        assertEquals(SettlementExecutorWorkerState.HALTED, worker.getState());
        assertFalse(worker.pollOnce());
        verify(coordinator, times(1)).runCycle();
    }

    private SettlementExecutorWorker worker() {
        return new SettlementExecutorWorker(coordinator, properties());
    }

    private static DexProperties properties() {
        return new DexProperties(
                new DexProperties.Blockchain(
                        BlockchainMode.MOCK,
                        "executor-worker-test",
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
                new DexProperties.Executor(null, Duration.ofSeconds(2)),
                new DexProperties.Api(List.of(
                        URI.create("http://localhost:5173")
                ))
        );
    }
}
