package com.dex.backend.settlement;

import com.dex.backend.matcher.TemporaryOrderIneligibilityRegistry;
import com.dex.backend.persistence.execution.ExecutionAttemptEntity;
import com.dex.backend.persistence.execution.ExecutionAttemptRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementReceiptResolutionServiceTests {

    private static final String DEPLOYMENT_ID = "receipt-test";
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final BigInteger BUY_ORDER_ID = BigInteger.TEN;
    private static final BigInteger SELL_ORDER_ID = BigInteger.valueOf(20);
    private static final BigInteger RECEIPT_BLOCK = BigInteger.valueOf(100);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ExecutionAttemptRepository attemptRepository =
            mock(ExecutionAttemptRepository.class);
    private final IndexerCheckpointRepository checkpointRepository =
            mock(IndexerCheckpointRepository.class);
    private final TemporaryOrderIneligibilityRegistry ineligibilityRegistry =
            mock(TemporaryOrderIneligibilityRegistry.class);
    private final ExecutionAttemptEntity attempt =
            mock(ExecutionAttemptEntity.class);
    private final SettlementReceiptResolutionService service =
            new SettlementReceiptResolutionService(
                    attemptRepository,
                    checkpointRepository,
                    ineligibilityRegistry,
                    DEPLOYMENT_ID,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    void revertedReceiptWaitsUntilIndexerHasProcessedItsBlock() {
        useSubmittedAttempt();
        useCheckpoint(BigInteger.valueOf(99));

        assertEquals(
                SettlementReceiptResolution.WAITING,
                service.reconcileReverted(
                        ATTEMPT_ID,
                        RECEIPT_BLOCK,
                        "Contract revert"
                )
        );

        verifyNoInteractions(ineligibilityRegistry);
        verify(attempt, never()).markReverted("Contract revert", NOW);
    }

    @Test
    void finalizedRevertCoolsDownBothOrdersBeforeResolution() {
        useSubmittedAttempt();
        useCheckpoint(RECEIPT_BLOCK);
        String oversizedReason = "x".repeat(2_100);

        assertEquals(
                SettlementReceiptResolution.RESOLVED,
                service.reconcileReverted(
                        ATTEMPT_ID,
                        RECEIPT_BLOCK,
                        oversizedReason
                )
        );

        verify(ineligibilityRegistry).markTemporarilyIneligible(
                DEPLOYMENT_ID,
                BUY_ORDER_ID
        );
        verify(ineligibilityRegistry).markTemporarilyIneligible(
                DEPLOYMENT_ID,
                SELL_ORDER_ID
        );
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(attempt).markReverted(reason.capture(), org.mockito.ArgumentMatchers.eq(NOW));
        assertEquals(2_000, reason.getValue().length());
    }

    @Test
    void successfulReceiptWaitsWhileIndexerIsBehind() {
        useSubmittedAttempt();
        useCheckpoint(BigInteger.valueOf(99));

        assertEquals(
                SettlementReceiptResolution.WAITING,
                service.reconcileSucceeded(ATTEMPT_ID, RECEIPT_BLOCK)
        );
        verifyNoInteractions(ineligibilityRegistry);
    }

    @Test
    void successfulReceiptWithoutIndexedTradeHaltsAfterCheckpointPasses() {
        useSubmittedAttempt();
        useCheckpoint(RECEIPT_BLOCK);

        assertThrows(
                SettlementExecutorSafetyException.class,
                () -> service.reconcileSucceeded(
                        ATTEMPT_ID,
                        RECEIPT_BLOCK
                )
        );
        verifyNoInteractions(ineligibilityRegistry);
    }

    @Test
    void concurrentIndexedConfirmationIsAlreadyResolved() {
        when(attemptRepository.findForUpdate(ATTEMPT_ID, DEPLOYMENT_ID))
                .thenReturn(Optional.of(attempt));
        when(attempt.getStatus()).thenReturn(ExecutionAttemptStatus.CONFIRMED);

        assertEquals(
                SettlementReceiptResolution.RESOLVED,
                service.reconcileSucceeded(ATTEMPT_ID, RECEIPT_BLOCK)
        );
        verifyNoInteractions(checkpointRepository, ineligibilityRegistry);
    }

    private void useSubmittedAttempt() {
        when(attemptRepository.findForUpdate(ATTEMPT_ID, DEPLOYMENT_ID))
                .thenReturn(Optional.of(attempt));
        when(attempt.getStatus()).thenReturn(ExecutionAttemptStatus.SUBMITTED);
        when(attempt.getBuyOrderId()).thenReturn(BUY_ORDER_ID);
        when(attempt.getSellOrderId()).thenReturn(SELL_ORDER_ID);
    }

    private void useCheckpoint(BigInteger lastProcessedBlock) {
        IndexerCheckpointEntity checkpoint = mock(IndexerCheckpointEntity.class);
        when(checkpoint.getLastProcessedBlockNumber())
                .thenReturn(lastProcessedBlock);
        when(checkpointRepository.findById(DEPLOYMENT_ID))
                .thenReturn(Optional.of(checkpoint));
    }
}
