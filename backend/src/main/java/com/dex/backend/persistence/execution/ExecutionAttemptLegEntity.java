package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.execution.SettlementLeg;
import com.dex.backend.matcher.MatchProposal;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "execution_attempt_legs")
public class ExecutionAttemptLegEntity {

    @EmbeddedId
    private ExecutionAttemptLegId id;

    @Column(name = "deployment_id", length = 128, nullable = false, updatable = false)
    private String deploymentId;

    @Column(name = "market_id", precision = 78, scale = 0, nullable = false, updatable = false)
    private BigInteger marketId;

    @Column(name = "buy_order_id", precision = 78, scale = 0, nullable = false, updatable = false)
    private BigInteger buyOrderId;

    @Column(name = "sell_order_id", precision = 78, scale = 0, nullable = false, updatable = false)
    private BigInteger sellOrderId;

    @Column(name = "fill_lots", precision = 78, scale = 0, nullable = false, updatable = false)
    private BigInteger fillLots;

    @Column(name = "execution_price_quote_atoms_per_lot", precision = 78, scale = 0, nullable = false, updatable = false)
    private BigInteger executionPriceQuoteAtomsPerLot;

    @Column(name = "confirmed_event_log_index", precision = 78, scale = 0)
    private BigInteger confirmedEventLogIndex;

    protected ExecutionAttemptLegEntity() {
    }

    public ExecutionAttemptLegEntity(
            UUID attemptId,
            int legIndex,
            MatchProposal proposal
    ) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        this.id = new ExecutionAttemptLegId(attemptId, legIndex);
        this.deploymentId = proposal.deploymentId();
        this.marketId = proposal.marketId();
        this.buyOrderId = proposal.buyOrderId();
        this.sellOrderId = proposal.sellOrderId();
        this.fillLots = proposal.fillLots();
        this.executionPriceQuoteAtomsPerLot =
                proposal.executionPriceQuoteAtomsPerLot();
    }

    public SettlementLeg toSettlementLeg() {
        return new SettlementLeg(
                buyOrderId,
                sellOrderId,
                fillLots,
                executionPriceQuoteAtomsPerLot
        );
    }

    public boolean matches(
            BigInteger marketId,
            BigInteger buyOrderId,
            BigInteger sellOrderId,
            BigInteger fillLots,
            BigInteger executionPrice
    ) {
        return this.marketId.equals(marketId)
                && this.buyOrderId.equals(buyOrderId)
                && this.sellOrderId.equals(sellOrderId)
                && this.fillLots.equals(fillLots)
                && this.executionPriceQuoteAtomsPerLot.equals(executionPrice);
    }

    public void markConfirmed(BigInteger logIndex) {
        Objects.requireNonNull(logIndex, "logIndex must not be null");
        if (logIndex.signum() < 0) {
            throw new IllegalArgumentException("logIndex must be non-negative");
        }
        if (confirmedEventLogIndex != null) {
            throw new ExecutionAttemptTransitionException(
                    "Execution attempt leg is already confirmed"
            );
        }
        confirmedEventLogIndex = logIndex;
    }

    public boolean isConfirmed() {
        return confirmedEventLogIndex != null;
    }

    public ExecutionAttemptLegId getId() { return id; }
    public String getDeploymentId() { return deploymentId; }
    public BigInteger getMarketId() { return marketId; }
    public BigInteger getBuyOrderId() { return buyOrderId; }
    public BigInteger getSellOrderId() { return sellOrderId; }
    public BigInteger getFillLots() { return fillLots; }
    public BigInteger getExecutionPriceQuoteAtomsPerLot() { return executionPriceQuoteAtomsPerLot; }
    public BigInteger getConfirmedEventLogIndex() { return confirmedEventLogIndex; }
}
