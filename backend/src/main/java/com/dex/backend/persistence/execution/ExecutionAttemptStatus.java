package com.dex.backend.persistence.execution;

import java.util.Set;

public enum ExecutionAttemptStatus {
    RESERVED(true),
    SIGNED(true),
    SUBMITTED(true),
    /** The indexed trade has already updated the confirmed projections. */
    CONFIRMED(false),
    REVERTED(false),
    UNCERTAIN(true),
    ABANDONED(false);

    private static final Set<ExecutionAttemptStatus> RESERVING_STATUSES = Set.of(
            RESERVED,
            SIGNED,
            SUBMITTED,
            UNCERTAIN
    );

    private final boolean reservesOrders;

    ExecutionAttemptStatus(boolean reservesOrders) {
        this.reservesOrders = reservesOrders;
    }

    public boolean reservesOrders() {
        return reservesOrders;
    }

    public static Set<ExecutionAttemptStatus> reservingStatuses() {
        return RESERVING_STATUSES;
    }
}
