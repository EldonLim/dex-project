package com.dex.backend.settlement;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementSigningCoordinatorTests {

    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final EthereumAddress EXECUTOR = address(999);
    private static final SettlementRequest REQUEST = new SettlementRequest(
            "settlement-test",
            BigInteger.ONE,
            BigInteger.TEN,
            BigInteger.valueOf(20),
            BigInteger.valueOf(5)
    );

    private final ExecutionAttemptReadService readService =
            mock(ExecutionAttemptReadService.class);
    private final ExecutionAttemptLifecycleService lifecycleService =
            mock(ExecutionAttemptLifecycleService.class);
    private final BlockchainGateway gateway = mock(BlockchainGateway.class);
    private final SettlementSigningCoordinator coordinator =
            new SettlementSigningCoordinator(
                    readService,
                    lifecycleService,
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
        ExecutionAttemptWorkItem workItem = reservedWorkItem();
        PreparedSettlement prepared = prepared(REQUEST, EXECUTOR);
        when(readService.findUnresolvedAttempt()).thenReturn(
                Optional.of(workItem)
        );
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
        when(gateway.prepareSettlement(REQUEST)).thenAnswer(invocation -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            return prepared;
        });

        assertEquals(
                SettlementExecutorCycleStatus.SIGNED,
                coordinator.runCycle()
        );

        verify(lifecycleService).recordSigned(ATTEMPT_ID, prepared);
        verify(gateway, never()).broadcastSettlement(prepared);
    }

    @Test
    void configuredExecutorMustMatchReservedExecutor() {
        when(readService.findUnresolvedAttempt()).thenReturn(
                Optional.of(reservedWorkItem())
        );
        when(gateway.getExecutorAddress()).thenReturn(address(123));

        assertThrows(
                SettlementExecutorSafetyException.class,
                coordinator::runCycle
        );

        verify(gateway, never()).prepareSettlement(REQUEST);
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void preparedSettlementMustExactlyMatchTheReservation() {
        when(readService.findUnresolvedAttempt()).thenReturn(
                Optional.of(reservedWorkItem())
        );
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

    @Test
    void postSignStatesPerformNoPreparationOrBroadcast() {
        PreparedSettlement prepared = prepared(REQUEST, EXECUTOR);

        for (ExecutionAttemptStatus status : new ExecutionAttemptStatus[]{
                ExecutionAttemptStatus.SIGNED,
                ExecutionAttemptStatus.SUBMITTED,
                ExecutionAttemptStatus.UNCERTAIN
        }) {
            when(readService.findUnresolvedAttempt()).thenReturn(Optional.of(
                    new ExecutionAttemptWorkItem(
                            ATTEMPT_ID,
                            status,
                            REQUEST,
                            EXECUTOR,
                            prepared
                    )
            ));

            SettlementExecutorCycleStatus expected =
                    status == ExecutionAttemptStatus.SIGNED
                            ? SettlementExecutorCycleStatus.SIGNED
                            : SettlementExecutorCycleStatus
                            .WAITING_FOR_RECONCILIATION;
            assertEquals(expected, coordinator.runCycle());
        }

        verifyNoInteractions(gateway, lifecycleService);
    }

    private static ExecutionAttemptWorkItem reservedWorkItem() {
        return new ExecutionAttemptWorkItem(
                ATTEMPT_ID,
                ExecutionAttemptStatus.RESERVED,
                REQUEST,
                EXECUTOR,
                null
        );
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

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
