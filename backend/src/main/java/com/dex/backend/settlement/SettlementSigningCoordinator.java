package com.dex.backend.settlement;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.persistence.execution.ExecutionAttemptLifecycleService;
import com.dex.backend.persistence.execution.ExecutionAttemptReadService;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import com.dex.backend.persistence.execution.ExecutionAttemptWorkItem;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SettlementSigningCoordinator {

    private final ExecutionAttemptReadService readService;
    private final ExecutionAttemptLifecycleService lifecycleService;
    private final BlockchainGateway gateway;

    public SettlementSigningCoordinator(
            ExecutionAttemptReadService readService,
            ExecutionAttemptLifecycleService lifecycleService,
            BlockchainGateway gateway
    ) {
        this.readService = readService;
        this.lifecycleService = lifecycleService;
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
            case SIGNED -> SettlementExecutorCycleStatus.SIGNED;
            case SUBMITTED, UNCERTAIN ->
                    SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION;
            case CONFIRMED, REVERTED, ABANDONED -> throw safetyFailure(
                    "Resolved execution attempt was returned as unresolved"
            );
        };
    }

    private SettlementExecutorCycleStatus sign(
            ExecutionAttemptWorkItem workItem
    ) {
        if (!gateway.getExecutorAddress().equals(
                workItem.executorAddress()
        )) {
            throw safetyFailure(
                    "Reserved executor does not match the configured gateway"
            );
        }

        PreparedSettlement prepared = gateway.prepareSettlement(
                workItem.request()
        );
        validatePreparedSettlement(workItem, prepared);
        lifecycleService.recordSigned(workItem.attemptId(), prepared);

        return SettlementExecutorCycleStatus.SIGNED;
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
