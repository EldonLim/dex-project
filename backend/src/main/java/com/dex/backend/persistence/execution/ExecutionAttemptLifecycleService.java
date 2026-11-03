package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.config.DexProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class ExecutionAttemptLifecycleService {

    private final ExecutionAttemptRepository repository;
    private final ExecutionAttemptLegRepository legRepository;
    private final String deploymentId;
    private final Clock clock;

    @Autowired
    public ExecutionAttemptLifecycleService(
            ExecutionAttemptRepository repository,
            ExecutionAttemptLegRepository legRepository,
            DexProperties properties
    ) {
        this(
                repository,
                legRepository,
                properties.blockchain().deploymentId(),
                Clock.systemUTC()
        );
    }

    ExecutionAttemptLifecycleService(
            ExecutionAttemptRepository repository,
            ExecutionAttemptLegRepository legRepository,
            String deploymentId,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository must not be null"
        );
        this.legRepository = Objects.requireNonNull(
                legRepository,
                "legRepository must not be null"
        );
        this.deploymentId = requireDeploymentId(deploymentId);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void recordSigned(
            UUID attemptId,
            PreparedSettlement preparedSettlement
    ) {
        ExecutionAttemptEntity attempt = load(attemptId);
        var legs = legRepository.findByIdAttemptIdOrderByIdLegIndex(attemptId);
        if (legs.isEmpty()) {
            throw new ExecutionAttemptTransitionException(
                    "Execution attempt has no settlement legs"
            );
        }
        var request = new com.dex.backend.blockchain.execution.SettlementBatchRequest(
                attempt.getDeploymentId(),
                legs.getFirst().getMarketId(),
                legs.stream()
                        .map(ExecutionAttemptLegEntity::toSettlementLeg)
                        .toList()
        );
        attempt.markSigned(
                preparedSettlement,
                request,
                monotonicTimestamp(attempt)
        );
    }

    public void recordSubmitted(UUID attemptId) {
        ExecutionAttemptEntity attempt = load(attemptId);
        attempt.markSubmitted(monotonicTimestamp(attempt));
    }

    public void recordUncertain(
            UUID attemptId,
            String errorCode,
            String errorMessage
    ) {
        ExecutionAttemptEntity attempt = load(attemptId);
        attempt.markUncertain(
                errorCode,
                errorMessage,
                monotonicTimestamp(attempt)
        );
    }

    public void recordConfirmed(UUID attemptId) {
        ExecutionAttemptEntity attempt = load(attemptId);
        attempt.markConfirmed(monotonicTimestamp(attempt));
    }

    public void recordReverted(UUID attemptId, String revertReason) {
        ExecutionAttemptEntity attempt = load(attemptId);
        attempt.markReverted(
                revertReason,
                monotonicTimestamp(attempt)
        );
    }

    public void abandon(
            UUID attemptId,
            String errorCode,
            String errorMessage
    ) {
        ExecutionAttemptEntity attempt = load(attemptId);
        attempt.abandon(
                errorCode,
                errorMessage,
                monotonicTimestamp(attempt)
        );
    }

    /**
     * PostgreSQL stores timestamps at microsecond precision and may round a
     * freshly persisted value slightly ahead of the next JVM nanosecond
     * reading. Preserve monotonic lifecycle timestamps across that boundary.
     */
    private Instant monotonicTimestamp(ExecutionAttemptEntity attempt) {
        Instant now = clock.instant();
        return now.isBefore(attempt.getUpdatedAt())
                ? attempt.getUpdatedAt()
                : now;
    }

    private ExecutionAttemptEntity load(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");

        return repository.findForUpdate(
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
