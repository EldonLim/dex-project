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
    private final Clock clock;

    @Autowired
    public IndexedTradeConfirmationService(
            ExecutionAttemptRepository repository
    ) {
        this(repository, Clock.systemUTC());
    }

    IndexedTradeConfirmationService(
            ExecutionAttemptRepository repository,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository must not be null"
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
            BigInteger fillLots
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

        var attempt = repository.findByTransactionHashForUpdate(
                deploymentId,
                transactionHash
        );

        if (attempt.isEmpty()) {
            return false;
        }

        ExecutionAttemptEntity entity = attempt.orElseThrow();

        if (!entity.getMarketId().equals(marketId)
                || !entity.getBuyOrderId().equals(buyOrderId)
                || !entity.getSellOrderId().equals(sellOrderId)
                || !entity.getFillLots().equals(fillLots)) {
            throw new ExecutionAttemptTransitionException(
                    "Indexed trade does not match its execution attempt"
            );
        }

        entity.markConfirmed(clock.instant());
        return true;
    }
}
