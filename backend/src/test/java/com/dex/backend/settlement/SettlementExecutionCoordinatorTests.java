package com.dex.backend.settlement;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.execution.TransactionObservation;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.persistence.execution.ExecutionAttemptLifecycleService;
import com.dex.backend.persistence.execution.ExecutionAttemptReadService;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import com.dex.backend.persistence.execution.ExecutionAttemptWorkItem;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementExecutionCoordinatorTests {

    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final EthereumAddress EXECUTOR = address(999);
    private static final SettlementRequest REQUEST = new SettlementRequest(
            "settlement-test",
            BigInteger.ONE,
            BigInteger.TEN,
            BigInteger.valueOf(20),
            BigInteger.valueOf(5)
    );
    private static final PreparedSettlement PREPARED = prepared(
            REQUEST,
            EXECUTOR
    );

    private final ExecutionAttemptReadService readService =
            mock(ExecutionAttemptReadService.class);
    private final ExecutionAttemptLifecycleService lifecycleService =
            mock(ExecutionAttemptLifecycleService.class);
    private final SettlementReceiptResolutionService receiptResolutionService =
            mock(SettlementReceiptResolutionService.class);
    private final BlockchainGateway gateway = mock(BlockchainGateway.class);
    private final SettlementExecutionCoordinator coordinator =
            new SettlementExecutionCoordinator(
                    readService,
                    lifecycleService,
                    receiptResolutionService,
                    gateway
            );

    @Test
    void noUnresolvedAttemptIsIdleWithoutGatewayWork() {
        when(readService.findUnresolvedAttempt()).thenReturn(Optional.empty());

        assertEquals(SettlementExecutorCycleStatus.IDLE, coordinator.runCycle());
        verifyNoInteractions(gateway, lifecycleService);
    }

    @Test
    void reservedAttemptIsPreparedOutsideTransactionAndRecordedSigned() {
        useWorkItem(ExecutionAttemptStatus.RESERVED);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.prepareSettlement(REQUEST)).thenAnswer(invocation -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            return PREPARED;
        });

        assertEquals(
                SettlementExecutorCycleStatus.SIGNED,
                coordinator.runCycle()
        );

        verify(lifecycleService).recordSigned(ATTEMPT_ID, PREPARED);
        verify(gateway, never()).broadcastSettlement(PREPARED);
    }

    @Test
    void signedAttemptBroadcastsExactPayloadOutsideTransaction() {
        useWorkItem(ExecutionAttemptStatus.SIGNED);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.broadcastSettlement(PREPARED)).thenAnswer(invocation -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            assertSame(PREPARED, invocation.getArgument(0));
            return PREPARED.transactionHash();
        });

        assertEquals(
                SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION,
                coordinator.runCycle()
        );

        verify(lifecycleService).recordSubmitted(ATTEMPT_ID);
        verify(gateway, never()).prepareSettlement(REQUEST);
        verify(gateway, never()).getTransaction(PREPARED.transactionHash());
    }

    @Test
    void broadcastFailureRecordsOnlySanitizedUncertainty() {
        useWorkItem(ExecutionAttemptStatus.SIGNED);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        BlockchainGatewayException failure = new BlockchainGatewayException(
                "rpc password=secret and raw=0xdeadbeef",
                true
        );
        when(gateway.broadcastSettlement(PREPARED)).thenThrow(failure);

        BlockchainGatewayException thrown = assertThrows(
                BlockchainGatewayException.class,
                coordinator::runCycle
        );

        assertSame(failure, thrown);
        verify(lifecycleService).recordUncertain(
                ATTEMPT_ID,
                SettlementExecutionCoordinator.BROADCAST_UNCERTAIN_CODE,
                SettlementExecutionCoordinator.BROADCAST_UNCERTAIN_MESSAGE
        );
        verify(lifecycleService, never()).recordSubmitted(ATTEMPT_ID);
    }

    @Test
    void locallyRejectedBroadcastRecordsUncertaintyAndBecomesSafetyFailure() {
        useWorkItem(ExecutionAttemptStatus.SIGNED);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.broadcastSettlement(PREPARED)).thenThrow(
                new IllegalArgumentException("signed bytes are invalid")
        );

        assertThrows(
                SettlementExecutorSafetyException.class,
                coordinator::runCycle
        );
        verify(lifecycleService).recordUncertain(
                ATTEMPT_ID,
                SettlementExecutionCoordinator.BROADCAST_UNCERTAIN_CODE,
                SettlementExecutionCoordinator.BROADCAST_UNCERTAIN_MESSAGE
        );
    }

    @Test
    void returnedHashMismatchRecordsUncertaintyAndHaltsSafely() {
        useWorkItem(ExecutionAttemptStatus.SIGNED);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.broadcastSettlement(PREPARED)).thenReturn(hash(99));

        assertThrows(
                SettlementExecutorSafetyException.class,
                coordinator::runCycle
        );
        verify(lifecycleService).recordUncertain(
                ATTEMPT_ID,
                SettlementExecutionCoordinator.BROADCAST_HASH_MISMATCH_CODE,
                SettlementExecutionCoordinator.BROADCAST_HASH_MISMATCH_MESSAGE
        );
        verify(lifecycleService, never()).recordSubmitted(ATTEMPT_ID);
    }

    @Test
    void uncertainNotFoundTransactionRebroadcastsTheExactPayload() {
        useWorkItem(ExecutionAttemptStatus.UNCERTAIN);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.getTransaction(PREPARED.transactionHash())).thenReturn(
                TransactionObservation.notFound(PREPARED.transactionHash())
        );
        when(gateway.broadcastSettlement(PREPARED)).thenReturn(
                PREPARED.transactionHash()
        );

        assertEquals(
                SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION,
                coordinator.runCycle()
        );

        verify(gateway).broadcastSettlement(PREPARED);
        verify(gateway, never()).prepareSettlement(REQUEST);
        verify(lifecycleService).recordSubmitted(ATTEMPT_ID);
    }

    @Test
    void anyVisibleTransactionMovesUncertainAttemptToSubmitted() {
        useWorkItem(ExecutionAttemptStatus.UNCERTAIN);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.getTransaction(PREPARED.transactionHash())).thenReturn(
                TransactionObservation.pending(PREPARED.transactionHash()),
                TransactionObservation.succeeded(
                        PREPARED.transactionHash(),
                        BigInteger.valueOf(100),
                        hash(100)
                ),
                TransactionObservation.reverted(
                        PREPARED.transactionHash(),
                        BigInteger.valueOf(101),
                        hash(101),
                        "contract revert"
                )
        );

        for (int invocation = 0; invocation < 3; invocation++) {
            assertEquals(
                    SettlementExecutorCycleStatus
                            .WAITING_FOR_RECONCILIATION,
                    coordinator.runCycle()
            );
        }

        verify(lifecycleService, times(3)).recordSubmitted(ATTEMPT_ID);
        verify(gateway, never()).broadcastSettlement(PREPARED);
        verify(gateway, never()).prepareSettlement(REQUEST);
    }

    @Test
    void transactionLookupFailureLeavesUncertainLifecycleUntouched() {
        useWorkItem(ExecutionAttemptStatus.UNCERTAIN);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        BlockchainGatewayException failure = new BlockchainGatewayException(
                "lookup unavailable",
                true
        );
        when(gateway.getTransaction(PREPARED.transactionHash())).thenThrow(
                failure
        );

        assertSame(failure, assertThrows(
                BlockchainGatewayException.class,
                coordinator::runCycle
        ));
        verifyNoInteractions(lifecycleService);
        verify(gateway, never()).broadcastSettlement(PREPARED);
    }

    @Test
    void nullOrMismatchedLookupObservationIsASafetyFailure() {
        useWorkItem(ExecutionAttemptStatus.UNCERTAIN);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.getTransaction(PREPARED.transactionHash())).thenReturn(
                null,
                TransactionObservation.pending(hash(99))
        );

        for (int invocation = 0; invocation < 2; invocation++) {
            assertThrows(
                    SettlementExecutorSafetyException.class,
                    coordinator::runCycle
            );
        }
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void submittedPendingAttemptWaitsWithoutLifecycleMutation() {
        useWorkItem(ExecutionAttemptStatus.SUBMITTED);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.getTransaction(PREPARED.transactionHash())).thenReturn(
                TransactionObservation.pending(PREPARED.transactionHash())
        );

        assertEquals(
                SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION,
                coordinator.runCycle()
        );
        verifyNoInteractions(lifecycleService, receiptResolutionService);
    }

    @Test
    void submittedNotFoundAttemptBecomesUncertainWithSanitizedDetails() {
        useWorkItem(ExecutionAttemptStatus.SUBMITTED);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.getTransaction(PREPARED.transactionHash())).thenReturn(
                TransactionObservation.notFound(PREPARED.transactionHash())
        );

        assertEquals(
                SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION,
                coordinator.runCycle()
        );
        verify(lifecycleService).recordUncertain(
                ATTEMPT_ID,
                SettlementExecutionCoordinator.SUBMITTED_NOT_FOUND_CODE,
                SettlementExecutionCoordinator.SUBMITTED_NOT_FOUND_MESSAGE
        );
        verifyNoInteractions(receiptResolutionService);
    }

    @Test
    void submittedSuccessWaitsForIndexedTradeConfirmation() {
        useWorkItem(ExecutionAttemptStatus.SUBMITTED);
        BlockReference block = block(100);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.getTransaction(PREPARED.transactionHash())).thenReturn(
                TransactionObservation.succeeded(
                        PREPARED.transactionHash(),
                        block.number(),
                        block.hash()
                )
        );
        when(gateway.getBlock(block.number())).thenReturn(Optional.of(block));
        when(receiptResolutionService.reconcileSucceeded(
                ATTEMPT_ID,
                block.number()
        )).thenReturn(SettlementReceiptResolution.WAITING);

        assertEquals(
                SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION,
                coordinator.runCycle()
        );
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void submittedRevertResolvesOnlyThroughReceiptService() {
        useWorkItem(ExecutionAttemptStatus.SUBMITTED);
        BlockReference block = block(101);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.getTransaction(PREPARED.transactionHash())).thenReturn(
                TransactionObservation.reverted(
                        PREPARED.transactionHash(),
                        block.number(),
                        block.hash(),
                        "Contract rejected settlement"
                )
        );
        when(gateway.getBlock(block.number())).thenReturn(Optional.of(block));
        when(receiptResolutionService.reconcileReverted(
                ATTEMPT_ID,
                block.number(),
                "Contract rejected settlement"
        )).thenReturn(SettlementReceiptResolution.RESOLVED);

        assertEquals(SettlementExecutorCycleStatus.IDLE, coordinator.runCycle());
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void noncanonicalMinedObservationIsRetriedWithoutResolution() {
        useWorkItem(ExecutionAttemptStatus.SUBMITTED);
        BlockReference receiptBlock = block(102);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.getTransaction(PREPARED.transactionHash())).thenReturn(
                TransactionObservation.succeeded(
                        PREPARED.transactionHash(),
                        receiptBlock.number(),
                        receiptBlock.hash()
                )
        );
        when(gateway.getBlock(receiptBlock.number())).thenReturn(Optional.of(
                new BlockReference(
                        receiptBlock.number(),
                        hash(999),
                        receiptBlock.timestamp()
                )
        ));

        assertThrows(
                SettlementReceiptRetryException.class,
                coordinator::runCycle
        );
        verifyNoInteractions(lifecycleService, receiptResolutionService);
    }

    @Test
    void configuredExecutorMustMatchBeforeSigningOrSubmission() {
        useWorkItem(ExecutionAttemptStatus.SIGNED);
        when(gateway.getExecutorAddress()).thenReturn(address(123));

        assertThrows(
                SettlementExecutorSafetyException.class,
                coordinator::runCycle
        );
        verify(gateway, never()).broadcastSettlement(PREPARED);
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void preparedSettlementMustExactlyMatchTheReservation() {
        useWorkItem(ExecutionAttemptStatus.RESERVED);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        SettlementRequest wrongRequest = new SettlementRequest(
                REQUEST.deploymentId(),
                REQUEST.marketId(),
                REQUEST.buyOrderId(),
                BigInteger.valueOf(21),
                REQUEST.fillLots()
        );
        when(gateway.prepareSettlement(REQUEST)).thenReturn(
                prepared(wrongRequest, EXECUTOR)
        );

        assertThrows(
                SettlementExecutorSafetyException.class,
                coordinator::runCycle
        );
        verifyNoInteractions(lifecycleService);
    }

    private void useWorkItem(ExecutionAttemptStatus status) {
        when(readService.findUnresolvedAttempt()).thenReturn(Optional.of(
                new ExecutionAttemptWorkItem(
                        ATTEMPT_ID,
                        status,
                        REQUEST,
                        EXECUTOR,
                        status == ExecutionAttemptStatus.RESERVED
                                ? null
                                : PREPARED
                )
        ));
    }

    private static PreparedSettlement prepared(
            SettlementRequest request,
            EthereumAddress executor
    ) {
        return new PreparedSettlement(
                request,
                executor,
                BigInteger.ONE.shiftLeft(200),
                hash(1),
                "0xdeadbeef"
        );
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static BlockReference block(long number) {
        return new BlockReference(
                BigInteger.valueOf(number),
                hash(number),
                java.time.Instant.parse("2026-01-01T00:00:00Z")
                        .plusSeconds(number)
        );
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
