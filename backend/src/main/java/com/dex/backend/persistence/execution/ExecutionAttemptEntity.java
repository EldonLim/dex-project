package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.matcher.MatchProposal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "execution_attempts")
public class ExecutionAttemptEntity {

    static final int MAX_ERROR_CODE_LENGTH = 64;
    static final int MAX_DETAIL_LENGTH = 2_000;

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

    @Column(
            name = "execution_price_quote_atoms_per_lot",
            precision = 78,
            scale = 0,
            nullable = false,
            updatable = false
    )
    private BigInteger executionPriceQuoteAtomsPerLot;

    @Column(name = "executor_address", length = 42, nullable = false, updatable = false)
    private EthereumAddress executorAddress;

    @Column(name = "executor_nonce", precision = 78, scale = 0)
    private BigInteger executorNonce;

    @Column(name = "transaction_hash", length = 66)
    private Hash256 transactionHash;

    @Column(name = "signed_transaction_hex")
    private String signedTransactionHex;

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

    private ExecutionAttemptEntity(
            UUID attemptId,
            MatchProposal proposal,
            EthereumAddress executorAddress,
            Instant createdAt
    ) {
        this.attemptId = Objects.requireNonNull(
                attemptId,
                "attemptId must not be null"
        );
        Objects.requireNonNull(proposal, "proposal must not be null");
        this.deploymentId = proposal.deploymentId();
        this.marketId = proposal.marketId();
        this.buyOrderId = proposal.buyOrderId();
        this.sellOrderId = proposal.sellOrderId();
        this.fillLots = proposal.fillLots();
        this.executionPriceQuoteAtomsPerLot =
                proposal.executionPriceQuoteAtomsPerLot();
        this.executorAddress = Objects.requireNonNull(
                executorAddress,
                "executorAddress must not be null"
        );
        this.status = ExecutionAttemptStatus.RESERVED;
        this.createdAt = Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
        this.updatedAt = createdAt;
    }

    public static ExecutionAttemptEntity reserved(
            UUID attemptId,
            MatchProposal proposal,
            EthereumAddress executorAddress,
            Instant createdAt
    ) {
        return new ExecutionAttemptEntity(
                attemptId,
                proposal,
                executorAddress,
                createdAt
        );
    }

    public void markSigned(
            PreparedSettlement preparedSettlement,
            Instant occurredAt
    ) {
        Objects.requireNonNull(
                preparedSettlement,
                "preparedSettlement must not be null"
        );
        requirePreparedSettlementMatches(preparedSettlement);
        requireTimestamp(occurredAt);
        requireTransition(ExecutionAttemptStatus.SIGNED);

        executorNonce = preparedSettlement.nonce();
        transactionHash = preparedSettlement.transactionHash();
        signedTransactionHex = preparedSettlement.signedTransactionHex();
        status = ExecutionAttemptStatus.SIGNED;
        updatedAt = occurredAt;
        clearFailureDetails();
    }

    public void markSubmitted(Instant occurredAt) {
        requireTimestamp(occurredAt);
        requireTransition(ExecutionAttemptStatus.SUBMITTED);
        requireTransactionIdentity();

        if (submittedAt == null) {
            submittedAt = occurredAt;
        }
        status = ExecutionAttemptStatus.SUBMITTED;
        updatedAt = occurredAt;
        clearFailureDetails();
    }

    public void markUncertain(
            String code,
            String message,
            Instant occurredAt
    ) {
        String normalizedCode = requireBoundedText(
                code,
                "errorCode",
                MAX_ERROR_CODE_LENGTH
        );
        String normalizedMessage = requireBoundedText(
                message,
                "errorMessage",
                MAX_DETAIL_LENGTH
        );
        requireTimestamp(occurredAt);
        requireTransition(ExecutionAttemptStatus.UNCERTAIN);
        requireTransactionIdentity();

        status = ExecutionAttemptStatus.UNCERTAIN;
        updatedAt = occurredAt;
        errorCode = normalizedCode;
        errorMessage = normalizedMessage;
    }

    /**
     * Marks the attempt confirmed only after the indexed trade has updated the
     * confirmed projections. A successful receipt alone is not sufficient.
     */
    public void markConfirmed(Instant occurredAt) {
        requireTimestamp(occurredAt);
        requireTransition(ExecutionAttemptStatus.CONFIRMED);
        requireTransactionIdentity();

        status = ExecutionAttemptStatus.CONFIRMED;
        updatedAt = occurredAt;
        resolvedAt = occurredAt;
        clearFailureDetails();
    }

    public void markReverted(String reason, Instant occurredAt) {
        String normalizedReason = optionalBoundedText(
                reason,
                "revertReason",
                MAX_DETAIL_LENGTH
        );
        requireTimestamp(occurredAt);
        requireTransition(ExecutionAttemptStatus.REVERTED);
        requireTransactionIdentity();

        status = ExecutionAttemptStatus.REVERTED;
        updatedAt = occurredAt;
        resolvedAt = occurredAt;
        revertReason = normalizedReason;
        clearFailureDetails();
    }

    public void abandon(
            String code,
            String message,
            Instant occurredAt
    ) {
        String normalizedCode = requireBoundedText(
                code,
                "errorCode",
                MAX_ERROR_CODE_LENGTH
        );
        String normalizedMessage = requireBoundedText(
                message,
                "errorMessage",
                MAX_DETAIL_LENGTH
        );
        requireTimestamp(occurredAt);
        requireTransition(ExecutionAttemptStatus.ABANDONED);

        status = ExecutionAttemptStatus.ABANDONED;
        updatedAt = occurredAt;
        resolvedAt = occurredAt;
        errorCode = normalizedCode;
        errorMessage = normalizedMessage;
    }

    private void requirePreparedSettlementMatches(
            PreparedSettlement preparedSettlement
    ) {
        SettlementRequest request = preparedSettlement.request();

        if (!deploymentId.equals(request.deploymentId())
                || !marketId.equals(request.marketId())
                || !buyOrderId.equals(request.buyOrderId())
                || !sellOrderId.equals(request.sellOrderId())
                || !fillLots.equals(request.fillLots())
                || !executionPriceQuoteAtomsPerLot.equals(
                request.executionPriceQuoteAtomsPerLot()
        )
                || !executorAddress.equals(
                        preparedSettlement.executorAddress()
                )) {
            throw new IllegalArgumentException(
                    "prepared settlement does not match the reserved attempt"
            );
        }
    }

    private void requireTransition(ExecutionAttemptStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ExecutionAttemptTransitionException(
                    "Cannot transition execution attempt from "
                            + status + " to " + target
            );
        }
    }

    private void requireTimestamp(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        if (occurredAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "occurredAt must not be before the previous update"
            );
        }
    }

    private void requireTransactionIdentity() {
        if (executorNonce == null
                || transactionHash == null
                || signedTransactionHex == null) {
            throw new ExecutionAttemptTransitionException(
                    "Signed transaction identity is required for status "
                            + status
            );
        }
    }

    public PreparedSettlement toPreparedSettlement() {
        requireTransactionIdentity();

        return new PreparedSettlement(
                toSettlementRequest(),
                executorAddress,
                executorNonce,
                transactionHash,
                signedTransactionHex
        );
    }

    public SettlementRequest toSettlementRequest() {
        return new SettlementRequest(
                deploymentId,
                marketId,
                buyOrderId,
                sellOrderId,
                fillLots,
                executionPriceQuoteAtomsPerLot
        );
    }

    private void clearFailureDetails() {
        errorCode = null;
        errorMessage = null;
    }

    private static String requireBoundedText(
            String value,
            String fieldName,
            int maximumLength
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.strip();

        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    fieldName + " must be nonblank and at most "
                            + maximumLength + " characters"
            );
        }
        return normalized;
    }

    private static String optionalBoundedText(
            String value,
            String fieldName,
            int maximumLength
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireBoundedText(value, fieldName, maximumLength);
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

    public BigInteger getExecutionPriceQuoteAtomsPerLot() {
        return executionPriceQuoteAtomsPerLot;
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
