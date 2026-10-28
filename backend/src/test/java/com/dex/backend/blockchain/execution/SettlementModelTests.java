package com.dex.backend.blockchain.execution;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettlementModelTests {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TWO = BigInteger.TWO;
    private static final BigInteger NEGATIVE = BigInteger.valueOf(-1);

    private static final EthereumAddress EXECUTOR =
            new EthereumAddress("0x" + "11".repeat(20));

    private static final Hash256 TX_HASH =
            new Hash256("0x" + "aa".repeat(32));

    private static final Hash256 BLOCK_HASH =
            new Hash256("0x" + "bb".repeat(32));

    @Test
    void createsValidSettlementRequest() {
        SettlementRequest request = request();

        assertAll(
                () -> assertEquals("local-dex-001", request.deploymentId()),
                () -> assertEquals(ONE, request.marketId()),
                () -> assertEquals(ONE, request.buyOrderId()),
                () -> assertEquals(TWO, request.sellOrderId()),
                () -> assertEquals(TWO, request.fillLots())
        );
    }

    @Test
    void rejectsIdenticalOrderIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SettlementRequest(
                        "local-dex-001", ONE, ONE, ONE, TWO
                )
        );
    }

    @Test
    void rejectsZeroOrNegativeFill() {
        for (BigInteger fill : List.of(ZERO, NEGATIVE)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SettlementRequest(
                            "local-dex-001", ONE, ONE, TWO, fill
                    )
            );
        }
    }

    @Test
    void rejectsNegativeIds() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new SettlementRequest(
                                "local-dex-001", NEGATIVE, ONE, TWO, ONE
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new SettlementRequest(
                                "local-dex-001", ONE, NEGATIVE, TWO, ONE
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new SettlementRequest(
                                "local-dex-001", ONE, ONE, NEGATIVE, ONE
                        )
                )
        );
    }

    @Test
    void rejectsInvalidDeploymentIds() {
        for (String id : List.of("", " \t\n", "d".repeat(129))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SettlementRequest(id, ONE, ONE, TWO, ONE)
            );
        }
    }

    @Test
    void acceptsRequestBoundaryValues() {
        SettlementRequest request = new SettlementRequest(
                "d".repeat(128), ZERO, ZERO, ONE, ONE
        );

        assertEquals(128, request.deploymentId().length());
        assertEquals(ZERO, request.marketId());
        assertEquals(ZERO, request.buyOrderId());
        assertEquals(ONE, request.fillLots());
    }

    @Test
    void rejectsNullRequestComponents() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new SettlementRequest(null, ONE, ONE, TWO, ONE)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new SettlementRequest(
                                "local-dex-001", null, ONE, TWO, ONE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new SettlementRequest(
                                "local-dex-001", ONE, null, TWO, ONE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new SettlementRequest(
                                "local-dex-001", ONE, ONE, null, ONE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new SettlementRequest(
                                "local-dex-001", ONE, ONE, TWO, null
                        )
                )
        );
    }

    @Test
    void normalizesPreparedTransactionHex() {
        PreparedSettlement prepared = prepared(ZERO, "0XAbCd01");

        assertEquals("0xabcd01", prepared.signedTransactionHex());
        assertEquals(ZERO, prepared.nonce());
        assertEquals(TX_HASH, prepared.transactionHash());
        assertEquals(EXECUTOR, prepared.executorAddress());
        assertEquals(request(), prepared.request());
    }

    @Test
    void acceptsOneByteAndAlreadyNormalizedHex() {
        assertEquals("0x00", prepared(ZERO, "0x00").signedTransactionHex());
        assertEquals(
                "0xabcdef",
                prepared(ONE, "0xabcdef").signedTransactionHex()
        );
    }

    @Test
    void rejectsNegativeNonce() {
        assertThrows(
                IllegalArgumentException.class,
                () -> prepared(NEGATIVE, "0xab")
        );
    }

    @Test
    void rejectsInvalidSignedHex() {
        for (String hex : List.of(
                "",
                "0x",        // No bytes
                "0xa",       // Half a byte
                "0xabc",     // Odd number of hex characters
                "abcd",      // Missing prefix
                "0xag",      // Non-hex character
                " 0xab",     // Leading whitespace
                "0xab ",     // Trailing whitespace
                "0xab cd",   // Internal whitespace
                "0xab\n"     // Trailing newline
        )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> prepared(ZERO, hex)
            );
        }
    }

    @Test
    void rejectsNullPreparedComponents() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new PreparedSettlement(
                                null, EXECUTOR, ZERO, TX_HASH, "0xab"
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new PreparedSettlement(
                                request(), null, ZERO, TX_HASH, "0xab"
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> prepared(null, "0xab")
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new PreparedSettlement(
                                request(), EXECUTOR, ZERO, null, "0xab"
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> prepared(ZERO, null)
                )
        );
    }

    @Test
    void preparedToStringRedactsSignedTransaction() {
        String signedHex = "0xdeadbeef0123456789";
        PreparedSettlement prepared = prepared(ONE, signedHex);

        String text = prepared.toString();

        assertAll(
                () -> assertTrue(
                        text.contains("request=" + prepared.request())
                ),
                () -> assertTrue(
                        text.contains("executorAddress=" + EXECUTOR)
                ),
                () -> assertTrue(text.contains("nonce=1")),
                () -> assertTrue(
                        text.contains("transactionHash=" + TX_HASH)
                ),
                () -> assertTrue(
                        text.contains("signedTransactionHex=<redacted>")
                ),
                () -> assertFalse(text.contains(signedHex)),
                () -> assertFalse(text.contains(signedHex.substring(2)))
        );
    }

    @Test
    void createsEveryValidObservationThroughFactories() {
        TransactionObservation missing =
                TransactionObservation.notFound(TX_HASH);

        TransactionObservation pending =
                TransactionObservation.pending(TX_HASH);

        TransactionObservation succeeded =
                TransactionObservation.succeeded(TX_HASH, ONE, BLOCK_HASH);

        TransactionObservation reverted =
                TransactionObservation.reverted(
                        TX_HASH, ONE, BLOCK_HASH, "Order already cancelled"
                );

        assertAll(
                () -> assertEquals(
                        TransactionStatus.NOT_FOUND, missing.status()
                ),
                () -> assertEquals(
                        TransactionStatus.PENDING, pending.status()
                ),
                () -> assertEquals(
                        TransactionStatus.SUCCEEDED, succeeded.status()
                ),
                () -> assertEquals(
                        TransactionStatus.REVERTED, reverted.status()
                ),
                () -> assertEquals(
                        "Order already cancelled", reverted.failureReason()
                )
        );

        for (TransactionObservation observation :
                List.of(missing, pending, succeeded, reverted)) {
            assertEquals(TX_HASH, observation.transactionHash());
        }

        for (TransactionObservation observation : List.of(missing, pending)) {
            assertNull(observation.blockNumber());
            assertNull(observation.blockHash());
            assertNull(observation.failureReason());
        }

        for (TransactionObservation observation : List.of(succeeded, reverted)) {
            assertEquals(ONE, observation.blockNumber());
            assertEquals(BLOCK_HASH, observation.blockHash());
        }

        assertNull(succeeded.failureReason());
    }

    @Test
    void revertedObservationAllowsMissingFailureReason() {
        TransactionObservation observation =
                TransactionObservation.reverted(
                        TX_HASH, ZERO, BLOCK_HASH, null
                );

        assertNull(observation.failureReason());
        assertEquals(ZERO, observation.blockNumber());
    }

    @Test
    void convertsBlankFailureReasonsToNullForEveryStatus() {
        for (TransactionStatus status : TransactionStatus.values()) {
            boolean mined = status == TransactionStatus.SUCCEEDED
                    || status == TransactionStatus.REVERTED;

            for (String blank : List.of("", " \t\n")) {
                TransactionObservation observation =
                        new TransactionObservation(
                                TX_HASH,
                                status,
                                mined ? ZERO : null,
                                mined ? BLOCK_HASH : null,
                                blank
                        );

                assertNull(observation.failureReason());
            }
        }
    }

    @Test
    void unminedStatusesRejectAnyBlockInformation() {
        for (TransactionStatus status : List.of(
                TransactionStatus.NOT_FOUND,
                TransactionStatus.PENDING
        )) {
            assertAll(
                    () -> assertInvalidObservation(status, ONE, null, null),
                    () -> assertInvalidObservation(status, null, BLOCK_HASH, null),
                    () -> assertInvalidObservation(status, ONE, BLOCK_HASH, null)
            );
        }
    }

    @Test
    void minedStatusesRequireBothBlockFields() {
        for (TransactionStatus status : List.of(
                TransactionStatus.SUCCEEDED,
                TransactionStatus.REVERTED
        )) {
            assertAll(
                    () -> assertInvalidObservation(status, null, null, null),
                    () -> assertInvalidObservation(status, ONE, null, null),
                    () -> assertInvalidObservation(status, null, BLOCK_HASH, null),
                    () -> assertInvalidObservation(
                            status, NEGATIVE, BLOCK_HASH, null
                    )
            );
        }
    }

    @Test
    void onlyRevertedStatusAllowsFailureReason() {
        assertAll(
                () -> assertInvalidObservation(
                        TransactionStatus.NOT_FOUND, null, null, "Failure"
                ),
                () -> assertInvalidObservation(
                        TransactionStatus.PENDING, null, null, "Failure"
                ),
                () -> assertInvalidObservation(
                        TransactionStatus.SUCCEEDED, ONE, BLOCK_HASH, "Failure"
                )
        );
    }

    @Test
    void observationsRequireTransactionHashAndStatus() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> TransactionObservation.notFound(null)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new TransactionObservation(
                                TX_HASH, null, null, null, null
                        )
                )
        );
    }

    @Test
    void preservesNumbersLargerThanLongMaxValue() {
        BigInteger large = BigInteger.valueOf(Long.MAX_VALUE).add(ONE);

        SettlementRequest request = new SettlementRequest(
                "local-dex-001",
                large,
                large,
                large.add(ONE),
                large
        );

        PreparedSettlement prepared = new PreparedSettlement(
                request, EXECUTOR, large, TX_HASH, "0xab"
        );

        TransactionObservation observation =
                TransactionObservation.succeeded(TX_HASH, large, BLOCK_HASH);

        assertAll(
                () -> assertEquals(large, request.marketId()),
                () -> assertEquals(large, request.buyOrderId()),
                () -> assertEquals(large.add(ONE), request.sellOrderId()),
                () -> assertEquals(large, request.fillLots()),
                () -> assertEquals(large, prepared.nonce()),
                () -> assertEquals(large, observation.blockNumber())
        );
    }

    @Test
    void gatewayExceptionsRetainClassificationMessageAndCause() {
        for (boolean retryable : new boolean[]{true, false}) {
            BlockchainGatewayException withoutCause =
                    new BlockchainGatewayException(
                            "Safe gateway error", retryable
                    );

            Throwable cause = new IllegalStateException("Safe underlying error");

            BlockchainGatewayException withCause =
                    new BlockchainGatewayException(
                            "Safe gateway error", retryable, cause
                    );

            assertAll(
                    () -> assertEquals(
                            retryable, withoutCause.isRetryable()
                    ),
                    () -> assertEquals(
                            retryable, withCause.isRetryable()
                    ),
                    () -> assertEquals(
                            "Safe gateway error", withoutCause.getMessage()
                    ),
                    () -> assertEquals(
                            "Safe gateway error", withCause.getMessage()
                    ),
                    () -> assertNull(withoutCause.getCause()),
                    () -> assertSame(cause, withCause.getCause())
            );
        }
    }

    private static SettlementRequest request() {
        return new SettlementRequest(
                "local-dex-001", ONE, ONE, TWO, TWO
        );
    }

    private static PreparedSettlement prepared(
            BigInteger nonce,
            String signedHex
    ) {
        return new PreparedSettlement(
                request(), EXECUTOR, nonce, TX_HASH, signedHex
        );
    }

    private static void assertInvalidObservation(
            TransactionStatus status,
            BigInteger blockNumber,
            Hash256 blockHash,
            String failureReason
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TransactionObservation(
                        TX_HASH, status, blockNumber, blockHash, failureReason
                )
        );
    }
}