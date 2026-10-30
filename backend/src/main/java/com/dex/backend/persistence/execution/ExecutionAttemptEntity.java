package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_attempts")
public class ExecutionAttemptEntity {

    @Id
    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

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

    @Column(name = "executor_address", length = 42, nullable = false, updatable = false)
    private EthereumAddress executorAddress;

    @Column(name = "executor_nonce", precision = 78, scale = 0)
    private BigInteger executorNonce;

    @Column(name = "transaction_hash", length = 66)
    private Hash256 transactionHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ExecutionAttemptStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "revert_reason")
    private String revertReason;

    protected ExecutionAttemptEntity() {
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public String getDeploymentId() {
        return deploymentId;
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

    public EthereumAddress getExecutorAddress() {
        return executorAddress;
    }

    public BigInteger getExecutorNonce() {
        return executorNonce;
    }

    public Hash256 getTransactionHash() {
        return transactionHash;
    }

    public ExecutionAttemptStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getRevertReason() {
        return revertReason;
    }
}
