package com.dex.backend.persistence.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ExecutionAttemptLegId implements Serializable {

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "leg_index", nullable = false, updatable = false)
    private int legIndex;

    protected ExecutionAttemptLegId() {
    }

    public ExecutionAttemptLegId(UUID attemptId, int legIndex) {
        this.attemptId = Objects.requireNonNull(
                attemptId,
                "attemptId must not be null"
        );
        if (legIndex < 0 || legIndex >= 10) {
            throw new IllegalArgumentException("legIndex must be between 0 and 9");
        }
        this.legIndex = legIndex;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public int getLegIndex() {
        return legIndex;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof ExecutionAttemptLegId other)) return false;
        return legIndex == other.legIndex && attemptId.equals(other.attemptId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attemptId, legIndex);
    }
}
