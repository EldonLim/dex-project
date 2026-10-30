package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.config.DexProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class ExecutionAttemptLifecycleService {

    private final ExecutionAttemptRepository repository;
    private final String deploymentId;
    private final Clock clock;

    @Autowired
    public ExecutionAttemptLifecycleService(
            ExecutionAttemptRepository repository,
            DexProperties properties
    ) {
        this(
                repository,
                properties.blockchain().deploymentId(),
                Clock.systemUTC()
        );
    }

    ExecutionAttemptLifecycleService(
            ExecutionAttemptRepository repository,
            String deploymentId,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository must not be null"
        );
        this.deploymentId = requireDeploymentId(deploymentId);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void recordSigned(
            UUID attemptId,
            PreparedSettlement preparedSettlement
    ) {
        load(attemptId).markSigned(preparedSettlement, clock.instant());
    }

    public void recordSubmitted(UUID attemptId) {
        load(attemptId).markSubmitted(clock.instant());
    }

    public void recordUncertain(
            UUID attemptId,
            String errorCode,
            String errorMessage
    ) {
        load(attemptId).markUncertain(
                errorCode,
                errorMessage,
                clock.instant()
        );
    }

    public void recordConfirmed(UUID attemptId) {
        load(attemptId).markConfirmed(clock.instant());
    }

    public void recordReverted(UUID attemptId, String revertReason) {
        load(attemptId).markReverted(revertReason, clock.instant());
    }

    public void abandon(
            UUID attemptId,
            String errorCode,
            String errorMessage
    ) {
        load(attemptId).abandon(
                errorCode,
                errorMessage,
                clock.instant()
        );
    }

    private ExecutionAttemptEntity load(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");

        return repository.findByAttemptIdAndDeploymentId(
                attemptId,
                deploymentId
        ).orElseThrow(() -> new ExecutionAttemptTransitionException(
                "Execution attempt was not found for the configured deployment"
        ));
    }

    private static String requireDeploymentId(String value) {
        Objects.requireNonNull(value, "deploymentId must not be null");
        String normalized = value.strip();

        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }
        return normalized;
    }
}
