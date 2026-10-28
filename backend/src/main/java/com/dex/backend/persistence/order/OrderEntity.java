package com.dex.backend.persistence.order;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @EmbeddedId
    private OrderEntityId id;

    @Column(name = "market_id", precision = 78, scale = 0, nullable = false)
    private BigInteger marketId;

    @Column(name = "owner_address", length = 42, nullable = false)
    private EthereumAddress ownerAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 4, nullable = false)
    private OrderSide side;

    @Column(name = "limit_price_quote_atoms_per_lot", precision = 78, scale = 0, nullable = false)
    private BigInteger limitPriceQuoteAtomsPerLot;

    @Column(name = "original_lots", precision = 78, scale = 0, nullable = false)
    private BigInteger originalLots;

    @Column(name = "remaining_lots", precision = 78, scale = 0, nullable = false)
    private BigInteger remainingLots;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OrderStatus status;

    @Column(name = "creation_block_number", precision = 78, scale = 0, nullable = false)
    private BigInteger creationBlockNumber;

    @Column(name = "creation_transaction_index", precision = 78, scale = 0, nullable = false)
    private BigInteger creationTransactionIndex;

    @Column(name = "creation_log_index", precision = 78, scale = 0, nullable = false)
    private BigInteger creationLogIndex;

    @Column(name = "creation_transaction_hash", length = 66, nullable = false)
    private Hash256 creationTransactionHash;

    @Column(name = "creation_block_hash", length = 66, nullable = false)
    private Hash256 creationBlockHash;

    @Column(name = "block_timestamp", nullable = false)
    private Instant blockTimestamp;

    @Column(name = "projection_updated_at", nullable = false)
    private Instant projectionUpdatedAt;

    protected OrderEntity() {
    }

    public OrderEntity(
            OrderEntityId id,
            BigInteger marketId,
            EthereumAddress ownerAddress,
            OrderSide side,
            BigInteger limitPriceQuoteAtomsPerLot,
            BigInteger originalLots,
            BigInteger remainingLots,
            OrderStatus status,
            BigInteger creationBlockNumber,
            BigInteger creationTransactionIndex,
            BigInteger creationLogIndex,
            Hash256 creationTransactionHash,
            Hash256 creationBlockHash,
            Instant blockTimestamp
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.marketId = Objects.requireNonNull(marketId, "marketId must not be null");
        this.ownerAddress = Objects.requireNonNull(ownerAddress, "ownerAddress must not be null");
        this.side = Objects.requireNonNull(side, "side must not be null");
        this.limitPriceQuoteAtomsPerLot = Objects.requireNonNull(
                limitPriceQuoteAtomsPerLot,
                "limitPriceQuoteAtomsPerLot must not be null"
        );
        this.originalLots = Objects.requireNonNull(originalLots, "originalLots must not be null");
        this.remainingLots = Objects.requireNonNull(remainingLots, "remainingLots must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.creationBlockNumber = Objects.requireNonNull(creationBlockNumber, "creationBlockNumber must not be null");
        this.creationTransactionIndex = Objects.requireNonNull(
                creationTransactionIndex,
                "creationTransactionIndex must not be null"
        );
        this.creationLogIndex = Objects.requireNonNull(creationLogIndex, "creationLogIndex must not be null");
        this.creationTransactionHash = Objects.requireNonNull(
                creationTransactionHash,
                "creationTransactionHash must not be null"
        );
        this.creationBlockHash = Objects.requireNonNull(creationBlockHash, "creationBlockHash must not be null");
        this.blockTimestamp = Objects.requireNonNull(blockTimestamp, "blockTimestamp must not be null");
    }

    @PrePersist
    @PreUpdate
    void setProjectionUpdatedAt() {
        projectionUpdatedAt = Instant.now();
    }

    public OrderEntityId getId() {
        return id;
    }

    public BigInteger getMarketId() {
        return marketId;
    }

    public EthereumAddress getOwnerAddress() {
        return ownerAddress;
    }

    public OrderSide getSide() {
        return side;
    }

    public BigInteger getLimitPriceQuoteAtomsPerLot() {
        return limitPriceQuoteAtomsPerLot;
    }

    public BigInteger getOriginalLots() {
        return originalLots;
    }

    public BigInteger getRemainingLots() {
        return remainingLots;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigInteger getCreationBlockNumber() {
        return creationBlockNumber;
    }

    public BigInteger getCreationTransactionIndex() {
        return creationTransactionIndex;
    }

    public BigInteger getCreationLogIndex() {
        return creationLogIndex;
    }

    public Hash256 getCreationTransactionHash() {
        return creationTransactionHash;
    }

    public Hash256 getCreationBlockHash() {
        return creationBlockHash;
    }

    public Instant getBlockTimestamp() {
        return blockTimestamp;
    }

    public Instant getProjectionUpdatedAt() {
        return projectionUpdatedAt;
    }
}
