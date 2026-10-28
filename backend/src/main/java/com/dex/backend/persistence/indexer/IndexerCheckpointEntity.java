package com.dex.backend.persistence.indexer;

import com.dex.backend.blockchain.model.Hash256;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "indexer_checkpoints")
public class IndexerCheckpointEntity {

    @Id
    @Column(name = "deployment_id", length = 128, nullable = false)
    private String deploymentId;

    @Column(name = "last_processed_block_number", precision = 78, scale = 0, nullable = false)
    private BigInteger lastProcessedBlockNumber;

    @Column(name = "last_processed_block_hash", length = 66, nullable = false)
    private Hash256 lastProcessedBlockHash;

    @Column(name = "latest_observed_block_number", precision = 78, scale = 0, nullable = false)
    private BigInteger latestObservedBlockNumber;

    @Column(name = "latest_observed_block_hash", length = 66, nullable = false)
    private Hash256 latestObservedBlockHash;

    @Column(name = "last_successful_sync_at", nullable = false)
    private Instant lastSuccessfulSyncAt;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "last_error_message", columnDefinition = "text")
    private String lastErrorMessage;

    @Column(name = "paused", nullable = false)
    private boolean paused;

    @Column(name = "pause_reason", columnDefinition = "text")
    private String pauseReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IndexerCheckpointEntity() {
    }

    public IndexerCheckpointEntity(
            String deploymentId,
            BigInteger lastProcessedBlockNumber,
            Hash256 lastProcessedBlockHash,
            BigInteger latestObservedBlockNumber,
            Hash256 latestObservedBlockHash,
            Instant lastSuccessfulSyncAt,
            Instant lastErrorAt,
            String lastErrorMessage,
            boolean paused,
            String pauseReason
    ) {
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        this.lastProcessedBlockNumber = Objects.requireNonNull(
                lastProcessedBlockNumber,
                "lastProcessedBlockNumber must not be null"
        );
        this.lastProcessedBlockHash = Objects.requireNonNull(
                lastProcessedBlockHash,
                "lastProcessedBlockHash must not be null"
        );
        this.latestObservedBlockNumber = Objects.requireNonNull(
                latestObservedBlockNumber,
                "latestObservedBlockNumber must not be null"
        );
        this.latestObservedBlockHash = Objects.requireNonNull(
                latestObservedBlockHash,
                "latestObservedBlockHash must not be null"
        );
        this.lastSuccessfulSyncAt = Objects.requireNonNull(
                lastSuccessfulSyncAt,
                "lastSuccessfulSyncAt must not be null"
        );
        this.lastErrorAt = lastErrorAt;
        this.lastErrorMessage = lastErrorMessage;
        this.paused = paused;
        this.pauseReason = pauseReason;
    }

    @PrePersist
    @PreUpdate
    void setUpdatedAt() {
        updatedAt = Instant.now();
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public BigInteger getLastProcessedBlockNumber() {
        return lastProcessedBlockNumber;
    }

    public Hash256 getLastProcessedBlockHash() {
        return lastProcessedBlockHash;
    }

    public BigInteger getLatestObservedBlockNumber() {
        return latestObservedBlockNumber;
    }

    public Hash256 getLatestObservedBlockHash() {
        return latestObservedBlockHash;
    }

    public Instant getLastSuccessfulSyncAt() {
        return lastSuccessfulSyncAt;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
