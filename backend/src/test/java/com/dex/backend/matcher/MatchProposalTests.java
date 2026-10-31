package com.dex.backend.matcher;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatchProposalTests {

    @Test
    void createsValidProposalWithExactIntegerValues() {
        BigInteger largeValue = new BigInteger("90071992547409931234567890");

        MatchProposal proposal = new MatchProposal(
                "deployment-1",
                BigInteger.ZERO,
                largeValue,
                largeValue.add(BigInteger.ONE),
                largeValue,
                largeValue.add(BigInteger.TWO)
        );

        assertAll(
                () -> assertEquals("deployment-1", proposal.deploymentId()),
                () -> assertEquals(BigInteger.ZERO, proposal.marketId()),
                () -> assertEquals(largeValue, proposal.buyOrderId()),
                () -> assertEquals(largeValue.add(BigInteger.ONE), proposal.sellOrderId()),
                () -> assertEquals(largeValue, proposal.fillLots()),
                () -> assertEquals(
                        largeValue.add(BigInteger.TWO),
                        proposal.executionPriceQuoteAtomsPerLot()
                )
        );
    }

    @Test
    void rejectsInvalidDeploymentIds() {
        for (String deploymentId : List.of("", " \t\n", "d".repeat(129))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> proposal(deploymentId, 1, 2, 1, 1)
            );
        }

        assertThrows(
                NullPointerException.class,
                () -> proposal(null, 1, 2, 1, 1)
        );
    }

    @Test
    void rejectsNegativeOrNullIds() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new MatchProposal(
                                "deployment-1",
                                BigInteger.valueOf(-1),
                                BigInteger.ONE,
                                BigInteger.TWO,
                                BigInteger.ONE,
                                BigInteger.ONE
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> proposal("deployment-1", -1, 2, 1, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> proposal("deployment-1", 1, -2, 1, 1)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MatchProposal(
                                "deployment-1",
                                null,
                                BigInteger.ONE,
                                BigInteger.TWO,
                                BigInteger.ONE,
                                BigInteger.ONE
                        )
                )
        );
    }

    @Test
    void rejectsIdenticalOrderIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> proposal("deployment-1", 1, 1, 1, 1)
        );
    }

    @Test
    void rejectsNonPositiveOrNullFillAndPrice() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> proposal("deployment-1", 1, 2, 0, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> proposal("deployment-1", 1, 2, -1, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> proposal("deployment-1", 1, 2, 1, 0)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> proposal("deployment-1", 1, 2, 1, -1)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MatchProposal(
                                "deployment-1",
                                BigInteger.ONE,
                                BigInteger.TWO,
                                BigInteger.valueOf(3),
                                null,
                                BigInteger.ONE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MatchProposal(
                                "deployment-1",
                                BigInteger.ONE,
                                BigInteger.TWO,
                                BigInteger.valueOf(3),
                                BigInteger.ONE,
                                null
                        )
                )
        );
    }

    private static MatchProposal proposal(
            String deploymentId,
            long buyOrderId,
            long sellOrderId,
            long fillLots,
            long price
    ) {
        return new MatchProposal(
                deploymentId,
                BigInteger.ONE,
                BigInteger.valueOf(buyOrderId),
                BigInteger.valueOf(sellOrderId),
                BigInteger.valueOf(fillLots),
                BigInteger.valueOf(price)
        );
    }
}
