package com.dex.backend.persistence.execution;

import com.dex.backend.config.DexProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ExecutionAttemptReadService {

    private final ExecutionAttemptRepository repository;
    private final String deploymentId;

    public ExecutionAttemptReadService(
            ExecutionAttemptRepository repository,
            DexProperties properties
    ) {
        this.repository = repository;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    @Transactional(readOnly = true)
    public Optional<ExecutionAttemptWorkItem> findUnresolvedAttempt() {
        List<ExecutionAttemptEntity> attempts = repository
                .findUnresolvedAttempts(
                        deploymentId,
                        ExecutionAttemptStatus.reservingStatuses()
                );

        if (attempts.size() > 1) {
            throw new ExecutionAttemptTransitionException(
                    "Multiple unresolved attempts exist for the configured deployment"
            );
        }

        return attempts.stream().findFirst().map(this::toWorkItem);
    }

    private ExecutionAttemptWorkItem toWorkItem(
            ExecutionAttemptEntity attempt
    ) {
        ExecutionAttemptStatus status = attempt.getStatus();

        return new ExecutionAttemptWorkItem(
                attempt.getAttemptId(),
                status,
                attempt.toSettlementRequest(),
                attempt.getExecutorAddress(),
                status == ExecutionAttemptStatus.RESERVED
                        ? null
                        : attempt.toPreparedSettlement()
        );
    }
}
