package com.dex.backend.order;

import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class OrderTests {

    @Test
    void createsValidOpenOrder() {
        Order order = new Fixture().build();

        assertAll(
                () -> assertEquals("local-dex-001", order.deploymentId()),
                () -> assertEquals(BigInteger.ONE, order.orderId()),
                () -> assertEquals(BigInteger.ONE, order.marketId()),
                () -> assertEquals(OrderSide.BUY, order.side()),
                () -> assertEquals(OrderStatus.OPEN, order.status()),
                () -> assertEquals(BigInteger.TEN, order.originalLots()),
                () -> assertEquals(BigInteger.TEN, order.remainingLots()),
                () -> assertEquals(BigInteger.ZERO, order.filledLots()),
                () -> assertTrue(order.isMatchable())
        );
    }

    @Test
    void createsValidPartiallyFilledOrder() {
        Order order = order(OrderStatus.PARTIALLY_FILLED, 4);

        assertAll(
                () -> assertEquals(
                        OrderStatus.PARTIALLY_FILLED, order.status()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(6), order.filledLots()
                ),
                () -> assertTrue(order.isMatchable())
        );
    }

    @Test
    void createsValidFilledOrder() {
        Order order = order(OrderStatus.FILLED, 0);

        assertAll(
                () -> assertEquals(OrderStatus.FILLED, order.status()),
                () -> assertEquals(BigInteger.TEN, order.filledLots()),
                () -> assertFalse(order.isMatchable())
        );
    }

    @Test
    void cancelledOrdersAllowZeroPartialOrFullRemainingQuantity() {
        for (long remaining : new long[]{0, 4, 10}) {
            Order order = order(OrderStatus.CANCELLED, remaining);

            assertAll(
                    () -> assertEquals(
                            OrderStatus.CANCELLED, order.status()
                    ),
                    () -> assertEquals(
                            BigInteger.valueOf(10 - remaining),
                            order.filledLots()
                    ),
                    () -> assertFalse(order.isMatchable())
            );
        }
    }

    @Test
    void acceptsSellSide() {
        Fixture fixture = new Fixture();
        fixture.side = OrderSide.SELL;

        assertEquals(OrderSide.SELL, fixture.build().side());
    }

    @Test
    void rejectsInvalidStatusQuantityCombinations() {
        assertAll(
                // OPEN requires all original lots to remain.
                () -> assertInvalidStatus(OrderStatus.OPEN, 0),
                () -> assertInvalidStatus(OrderStatus.OPEN, 4),

                // PARTIALLY_FILLED requires a strict intermediate quantity.
                () -> assertInvalidStatus(OrderStatus.PARTIALLY_FILLED, 0),
                () -> assertInvalidStatus(OrderStatus.PARTIALLY_FILLED, 10),

                // FILLED requires zero remaining lots.
                () -> assertInvalidStatus(OrderStatus.FILLED, 4),
                () -> assertInvalidStatus(OrderStatus.FILLED, 10)
        );
    }

    @Test
    void rejectsRemainingOutsideBoundsForEveryStatus() {
        for (OrderStatus status : OrderStatus.values()) {
            assertAll(
                    () -> assertInvalidStatus(status, -1),
                    () -> assertInvalidStatus(status, 11)
            );
        }
    }

    @Test
    void rejectsZeroOrNegativePrice() {
        for (BigInteger price : List.of(
                BigInteger.ZERO,
                BigInteger.valueOf(-1)
        )) {
            assertInvalid(fixture -> fixture.price = price);
        }
    }

    @Test
    void rejectsZeroOrNegativeOriginalQuantity() {
        for (BigInteger quantity : List.of(
                BigInteger.ZERO,
                BigInteger.valueOf(-1)
        )) {
            assertInvalid(fixture -> {
                fixture.original = quantity;
                fixture.remaining = BigInteger.ZERO;
                fixture.status = OrderStatus.FILLED;
            });
        }
    }

    @Test
    void rejectsBlankOrOversizedDeploymentId() {
        for (String id : List.of("", " \t\n", "d".repeat(129))) {
            assertInvalid(fixture -> fixture.deploymentId = id);
        }
    }

    @Test
    void acceptsMaximumDeploymentIdLengthAndZeroIds() {
        Fixture fixture = new Fixture();
        fixture.deploymentId = "d".repeat(128);
        fixture.orderId = BigInteger.ZERO;
        fixture.marketId = BigInteger.ZERO;

        Order order = fixture.build();

        assertAll(
                () -> assertEquals(128, order.deploymentId().length()),
                () -> assertEquals(BigInteger.ZERO, order.orderId()),
                () -> assertEquals(BigInteger.ZERO, order.marketId())
        );
    }

    @Test
    void rejectsNegativeIds() {
        assertAll(
                () -> assertInvalid(
                        fixture -> fixture.orderId = BigInteger.valueOf(-1)
                ),
                () -> assertInvalid(
                        fixture -> fixture.marketId = BigInteger.valueOf(-1)
                )
        );
    }

    @Test
    void rejectsNullBlockchainReferences() {
        assertAll(
                () -> assertNullRejected(fixture -> fixture.owner = null),
                () -> assertNullRejected(fixture -> fixture.position = null),
                () -> assertNullRejected(
                        fixture -> fixture.transactionHash = null
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
    void rejectsOtherNullRequiredFields() {
        assertAll(
                () -> assertNullRejected(
                        fixture -> fixture.deploymentId = null
                ),
                () -> assertNullRejected(fixture -> fixture.orderId = null),
                () -> assertNullRejected(fixture -> fixture.marketId = null),
                () -> assertNullRejected(fixture -> fixture.side = null),
                () -> assertNullRejected(fixture -> fixture.price = null),
                () -> assertNullRejected(fixture -> fixture.original = null),
                () -> assertNullRejected(fixture -> fixture.remaining = null),
                () -> assertNullRejected(fixture -> fixture.status = null)
        );
    }

    @Test
    void onlyOpenAndPartiallyFilledOrdersAreMatchable() {
        assertAll(
                () -> assertTrue(order(OrderStatus.OPEN, 10).isMatchable()),
                () -> assertTrue(
                        order(OrderStatus.PARTIALLY_FILLED, 4).isMatchable()
                ),
                () -> assertFalse(
                        order(OrderStatus.FILLED, 0).isMatchable()
                ),
                () -> assertFalse(
                        order(OrderStatus.CANCELLED, 10).isMatchable()
                ),
                () -> assertFalse(
                        order(OrderStatus.CANCELLED, 4).isMatchable()
                ),
                () -> assertFalse(
                        order(OrderStatus.CANCELLED, 0).isMatchable()
                )
        );
    }

    @Test
    void retainsExactPrecisionBeyondLongMaxValue() {
        BigInteger original =
                new BigInteger("300000000000000000000");

        BigInteger remaining =
                new BigInteger("100000000000000000001");

        BigInteger price =
                new BigInteger("500000000000000000007");

        Fixture fixture = new Fixture();
        fixture.orderId = original;
        fixture.marketId = remaining;
        fixture.original = original;
        fixture.remaining = remaining;
        fixture.price = price;
        fixture.status = OrderStatus.PARTIALLY_FILLED;

        Order order = fixture.build();

        assertAll(
                () -> assertEquals(original, order.orderId()),
                () -> assertEquals(remaining, order.marketId()),
                () -> assertEquals(original, order.originalLots()),
                () -> assertEquals(remaining, order.remainingLots()),
                () -> assertEquals(price, order.limitPriceQuoteAtomsPerLot()),
                () -> assertEquals(
                        new BigInteger("199999999999999999999"),
                        order.filledLots()
                ),
                () -> assertTrue(order.isMatchable())
        );
    }

    private static Order order(OrderStatus status, long remaining) {
        Fixture fixture = new Fixture();
        fixture.status = status;
        fixture.remaining = BigInteger.valueOf(remaining);
        return fixture.build();
    }

    private static void assertInvalidStatus(
            OrderStatus status,
            long remaining
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> order(status, remaining),
                () -> "Expected rejection for status=" + status
                        + ", remaining=" + remaining
        );
    }

    private static void assertInvalid(Consumer<Fixture> change) {
        Fixture fixture = new Fixture();
        change.accept(fixture);

        assertThrows(IllegalArgumentException.class, () -> fixture.build());
    }

    private static void assertNullRejected(Consumer<Fixture> change) {
        Fixture fixture = new Fixture();
        change.accept(fixture);

        assertThrows(NullPointerException.class, () -> fixture.build());
    }

    private static final class Fixture {

        private String deploymentId = "local-dex-001";
        private BigInteger orderId = BigInteger.ONE;
        private BigInteger marketId = BigInteger.ONE;

        private EthereumAddress owner =
                new EthereumAddress("0x" + "11".repeat(20));

        private OrderSide side = OrderSide.BUY;
        private BigInteger price = BigInteger.valueOf(200);
        private BigInteger original = BigInteger.TEN;
        private BigInteger remaining = BigInteger.TEN;
        private OrderStatus status = OrderStatus.OPEN;

        private ChainPosition position = new ChainPosition(
                BigInteger.valueOf(100),
                BigInteger.ZERO,
                BigInteger.ZERO
        );

        private Hash256 transactionHash =
                new Hash256("0x" + "aa".repeat(32));

        private Hash256 blockHash =
                new Hash256("0x" + "bb".repeat(32));

        private Instant timestamp =
                Instant.parse("2026-10-18T12:00:00Z");

        private Order build() {
            return new Order(
                    deploymentId,
                    orderId,
                    marketId,
                    owner,
                    side,
                    price,
                    original,
                    remaining,
                    status,
                    position,
                    transactionHash,
                    blockHash,
                    timestamp
            );
        }
    }
}