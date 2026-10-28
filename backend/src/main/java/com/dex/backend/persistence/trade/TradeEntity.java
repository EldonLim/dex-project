package com.dex.backend.persistence.trade;

import com.dex.backend.blockchain.model.Hash256;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "trades")
public class TradeEntity {

    @EmbeddedId
    private TradeEntityId id;

    @Column(name = "trade_id", precision = 78, scale = 0)
    private BigInteger tradeId;

    @Column(name = "market_id", precision = 78, scale = 0, nullable = false)
    private BigInteger marketId;

    @Column(name = "buy_order_id", precision = 78, scale = 0, nullable = false)
    private BigInteger buyOrderId;

    @Column(name = "sell_order_id", precision = 78, scale = 0, nullable = false)
    private BigInteger sellOrderId;

    @Column(name = "fill_lots", precision = 78, scale = 0, nullable = false)
    private BigInteger fillLots;

    @Column(name = "execution_price_quote_atoms_per_lot", precision = 78, scale = 0, nullable = false)
    private BigInteger executionPriceQuoteAtomsPerLot;

    @Column(name = "buy_order_remaining_lots", precision = 78, scale = 0, nullable = false)
    private BigInteger buyOrderRemainingLots;

    @Column(name = "sell_order_remaining_lots", precision = 78, scale = 0, nullable = false)
    private BigInteger sellOrderRemainingLots;

    @Column(name = "block_number", precision = 78, scale = 0, nullable = false)
    private BigInteger blockNumber;

    @Column(name = "transaction_index", precision = 78, scale = 0, nullable = false)
    private BigInteger transactionIndex;

    @Column(name = "block_hash", length = 66, nullable = false)
    private Hash256 blockHash;

    @Column(name = "block_timestamp", nullable = false)
    private Instant blockTimestamp;

    @Column(name = "projection_created_at", nullable = false, updatable = false)
    private Instant projectionCreatedAt;

    protected TradeEntity() {
    }

    public TradeEntity(
            TradeEntityId id,
            BigInteger tradeId,
            BigInteger marketId,
            BigInteger buyOrderId,
            BigInteger sellOrderId,
            BigInteger fillLots,
            BigInteger executionPriceQuoteAtomsPerLot,
            BigInteger buyOrderRemainingLots,
            BigInteger sellOrderRemainingLots,
            BigInteger blockNumber,
            BigInteger transactionIndex,
            Hash256 blockHash,
            Instant blockTimestamp
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tradeId = tradeId;
        this.marketId = Objects.requireNonNull(marketId, "marketId must not be null");
        this.buyOrderId = Objects.requireNonNull(buyOrderId, "buyOrderId must not be null");
        this.sellOrderId = Objects.requireNonNull(sellOrderId, "sellOrderId must not be null");
        this.fillLots = Objects.requireNonNull(fillLots, "fillLots must not be null");
        this.executionPriceQuoteAtomsPerLot = Objects.requireNonNull(
                executionPriceQuoteAtomsPerLot,
                "executionPriceQuoteAtomsPerLot must not be null"
        );
        this.buyOrderRemainingLots = Objects.requireNonNull(
                buyOrderRemainingLots,
                "buyOrderRemainingLots must not be null"
        );
        this.sellOrderRemainingLots = Objects.requireNonNull(
                sellOrderRemainingLots,
                "sellOrderRemainingLots must not be null"
        );
        this.blockNumber = Objects.requireNonNull(blockNumber, "blockNumber must not be null");
        this.transactionIndex = Objects.requireNonNull(transactionIndex, "transactionIndex must not be null");
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash must not be null");
        this.blockTimestamp = Objects.requireNonNull(blockTimestamp, "blockTimestamp must not be null");
    }

    @PrePersist
    void setProjectionCreatedAt() {
        if (projectionCreatedAt == null) {
            projectionCreatedAt = Instant.now();
        }
    }

    public TradeEntityId getId() {
        return id;
    }

    public BigInteger getTradeId() {
        return tradeId;
    }

    public BigInteger getMarketId() {
        return marketId;
    }

    public BigInteger getBuyOrderId() {
        return buyOrderId;
    }

    public BigInteger getSellOrderId() {
        return sellOrderId;
    }

    public BigInteger getFillLots() {
        return fillLots;
    }

    public BigInteger getExecutionPriceQuoteAtomsPerLot() {
        return executionPriceQuoteAtomsPerLot;
    }

    public BigInteger getBuyOrderRemainingLots() {
        return buyOrderRemainingLots;
    }

    public BigInteger getSellOrderRemainingLots() {
        return sellOrderRemainingLots;
    }

    public BigInteger getBlockNumber() {
        return blockNumber;
    }

    public BigInteger getTransactionIndex() {
        return transactionIndex;
    }

    public Hash256 getBlockHash() {
        return blockHash;
    }

    public Instant getBlockTimestamp() {
        return blockTimestamp;
    }

    public Instant getProjectionCreatedAt() {
        return projectionCreatedAt;
    }
}
