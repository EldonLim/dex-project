package com.dex.backend.settlement;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.TransactionObservation;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.persistence.execution.ExecutionAttemptLifecycleService;
import com.dex.backend.persistence.execution.ExecutionAttemptReadService;
import com.dex.backend.persistence.execution.ExecutionAttemptWorkItem;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SettlementExecutionCoordinator {

    static final String BROADCAST_UNCERTAIN_CODE =
            "BROADCAST_OUTCOME_UNCERTAIN";
    static final String BROADCAST_UNCERTAIN_MESSAGE =
            "Settlement broadcast outcome is uncertain";
    static final String BROADCAST_HASH_MISMATCH_CODE =
            "BROADCAST_HASH_MISMATCH";
    static final String BROADCAST_HASH_MISMATCH_MESSAGE =
            "Settlement broadcast returned an unexpected transaction hash";
    static final String SUBMITTED_NOT_FOUND_CODE =
            "SUBMITTED_TRANSACTION_NOT_FOUND";
    static final String SUBMITTED_NOT_FOUND_MESSAGE =
            "Submitted settlement transaction is not currently visible";

    private final ExecutionAttemptReadService readService;
    private final ExecutionAttemptLifecycleService lifecycleService;
    private final SettlementReceiptResolutionService receiptResolutionService;
    private final BlockchainGateway gateway;

    public SettlementExecutionCoordinator(
            ExecutionAttemptReadService readService,
            ExecutionAttemptLifecycleService lifecycleService,
            SettlementReceiptResolutionService receiptResolutionService,
            BlockchainGateway gateway
    ) {
        this.readService = readService;
        this.lifecycleService = lifecycleService;
        this.receiptResolutionService = receiptResolutionService;
        this.gateway = gateway;
    }

    public SettlementExecutorCycleStatus runCycle() {
        Optional<ExecutionAttemptWorkItem> result =
                readService.findUnresolvedAttempt();

        if (result.isEmpty()) {
            return SettlementExecutorCycleStatus.IDLE;
        }

        ExecutionAttemptWorkItem workItem = result.orElseThrow();

        return switch (workItem.status()) {
            case RESERVED -> sign(workItem);
            case SIGNED -> broadcast(workItem);
            case SUBMITTED -> reconcileSubmitted(workItem);
            case UNCERTAIN -> reconcileUncertain(workItem);
            case CONFIRMED, REVERTED, ABANDONED -> throw safetyFailure(
                    "Resolved execution attempt was returned as unresolved"
            );
        };
    }

    private SettlementExecutorCycleStatus sign(
            ExecutionAttemptWorkItem workItem
    ) {
        validateConfiguredExecutor(workItem);

        PreparedSettlement prepared = gateway.prepareSettlement(
                workItem.request()
        );
        validatePreparedSettlement(workItem, prepared);
        lifecycleService.recordSigned(workItem.attemptId(), prepared);

        return SettlementExecutorCycleStatus.SIGNED;
    }

    private SettlementExecutorCycleStatus broadcast(
            ExecutionAttemptWorkItem workItem
    ) {
        validateConfiguredExecutor(workItem);
        PreparedSettlement prepared = workItem.preparedSettlement();
        Hash256 returnedHash;

        try {
            returnedHash = gateway.broadcastSettlement(prepared);
        } catch (BlockchainGatewayException exception) {
            recordBroadcastUncertain(workItem);
            throw exception;
        } catch (IllegalArgumentException exception) {
            recordBroadcastUncertain(workItem);
            throw safetyFailure(
                    "Gateway rejected the durably signed settlement"
            );
        } catch (RuntimeException exception) {
            recordBroadcastUncertain(workItem);
            throw exception;
        }

        if (!prepared.transactionHash().equals(returnedHash)) {
            lifecycleService.recordUncertain(
                    workItem.attemptId(),
                    BROADCAST_HASH_MISMATCH_CODE,
                    BROADCAST_HASH_MISMATCH_MESSAGE
            );
            throw safetyFailure(
                    "Broadcast returned a different transaction hash"
            );
        }

        lifecycleService.recordSubmitted(workItem.attemptId());
        return SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION;
    }

    private SettlementExecutorCycleStatus reconcileUncertain(
            ExecutionAttemptWorkItem workItem
    ) {
        validateConfiguredExecutor(workItem);
        PreparedSettlement prepared = workItem.preparedSettlement();
        TransactionObservation observation = gateway.getTransaction(
                prepared.transactionHash()
        );
        validateObservation(prepared, observation);

        return switch (observation.status()) {
            case NOT_FOUND -> broadcast(workItem);
            case PENDING, SUCCEEDED, REVERTED -> {
                lifecycleService.recordSubmitted(workItem.attemptId());
                yield SettlementExecutorCycleStatus
                        .WAITING_FOR_RECONCILIATION;
            }
        };
    }

    private SettlementExecutorCycleStatus reconcileSubmitted(
            ExecutionAttemptWorkItem workItem
    ) {
        validateConfiguredExecutor(workItem);
        PreparedSettlement prepared = workItem.preparedSettlement();
        TransactionObservation observation = gateway.getTransaction(
                prepared.transactionHash()
        );
        validateObservation(prepared, observation);

        return switch (observation.status()) {
            case NOT_FOUND -> {
                lifecycleService.recordUncertain(
                        workItem.attemptId(),
                        SUBMITTED_NOT_FOUND_CODE,
                        SUBMITTED_NOT_FOUND_MESSAGE
                );
                yield SettlementExecutorCycleStatus
                        .WAITING_FOR_RECONCILIATION;
            }
            case PENDING -> SettlementExecutorCycleStatus
                    .WAITING_FOR_RECONCILIATION;
            case SUCCEEDED -> resolveMinedObservation(
                    workItem,
                    observation,
                    false
            );
            case REVERTED -> resolveMinedObservation(
                    workItem,
                    observation,
                    true
            );
        };
    }

    private SettlementExecutorCycleStatus resolveMinedObservation(
            ExecutionAttemptWorkItem workItem,
            TransactionObservation observation,
            boolean reverted
    ) {
        verifyCanonicalReceiptBlock(observation);

        SettlementReceiptResolution resolution = reverted
                ? receiptResolutionService.reconcileReverted(
                        workItem.attemptId(),
                        observation.blockNumber(),
                        observation.failureReason()
                )
                : receiptResolutionService.reconcileSucceeded(
                        workItem.attemptId(),
                        observation.blockNumber()
                );

        return resolution == SettlementReceiptResolution.RESOLVED
                ? SettlementExecutorCycleStatus.IDLE
                : SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION;
    }

    private void verifyCanonicalReceiptBlock(
            TransactionObservation observation
    ) {
        Optional<BlockReference> block = gateway.getBlock(
                observation.blockNumber()
        );

        if (block.isEmpty()
                || !block.orElseThrow().hash().equals(observation.blockHash())) {
            throw new SettlementReceiptRetryException(
                    "Mined settlement block is not currently canonical"
            );
        }
    }

    private void validateObservation(
            PreparedSettlement prepared,
            TransactionObservation observation
    ) {
        if (observation == null
                || !prepared.transactionHash().equals(
                        observation.transactionHash()
                )) {
            throw safetyFailure(
                    "Transaction lookup returned an invalid observation"
            );
        }
    }

    private void recordBroadcastUncertain(
            ExecutionAttemptWorkItem workItem
    ) {
        lifecycleService.recordUncertain(
                workItem.attemptId(),
                BROADCAST_UNCERTAIN_CODE,
                BROADCAST_UNCERTAIN_MESSAGE
        );
    }

    private void validateConfiguredExecutor(
            ExecutionAttemptWorkItem workItem
    ) {
        if (!workItem.executorAddress().equals(
                gateway.getExecutorAddress()
        )) {
            throw safetyFailure(
                    "Attempt executor does not match the configured gateway"
            );
        }
    }

    private void validatePreparedSettlement(
            ExecutionAttemptWorkItem workItem,
            PreparedSettlement prepared
    ) {
        if (prepared == null
                || !workItem.request().equals(prepared.request())
                || !workItem.executorAddress().equals(
                        prepared.executorAddress()
                )) {
            throw safetyFailure(
                    "Prepared settlement does not match the reserved work item"
            );
        }
    }

    private SettlementExecutorSafetyException safetyFailure(String message) {
        return new SettlementExecutorSafetyException(message);
    }
}
