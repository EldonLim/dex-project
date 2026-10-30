package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.model.EthereumAddress;

import java.util.Objects;
import java.util.UUID;

public record ExecutionAttemptWorkItem(
        UUID attemptId,
        ExecutionAttemptStatus status,
        SettlementRequest request,
        EthereumAddress executorAddress,
        PreparedSettlement preparedSettlement
) {

    public ExecutionAttemptWorkItem {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(
                executorAddress,
                "executorAddress must not be null"
        );

        if (!status.reservesOrders()) {
            throw new IllegalArgumentException(
                    "work item status must be unresolved"
            );
        }
        if (status == ExecutionAttemptStatus.RESERVED
                && preparedSettlement != null) {
            throw new IllegalArgumentException(
                    "RESERVED work must not include a prepared settlement"
            );
        }
        if (status != ExecutionAttemptStatus.RESERVED
                && preparedSettlement == null) {
            throw new IllegalArgumentException(
                    "post-sign work requires a prepared settlement"
            );
        }
    }
}
