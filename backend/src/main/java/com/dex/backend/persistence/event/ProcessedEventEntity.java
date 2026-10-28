package com.dex.backend.persistence.event;

import com.dex.backend.blockchain.event.DexEventType;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "processed_events")
public class ProcessedEventEntity {

    @EmbeddedId
    private ProcessedEventEntityId id;

    @Column(name = "contract_address", length = 42, nullable = false)
    private EthereumAddress contractAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_name", length = 64, nullable = false)
    private DexEventType eventName;

    @Column(name = "block_number", precision = 78, scale = 0, nullable = false)
    private BigInteger blockNumber;

    @Column(name = "block_hash", length = 66, nullable = false)
    private Hash256 blockHash;

    @Column(name = "transaction_index", precision = 78, scale = 0, nullable = false)
    private BigInteger transactionIndex;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {
    }

    public ProcessedEventEntity(
            ProcessedEventEntityId id,
            EthereumAddress contractAddress,
            DexEventType eventName,
            BigInteger blockNumber,
            Hash256 blockHash,
            BigInteger transactionIndex
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.contractAddress = Objects.requireNonNull(contractAddress, "contractAddress must not be null");
        this.eventName = Objects.requireNonNull(eventName, "eventName must not be null");
        this.blockNumber = Objects.requireNonNull(blockNumber, "blockNumber must not be null");
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash must not be null");
        this.transactionIndex = Objects.requireNonNull(transactionIndex, "transactionIndex must not be null");
    }

    @PrePersist
    void setProcessedAt() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }

    public ProcessedEventEntityId getId() {
        return id;
    }

    public EthereumAddress getContractAddress() {
        return contractAddress;
    }

    public DexEventType getEventName() {
        return eventName;
    }

    public BigInteger getBlockNumber() {
        return blockNumber;
    }

    public Hash256 getBlockHash() {
        return blockHash;
    }

    public BigInteger getTransactionIndex() {
        return transactionIndex;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
