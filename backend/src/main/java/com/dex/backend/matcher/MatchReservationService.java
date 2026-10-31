package com.dex.backend.matcher;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.execution.ExecutionAttemptEntity;
import com.dex.backend.persistence.execution.ExecutionAttemptRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class MatchReservationService {

    private final ExecutionAttemptRepository executionAttemptRepository;
    private final ProjectionMatchService projectionMatchService;
    private final String deploymentId;
    private final Clock clock;

    @Autowired
    public MatchReservationService(
            ExecutionAttemptRepository executionAttemptRepository,
            ProjectionMatchService projectionMatchService,
            DexProperties properties
    ) {
        this(
                executionAttemptRepository,
                projectionMatchService,
                properties.blockchain().deploymentId(),
                Clock.systemUTC()
        );
    }

    MatchReservationService(
            ExecutionAttemptRepository executionAttemptRepository,
            ProjectionMatchService projectionMatchService,
            String deploymentId,
            Clock clock
    ) {
        this.executionAttemptRepository = Objects.requireNonNull(
                executionAttemptRepository,
                "executionAttemptRepository must not be null"
        );
        this.projectionMatchService = Objects.requireNonNull(
                projectionMatchService,
                "projectionMatchService must not be null"
        );
        this.deploymentId = Objects.requireNonNull(
                deploymentId,
                "deploymentId must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public MatchReservationResult reserveIfCurrent(
            MatchProposal proposal,
            EthereumAddress executorAddress
    ) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(
                executorAddress,
                "executorAddress must not be null"
        );

        if (!deploymentId.equals(proposal.deploymentId())) {
            throw new IllegalArgumentException(
                    "proposal must belong to the configured deployment"
            );
        }

        if (hasUnresolvedAttempt()) {
            return MatchReservationResult.ALREADY_RESERVED;
        }

        Optional<MatchProposal> currentProposal =
                projectionMatchService.findNextProposal();

        if (currentProposal.isEmpty()
                || !currentProposal.orElseThrow().equals(proposal)) {
            return MatchReservationResult.STALE;
        }

        executionAttemptRepository.saveAndFlush(
                ExecutionAttemptEntity.reserved(
                        UUID.randomUUID(),
                        proposal,
                        executorAddress,
                        clock.instant()
                )
        );

        return MatchReservationResult.RESERVED;
    }

    private boolean hasUnresolvedAttempt() {
        return executionAttemptRepository.existsUnresolved(
                deploymentId,
                ExecutionAttemptStatus.reservingStatuses()
        );
    }
}
