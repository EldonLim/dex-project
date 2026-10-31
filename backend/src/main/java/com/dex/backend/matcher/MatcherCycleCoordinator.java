package com.dex.backend.matcher;

import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.execution.ExecutionAttemptRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Optional;

@Service
public class MatcherCycleCoordinator {

    private final MatcherReadinessService readinessService;
    private final ExecutionAttemptRepository executionAttemptRepository;
    private final ProjectionMatchService projectionMatchService;
    private final MatchPreflightService preflightService;
    private final TemporaryOrderIneligibilityRegistry ineligibilityRegistry;
    private final MatchReservationService reservationService;
    private final String deploymentId;

    public MatcherCycleCoordinator(
            MatcherReadinessService readinessService,
            ExecutionAttemptRepository executionAttemptRepository,
            ProjectionMatchService projectionMatchService,
            MatchPreflightService preflightService,
            TemporaryOrderIneligibilityRegistry ineligibilityRegistry,
            MatchReservationService reservationService,
            DexProperties properties
    ) {
        this.readinessService = readinessService;
        this.executionAttemptRepository = executionAttemptRepository;
        this.projectionMatchService = projectionMatchService;
        this.preflightService = preflightService;
        this.ineligibilityRegistry = ineligibilityRegistry;
        this.reservationService = reservationService;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    public MatcherCycleStatus runCycle() {
        if (!readinessService.isReady()) {
            return MatcherCycleStatus.WAITING_FOR_INDEXER;
        }

        if (executionAttemptRepository.existsUnresolved(
                deploymentId,
                ExecutionAttemptStatus.reservingStatuses()
        )) {
            return MatcherCycleStatus.ALREADY_RESERVED;
        }

        Optional<MatchProposal> proposal =
                projectionMatchService.findNextProposal();

        if (proposal.isEmpty()) {
            return MatcherCycleStatus.IDLE;
        }

        MatchProposal candidate = proposal.orElseThrow();
        MatchPreflightResult preflight = preflightService.check(candidate);

        if (!preflight.eligible()) {
            for (BigInteger orderId : preflight.ineligibleOrderIds()) {
                ineligibilityRegistry.markTemporarilyIneligible(
                        deploymentId,
                        orderId
                );
            }

            return MatcherCycleStatus.TEMPORARILY_INELIGIBLE;
        }

        return switch (reservationService.reserveIfCurrent(
                candidate,
                preflight.executorAddress()
        )) {
            case RESERVED -> MatcherCycleStatus.RESERVED;
            case ALREADY_RESERVED -> MatcherCycleStatus.ALREADY_RESERVED;
            case STALE -> MatcherCycleStatus.STALE;
        };
    }
}
