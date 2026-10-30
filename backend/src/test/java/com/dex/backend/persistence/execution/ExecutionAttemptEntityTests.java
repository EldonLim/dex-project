package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.matcher.MatchProposal;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionAttemptEntityTests {

    private static final String DEPLOYMENT_ID = "execution-lifecycle-test";
    private static final Instant RESERVED_AT =
            Instant.parse("2026-01-01T00:00:00Z");
    private static final EthereumAddress EXECUTOR = address(999);

    @Test
    void signedSubmittedAndConfirmedLifecyclePreservesIdentity() {
        ExecutionAttemptEntity attempt = reservedAttempt();
        BigInteger nonce = BigInteger.ONE.shiftLeft(200);
        PreparedSettlement prepared = prepared(nonce);

        attempt.markSigned(prepared, RESERVED_AT.plusSeconds(1));
        attempt.markSubmitted(RESERVED_AT.plusSeconds(2));
        attempt.markConfirmed(RESERVED_AT.plusSeconds(3));

        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.CONFIRMED,
                        attempt.getStatus()
                ),
                () -> assertEquals(nonce, attempt.getExecutorNonce()),
                () -> assertEquals(
                        prepared.transactionHash(),
                        attempt.getTransactionHash()
                ),
                () -> assertEquals(
                        prepared,
                        attempt.toPreparedSettlement()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(100),
                        attempt.getExecutionPriceQuoteAtomsPerLot()
                ),
                () -> assertEquals(
                        RESERVED_AT.plusSeconds(2),
                        attempt.getSubmittedAt()
                ),
                () -> assertEquals(
                        RESERVED_AT.plusSeconds(3),
                        attempt.getResolvedAt()
                ),
                () -> assertNull(attempt.getErrorCode()),
                () -> assertNull(attempt.getErrorMessage())
        );
    }

    @Test
    void uncertaintyCanRecoverToSubmittedOrResolveDirectly() {
        ExecutionAttemptEntity submitted = reservedAttempt();
        submitted.markSigned(prepared(BigInteger.TEN), at(1));
        submitted.markUncertain("RPC_TIMEOUT", "Node did not reply", at(2));
        submitted.markSubmitted(at(3));

        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.SUBMITTED,
                        submitted.getStatus()
                ),
                () -> assertEquals(at(3), submitted.getSubmittedAt()),
                () -> assertNull(submitted.getErrorCode()),
                () -> assertNull(submitted.getErrorMessage())
        );

        ExecutionAttemptEntity confirmed = reservedAttempt();
        confirmed.markSigned(prepared(BigInteger.valueOf(11)), at(1));
        confirmed.markUncertain("BROADCAST_UNKNOWN", "Outcome unknown", at(2));
        confirmed.markConfirmed(at(3));
        assertEquals(ExecutionAttemptStatus.CONFIRMED, confirmed.getStatus());

        ExecutionAttemptEntity reverted = reservedAttempt();
        reverted.markSigned(prepared(BigInteger.valueOf(12)), at(1));
        reverted.markUncertain("LOOKUP_FAILED", "Lookup failed", at(2));
        reverted.markReverted(" contract rejected match ", at(3));
        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.REVERTED,
                        reverted.getStatus()
                ),
                () -> assertEquals(
                        "contract rejected match",
                        reverted.getRevertReason()
                )
        );
    }

    @Test
    void reservationMayBeAbandonedOnlyBeforeSigning() {
        ExecutionAttemptEntity abandoned = reservedAttempt();
        abandoned.abandon(
                "STALE_RESERVATION",
                "Projection changed before signing",
                at(1)
        );

        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.ABANDONED,
                        abandoned.getStatus()
                ),
                () -> assertEquals(at(1), abandoned.getResolvedAt()),
                () -> assertEquals(
                        "STALE_RESERVATION",
                        abandoned.getErrorCode()
                )
        );

        ExecutionAttemptEntity signed = reservedAttempt();
        signed.markSigned(prepared(BigInteger.ZERO), at(1));

        assertThrows(
                ExecutionAttemptTransitionException.class,
                () -> signed.abandon("UNSAFE", "Must not abandon", at(2))
        );
        assertEquals(ExecutionAttemptStatus.SIGNED, signed.getStatus());
    }

    @Test
    void rejectsMismatchedPreparedSettlementAndBackwardTime() {
        ExecutionAttemptEntity attempt = reservedAttempt();
        SettlementRequest wrongRequest = new SettlementRequest(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.valueOf(99),
                BigInteger.valueOf(5),
                BigInteger.valueOf(100)
        );
        PreparedSettlement wrong = new PreparedSettlement(
                wrongRequest,
                EXECUTOR,
                BigInteger.ZERO,
                hash(99),
                "0x01"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> attempt.markSigned(wrong, at(1))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> attempt.markSigned(
                        prepared(BigInteger.ZERO),
                        RESERVED_AT.minusSeconds(1)
                )
        );
        assertEquals(ExecutionAttemptStatus.RESERVED, attempt.getStatus());
    }

    @Test
    void rejectsBlankOrOversizedFailureDetails() {
        ExecutionAttemptEntity uncertain = reservedAttempt();
        uncertain.markSigned(prepared(BigInteger.ZERO), at(1));

        assertThrows(
                IllegalArgumentException.class,
                () -> uncertain.markUncertain(" ", "failure", at(2))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> uncertain.markUncertain(
                        "X".repeat(ExecutionAttemptEntity.MAX_ERROR_CODE_LENGTH + 1),
                        "failure",
                        at(2)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> uncertain.markUncertain(
                        "RPC_ERROR",
                        "X".repeat(ExecutionAttemptEntity.MAX_DETAIL_LENGTH + 1),
                        at(2)
                )
        );
        assertEquals(ExecutionAttemptStatus.SIGNED, uncertain.getStatus());
    }

    @Test
    void terminalStateCannotTransitionAgain() {
        ExecutionAttemptEntity attempt = reservedAttempt();
        attempt.markSigned(prepared(BigInteger.ZERO), at(1));
        attempt.markSubmitted(at(2));
        attempt.markReverted(null, at(3));

        assertTrue(attempt.getStatus().isTerminal());
        assertNull(attempt.getRevertReason());
        assertThrows(
                ExecutionAttemptTransitionException.class,
                () -> attempt.markConfirmed(at(4))
        );
    }

    private static ExecutionAttemptEntity reservedAttempt() {
        return ExecutionAttemptEntity.reserved(
                UUID.randomUUID(),
                proposal(),
                EXECUTOR,
                RESERVED_AT
        );
    }

    private static MatchProposal proposal() {
        return new MatchProposal(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                BigInteger.TEN,
                BigInteger.valueOf(20),
                BigInteger.valueOf(5),
                BigInteger.valueOf(100)
        );
    }

    private static PreparedSettlement prepared(BigInteger nonce) {
        MatchProposal proposal = proposal();
        return new PreparedSettlement(
                new SettlementRequest(
                        proposal.deploymentId(),
                        proposal.marketId(),
                        proposal.buyOrderId(),
                        proposal.sellOrderId(),
                        proposal.fillLots(),
                        proposal.executionPriceQuoteAtomsPerLot()
                ),
                EXECUTOR,
                nonce,
                hash(nonce.add(BigInteger.ONE)),
                "0x01"
        );
    }

    private static Instant at(long seconds) {
        return RESERVED_AT.plusSeconds(seconds);
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return hash(BigInteger.valueOf(value));
    }

    private static Hash256 hash(BigInteger value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
