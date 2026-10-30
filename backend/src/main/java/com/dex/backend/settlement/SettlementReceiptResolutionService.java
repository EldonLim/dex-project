package com.dex.backend.settlement;

import com.dex.backend.config.DexProperties;
import com.dex.backend.matcher.TemporaryOrderIneligibilityRegistry;
import com.dex.backend.persistence.execution.ExecutionAttemptEntity;
import com.dex.backend.persistence.execution.ExecutionAttemptRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import com.dex.backend.persistence.execution.ExecutionAttemptTransitionException;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
public class SettlementReceiptResolutionService {

    private static final int MAX_REVERT_REASON_LENGTH = 2_000;
    private static final String DEFAULT_REVERT_REASON =
            "Settlement transaction reverted";

    private final ExecutionAttemptRepository attemptRepository;
    private final IndexerCheckpointRepository checkpointRepository;
    private final TemporaryOrderIneligibilityRegistry ineligibilityRegistry;
    private final String deploymentId;
    private final Clock clock;

    @Autowired
    public SettlementReceiptResolutionService(
            ExecutionAttemptRepository attemptRepository,
            IndexerCheckpointRepository checkpointRepository,
            TemporaryOrderIneligibilityRegistry ineligibilityRegistry,
            DexProperties properties
    ) {
        this(
                attemptRepository,
                checkpointRepository,
                ineligibilityRegistry,
                properties.blockchain().deploymentId(),
                Clock.systemUTC()
        );
    }

    SettlementReceiptResolutionService(
            ExecutionAttemptRepository attemptRepository,
            IndexerCheckpointRepository checkpointRepository,
            TemporaryOrderIneligibilityRegistry ineligibilityRegistry,
            String deploymentId,
            Clock clock
    ) {
        this.attemptRepository = Objects.requireNonNull(
                attemptRepository,
                "attemptRepository must not be null"
        );
        this.checkpointRepository = Objects.requireNonNull(
                checkpointRepository,
                "checkpointRepository must not be null"
        );
        this.ineligibilityRegistry = Objects.requireNonNull(
                ineligibilityRegistry,
                "ineligibilityRegistry must not be null"
        );
        this.deploymentId = requireDeploymentId(deploymentId);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementReceiptResolution reconcileSucceeded(
            UUID attemptId,
            BigInteger receiptBlockNumber
    ) {
        ExecutionAttemptEntity attempt = loadForUpdate(attemptId);

        if (attempt.getStatus() == ExecutionAttemptStatus.CONFIRMED) {
            return SettlementReceiptResolution.RESOLVED;
        }
        requireSubmitted(attempt);

        if (!hasIndexedThrough(receiptBlockNumber)) {
            return SettlementReceiptResolution.WAITING;
        }

        throw new SettlementExecutorSafetyException(
                "Successful settlement was indexed without a matching trade event"
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementReceiptResolution reconcileReverted(
            UUID attemptId,
            BigInteger receiptBlockNumber,
            String revertReason
    ) {
        ExecutionAttemptEntity attempt = loadForUpdate(attemptId);

        if (attempt.getStatus() == ExecutionAttemptStatus.REVERTED) {
            return SettlementReceiptResolution.RESOLVED;
        }
        requireSubmitted(attempt);

        if (!hasIndexedThrough(receiptBlockNumber)) {
            return SettlementReceiptResolution.WAITING;
        }

        ineligibilityRegistry.markTemporarilyIneligible(
                deploymentId,
                attempt.getBuyOrderId()
        );
        ineligibilityRegistry.markTemporarilyIneligible(
                deploymentId,
                attempt.getSellOrderId()
        );
        attempt.markReverted(
                normalizeRevertReason(revertReason),
                clock.instant()
        );
        return SettlementReceiptResolution.RESOLVED;
    }

    private ExecutionAttemptEntity loadForUpdate(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");

        return attemptRepository.findForUpdate(attemptId, deploymentId)
                .orElseThrow(() -> new ExecutionAttemptTransitionException(
                        "Execution attempt was not found for the configured deployment"
                ));
    }

    private void requireSubmitted(ExecutionAttemptEntity attempt) {
        if (attempt.getStatus() != ExecutionAttemptStatus.SUBMITTED) {
            throw new ExecutionAttemptTransitionException(
                    "Receipt reconciliation requires a submitted execution attempt"
            );
        }
    }

    private boolean hasIndexedThrough(BigInteger receiptBlockNumber) {
        Objects.requireNonNull(
                receiptBlockNumber,
                "receiptBlockNumber must not be null"
        );

        if (receiptBlockNumber.signum() < 0) {
            throw new IllegalArgumentException(
                    "receiptBlockNumber must be non-negative"
            );
        }

        return checkpointRepository.findById(deploymentId)
                .map(checkpoint -> checkpoint.getLastProcessedBlockNumber()
                        .compareTo(receiptBlockNumber) >= 0)
                .orElse(false);
    }

    private static String normalizeRevertReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_REVERT_REASON;
        }

        String normalized = reason.strip();
        return normalized.length() <= MAX_REVERT_REASON_LENGTH
                ? normalized
                : normalized.substring(0, MAX_REVERT_REASON_LENGTH);
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
