package com.dex.backend.persistence.execution;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionAttemptStatusTests {

    @Test
    void exposesTheFourOrderReservingStates() {
        assertTrue(ExecutionAttemptStatus.reservingStatuses().equals(Set.of(
                ExecutionAttemptStatus.RESERVED,
                ExecutionAttemptStatus.SIGNED,
                ExecutionAttemptStatus.SUBMITTED,
                ExecutionAttemptStatus.UNCERTAIN
        )));

        for (ExecutionAttemptStatus status : ExecutionAttemptStatus.values()) {
            assertTrue(
                    status.reservesOrders()
                            == ExecutionAttemptStatus.reservingStatuses()
                            .contains(status)
            );
        }
    }

    @Test
    void permitsOnlyTheDefinedLifecycleTransitions() {
        assertTransitions(
                ExecutionAttemptStatus.RESERVED,
                ExecutionAttemptStatus.SIGNED,
                ExecutionAttemptStatus.ABANDONED
        );
        assertTransitions(
                ExecutionAttemptStatus.SIGNED,
                ExecutionAttemptStatus.SUBMITTED,
                ExecutionAttemptStatus.UNCERTAIN
        );
        assertTransitions(
                ExecutionAttemptStatus.SUBMITTED,
                ExecutionAttemptStatus.CONFIRMED,
                ExecutionAttemptStatus.REVERTED,
                ExecutionAttemptStatus.UNCERTAIN
        );
        assertTransitions(
                ExecutionAttemptStatus.UNCERTAIN,
                ExecutionAttemptStatus.UNCERTAIN,
                ExecutionAttemptStatus.SUBMITTED,
                ExecutionAttemptStatus.CONFIRMED,
                ExecutionAttemptStatus.REVERTED
        );
    }

    @Test
    void terminalStatesPermitNoFurtherTransitions() {
        for (ExecutionAttemptStatus terminal : Set.of(
                ExecutionAttemptStatus.CONFIRMED,
                ExecutionAttemptStatus.REVERTED,
                ExecutionAttemptStatus.ABANDONED
        )) {
            assertTrue(terminal.isTerminal());

            for (ExecutionAttemptStatus target
                    : ExecutionAttemptStatus.values()) {
                assertFalse(terminal.canTransitionTo(target));
            }
        }
    }

    private static void assertTransitions(
            ExecutionAttemptStatus source,
            ExecutionAttemptStatus... permitted
    ) {
        Set<ExecutionAttemptStatus> expected = Set.of(permitted);

        for (ExecutionAttemptStatus target
                : ExecutionAttemptStatus.values()) {
            assertTrue(
                    source.canTransitionTo(target) == expected.contains(target),
                    () -> "Unexpected transition " + source + " -> " + target
            );
        }
    }
}
