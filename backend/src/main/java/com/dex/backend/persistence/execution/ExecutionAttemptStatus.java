package com.dex.backend.persistence.execution;

import java.util.Set;

public enum ExecutionAttemptStatus {
    RESERVED(true, false),
    SIGNED(true, false),
    SUBMITTED(true, false),
    /** The indexed trade has already updated the confirmed projections. */
    CONFIRMED(false, true),
    REVERTED(false, true),
    UNCERTAIN(true, false),
    ABANDONED(false, true);

    private static final Set<ExecutionAttemptStatus> RESERVING_STATUSES = Set.of(
            RESERVED,
            SIGNED,
            SUBMITTED,
            UNCERTAIN
    );

    private final boolean reservesOrders;
    private final boolean terminal;

    ExecutionAttemptStatus(boolean reservesOrders, boolean terminal) {
        this.reservesOrders = reservesOrders;
        this.terminal = terminal;
    }

    public boolean reservesOrders() {
        return reservesOrders;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean canTransitionTo(ExecutionAttemptStatus target) {
        if (target == null || terminal) {
            return false;
        }

        return switch (this) {
            case RESERVED -> target == SIGNED || target == ABANDONED;
            case SIGNED -> target == SUBMITTED
                    || target == CONFIRMED
                    || target == UNCERTAIN;
            case SUBMITTED -> target == CONFIRMED
                    || target == REVERTED
                    || target == UNCERTAIN;
            case UNCERTAIN -> target == UNCERTAIN
                    || target == SUBMITTED
                    || target == CONFIRMED
                    || target == REVERTED;
            case CONFIRMED, REVERTED, ABANDONED -> false;
        };
    }

    public static Set<ExecutionAttemptStatus> reservingStatuses() {
        return RESERVING_STATUSES;
    }
}
