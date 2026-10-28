package com.dex.backend.trade;

import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.Hash256;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TradeTests {

    @Test
    void createsValidTradeWithTradeId() {
        Trade trade = new Fixture().build();

        assertAll(
                () -> assertEquals("local-dex-001", trade.deploymentId()),
                () -> assertEquals(BigInteger.ONE, trade.tradeId()),
                () -> assertEquals(BigInteger.ONE, trade.marketId()),
                () -> assertEquals(
                        BigInteger.valueOf(101), trade.buyOrderId()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(102), trade.sellOrderId()
                ),
                () -> assertEquals(BigInteger.TWO, trade.fillLots()),
                () -> assertEquals(
                        BigInteger.valueOf(200),
                        trade.executionPriceQuoteAtomsPerLot()
                ),
                () -> assertEquals(
                        BigInteger.ONE, trade.buyOrderRemainingLots()
                ),
                () -> assertEquals(
                        BigInteger.ZERO, trade.sellOrderRemainingLots()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(400), trade.quoteAmountAtoms()
                )
        );
    }

    @Test
    void createsValidTradeWithoutTradeId() {
        Fixture fixture = new Fixture();
        fixture.tradeId = null;

        Trade trade = fixture.build();

        assertNull(trade.tradeId());
        assertEquals(BigInteger.valueOf(400), trade.quoteAmountAtoms());
    }

    @Test
    void rejectsNegativeTradeIdWhenPresent() {
        assertInvalid(
                fixture -> fixture.tradeId = BigInteger.valueOf(-1)
        );
    }

    @Test
    void rejectsIdenticalOrderIds() {
        assertInvalid(
                fixture -> fixture.sellOrderId = fixture.buyOrderId
        );
    }

    @Test
    void rejectsNegativeMarketAndOrderIds() {
        assertAll(
                () -> assertInvalid(
                        fixture -> fixture.marketId = BigInteger.valueOf(-1)
                ),
                () -> assertInvalid(
                        fixture -> fixture.buyOrderId = BigInteger.valueOf(-1)
                ),
                () -> assertInvalid(
                        fixture -> fixture.sellOrderId = BigInteger.valueOf(-1)
                )
        );
    }

    @Test
    void rejectsZeroOrNegativeFillAndPrice() {
        for (BigInteger value : List.of(
                BigInteger.ZERO,
                BigInteger.valueOf(-1)
        )) {
            assertAll(
                    () -> assertInvalid(
                            fixture -> fixture.fillLots = value
                    ),
                    () -> assertInvalid(
                            fixture -> fixture.price = value
                    )
            );
        }
    }

    @Test
    void rejectsNegativeRemainingQuantities() {
        assertAll(
                () -> assertInvalid(
                        fixture -> fixture.buyRemaining = BigInteger.valueOf(-1)
                ),
                () -> assertInvalid(
                        fixture -> fixture.sellRemaining = BigInteger.valueOf(-1)
                )
        );
    }

    @Test
    void rejectsBlankOrOversizedDeploymentId() {
        for (String id : List.of("", " \t\n", "d".repeat(129))) {
            assertInvalid(fixture -> fixture.deploymentId = id);
        }
    }

    @Test
    void acceptsBoundaryValues() {
        Fixture fixture = new Fixture();
        fixture.deploymentId = "d".repeat(128);
        fixture.tradeId = BigInteger.ZERO;
        fixture.marketId = BigInteger.ZERO;
        fixture.buyOrderId = BigInteger.ZERO;
        fixture.sellOrderId = BigInteger.ONE;
        fixture.fillLots = BigInteger.ONE;
        fixture.price = BigInteger.ONE;
        fixture.buyRemaining = BigInteger.ZERO;
        fixture.sellRemaining = BigInteger.ZERO;

        Trade trade = fixture.build();

        assertAll(
                () -> assertEquals(128, trade.deploymentId().length()),
                () -> assertEquals(BigInteger.ZERO, trade.tradeId()),
                () -> assertEquals(BigInteger.ZERO, trade.marketId()),
                () -> assertEquals(BigInteger.ZERO, trade.buyOrderId()),
                () -> assertEquals(BigInteger.ONE, trade.sellOrderId()),
                () -> assertEquals(BigInteger.ZERO, trade.buyOrderRemainingLots()),
                () -> assertEquals(BigInteger.ZERO, trade.sellOrderRemainingLots()),
                () -> assertEquals(BigInteger.ONE, trade.quoteAmountAtoms())
        );
    }

    @Test
    void rejectsMissingChainIdentityFields() {
        assertAll(
                () -> assertNullRejected(
                        fixture -> fixture.deploymentId = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.transactionHash = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.position = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.blockHash = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.timestamp = null
                )
        );
    }

    @Test
    void rejectsMissingRequiredNumericFields() {
        assertAll(
                () -> assertNullRejected(
                        fixture -> fixture.marketId = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.buyOrderId = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.sellOrderId = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.fillLots = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.price = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.buyRemaining = null
                ),
                () -> assertNullRejected(
                        fixture -> fixture.sellRemaining = null
                )
        );
    }

    @Test
    void quoteAmountRemainsExactForVeryLargeValues() {
        Fixture fixture = new Fixture();

        // Both inputs exceed Long.MAX_VALUE.
        fixture.fillLots = new BigInteger("100000000000000000001");
        fixture.price = new BigInteger("100000000000000000003");

        Trade trade = fixture.build();

        // (10^20 + 1)(10^20 + 3) = 10^40 + 4×10^20 + 3
        BigInteger expected = BigInteger.TEN.pow(40)
                .add(BigInteger.TEN.pow(20).multiply(BigInteger.valueOf(4)))
                .add(BigInteger.valueOf(3));

        assertEquals(expected, trade.quoteAmountAtoms());
        assertEquals(fixture.fillLots, trade.fillLots());
        assertEquals(fixture.price, trade.executionPriceQuoteAtomsPerLot());
    }

    private static void assertInvalid(Consumer<Fixture> change) {
        Fixture fixture = new Fixture();
        change.accept(fixture);

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.build()
        );
    }

    private static void assertNullRejected(Consumer<Fixture> change) {
        Fixture fixture = new Fixture();
        change.accept(fixture);

        assertThrows(
                NullPointerException.class,
                () -> fixture.build()
        );
    }

    private static final class Fixture {

        private String deploymentId = "local-dex-001";

        private Hash256 transactionHash =
                new Hash256("0x" + "aa".repeat(32));

        private BigInteger tradeId = BigInteger.ONE;
        private BigInteger marketId = BigInteger.ONE;
        private BigInteger buyOrderId = BigInteger.valueOf(101);
        private BigInteger sellOrderId = BigInteger.valueOf(102);
        private BigInteger fillLots = BigInteger.TWO;
        private BigInteger price = BigInteger.valueOf(200);
        private BigInteger buyRemaining = BigInteger.ONE;
        private BigInteger sellRemaining = BigInteger.ZERO;

        private ChainPosition position = new ChainPosition(
                BigInteger.valueOf(100),
                BigInteger.ZERO,
                BigInteger.ZERO
        );

        private Hash256 blockHash =
                new Hash256("0x" + "bb".repeat(32));

        private Instant timestamp =
                Instant.parse("2026-10-18T12:00:00Z");

        private Trade build() {
            return new Trade(
                    deploymentId,
                    transactionHash,
                    tradeId,
                    marketId,
                    buyOrderId,
                    sellOrderId,
                    fillLots,
                    price,
                    buyRemaining,
                    sellRemaining,
                    position,
                    blockHash,
                    timestamp
            );
        }
    }
}