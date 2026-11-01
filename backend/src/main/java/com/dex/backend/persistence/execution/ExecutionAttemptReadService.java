package com.dex.backend.persistence.execution;

import com.dex.backend.config.DexProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import com.dex.backend.blockchain.execution.SettlementBatchRequest;

@Service
public class ExecutionAttemptReadService {

    private final ExecutionAttemptRepository repository;
    private final ExecutionAttemptLegRepository legRepository;
    private final String deploymentId;

    public ExecutionAttemptReadService(
            ExecutionAttemptRepository repository,
            ExecutionAttemptLegRepository legRepository,
            DexProperties properties
    ) {
        this.repository = repository;
        this.legRepository = legRepository;
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
        SettlementBatchRequest request = requestFor(attempt);

        return new ExecutionAttemptWorkItem(
                attempt.getAttemptId(),
                status,
                request,
                attempt.getExecutorAddress(),
                status == ExecutionAttemptStatus.RESERVED
                        ? null
                        : attempt.toPreparedSettlement(request)
        );
    }

    private SettlementBatchRequest requestFor(ExecutionAttemptEntity attempt) {
        List<ExecutionAttemptLegEntity> legs = legRepository
                .findByIdAttemptIdOrderByIdLegIndex(attempt.getAttemptId());
        if (legs.isEmpty()) {
            throw new ExecutionAttemptTransitionException(
                    "Execution attempt has no settlement legs"
            );
        }
        var marketId = legs.getFirst().getMarketId();
        if (legs.stream().anyMatch(leg -> !marketId.equals(leg.getMarketId()))) {
            throw new ExecutionAttemptTransitionException(
                    "Execution attempt legs span multiple markets"
            );
        }
        return new SettlementBatchRequest(
                attempt.getDeploymentId(),
                marketId,
                legs.stream().map(ExecutionAttemptLegEntity::toSettlementLeg).toList()
        );
    }
}
