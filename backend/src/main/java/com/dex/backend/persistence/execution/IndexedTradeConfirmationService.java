package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.model.Hash256;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Clock;
import java.util.Objects;

@Service
public class IndexedTradeConfirmationService {

    private final ExecutionAttemptRepository repository;
    private final ExecutionAttemptLegRepository legRepository;
    private final Clock clock;

    @Autowired
    public IndexedTradeConfirmationService(
            ExecutionAttemptRepository repository,
            ExecutionAttemptLegRepository legRepository
    ) {
        this(repository, legRepository, Clock.systemUTC());
    }

    IndexedTradeConfirmationService(
            ExecutionAttemptRepository repository,
            ExecutionAttemptLegRepository legRepository,
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
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirmMatchingAttempt(
            String deploymentId,
            Hash256 transactionHash,
            BigInteger marketId,
            BigInteger buyOrderId,
            BigInteger sellOrderId,
            BigInteger fillLots,
            BigInteger executionPriceQuoteAtomsPerLot,
            BigInteger eventLogIndex
    ) {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        Objects.requireNonNull(
                transactionHash,
                "transactionHash must not be null"
        );
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(buyOrderId, "buyOrderId must not be null");
        Objects.requireNonNull(sellOrderId, "sellOrderId must not be null");
        Objects.requireNonNull(fillLots, "fillLots must not be null");
        Objects.requireNonNull(
                executionPriceQuoteAtomsPerLot,
                "executionPriceQuoteAtomsPerLot must not be null"
        );
        Objects.requireNonNull(eventLogIndex, "eventLogIndex must not be null");

        var attempt = repository.findByTransactionHashForUpdate(
                deploymentId,
                transactionHash
        );

        if (attempt.isEmpty()) {
            return false;
        }

        ExecutionAttemptEntity entity = attempt.orElseThrow();
        var legs = legRepository.findByIdAttemptIdOrderByIdLegIndex(
                entity.getAttemptId()
        );
        var matchingLeg = legs.stream()
                .filter(leg -> !leg.isConfirmed())
                .filter(leg -> leg.matches(
                        marketId,
                        buyOrderId,
                        sellOrderId,
                        fillLots,
                        executionPriceQuoteAtomsPerLot
                ))
                .findFirst();

        if (matchingLeg.isEmpty()) {
            throw new ExecutionAttemptTransitionException(
                    "Indexed trade does not match its execution attempt"
            );
        }

        matchingLeg.orElseThrow().markConfirmed(eventLogIndex);
        if (legs.stream().allMatch(ExecutionAttemptLegEntity::isConfirmed)) {
            entity.markConfirmed(clock.instant());
        }
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirmMatchingAttempt(
            String deploymentId,
            Hash256 transactionHash,
            BigInteger marketId,
            BigInteger buyOrderId,
            BigInteger sellOrderId,
            BigInteger fillLots,
            BigInteger executionPriceQuoteAtomsPerLot
    ) {
        return confirmMatchingAttempt(
                deploymentId,
                transactionHash,
                marketId,
                buyOrderId,
                sellOrderId,
                fillLots,
                executionPriceQuoteAtomsPerLot,
                BigInteger.ZERO
        );
    }
}
