package com.dex.backend.matcher;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.execution.ExecutionAttemptEntity;
import com.dex.backend.persistence.execution.ExecutionAttemptRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptLegEntity;
import com.dex.backend.persistence.execution.ExecutionAttemptLegRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

@Service
public class MatchReservationService {

    private final ExecutionAttemptRepository executionAttemptRepository;
    private final ExecutionAttemptLegRepository legRepository;
    private final ProjectionMatchService projectionMatchService;
    private final String deploymentId;
    private final Clock clock;

    @Autowired
    public MatchReservationService(
            ExecutionAttemptRepository executionAttemptRepository,
            ExecutionAttemptLegRepository legRepository,
            ProjectionMatchService projectionMatchService,
            DexProperties properties
    ) {
        this(
                executionAttemptRepository,
                legRepository,
                projectionMatchService,
                properties.blockchain().deploymentId(),
                Clock.systemUTC()
        );
    }

    MatchReservationService(
            ExecutionAttemptRepository executionAttemptRepository,
            ExecutionAttemptLegRepository legRepository,
            ProjectionMatchService projectionMatchService,
            String deploymentId,
            Clock clock
    ) {
        this.executionAttemptRepository = Objects.requireNonNull(
                executionAttemptRepository,
                "executionAttemptRepository must not be null"
        );
        this.legRepository = Objects.requireNonNull(
                legRepository,
                "legRepository must not be null"
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
            MatchBatch proposal,
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

        Optional<MatchBatch> currentProposal =
                projectionMatchService.findNextBatch();

        if (currentProposal.isEmpty()
                || !currentProposal.orElseThrow().equals(proposal)) {
            return MatchReservationResult.STALE;
        }

        UUID attemptId = UUID.randomUUID();
        executionAttemptRepository.saveAndFlush(
                ExecutionAttemptEntity.reserved(
                        attemptId,
                        proposal,
                        executorAddress,
                        clock.instant().truncatedTo(ChronoUnit.MICROS)
                )
        );
        List<ExecutionAttemptLegEntity> legs = new ArrayList<>();
        for (int index = 0; index < proposal.legs().size(); index++) {
            legs.add(new ExecutionAttemptLegEntity(
                    attemptId,
                    index,
                    proposal.legs().get(index)
            ));
        }
        legRepository.saveAllAndFlush(legs);

        return MatchReservationResult.RESERVED;
    }

    public MatchReservationResult reserveIfCurrent(
            MatchProposal proposal,
            EthereumAddress executorAddress
    ) {
        return reserveIfCurrent(MatchBatch.single(proposal), executorAddress);
    }

    private boolean hasUnresolvedAttempt() {
        return executionAttemptRepository.existsUnresolved(
                deploymentId,
                ExecutionAttemptStatus.reservingStatuses()
        );
    }
}
