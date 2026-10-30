package com.dex.backend.blockchain.model;

import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockchainQueryModelTests {

    private static final String DEPLOYMENT = "local-dex-001";
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TEN = BigInteger.TEN;
    private static final BigInteger NEGATIVE = BigInteger.valueOf(-1);

    private static final EthereumAddress BASE =
            new EthereumAddress("0x" + "11".repeat(20));

    private static final EthereumAddress QUOTE =
            new EthereumAddress("0x" + "22".repeat(20));

    private static final Hash256 HASH =
            new Hash256("0x" + "aa".repeat(32));

    private static final Instant TIME =
            Instant.parse("2026-10-18T12:00:00Z");

    @Test
    void createsValidChainIdentity() {
        ChainIdentity identity = new ChainIdentity(
                DEPLOYMENT,
                BigInteger.valueOf(31337),
                BASE,
                QUOTE,
                TEN,
                HASH
        );

        assertAll(
                () -> assertEquals(DEPLOYMENT, identity.deploymentId()),
                () -> assertEquals(
                        BigInteger.valueOf(31337), identity.chainId()
                ),
                () -> assertEquals(BASE, identity.dexAddress()),
                () -> assertEquals(TEN, identity.deploymentBlockNumber()),
                () -> assertEquals(HASH, identity.deploymentBlockHash())
        );
    }

    @Test
    void rejectsInvalidDeploymentIdsAcrossModels() {
        for (String id : List.of("", " \t\n", "d".repeat(129))) {
            assertAll(
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> new ChainIdentity(
                                    id, ONE, BASE, QUOTE, ZERO, HASH
                            )
                    ),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> new MarketSnapshot(
                                    id, ONE, BASE, QUOTE, ONE, true
                            )
                    ),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> snapshot(
                                    id, OrderStatus.OPEN, TEN, TEN
                            )
                    )
            );
        }
    }

    @Test
    void acceptsMaximumDeploymentIdLengthAcrossModels() {
        String id = "d".repeat(128);

        assertAll(
                () -> assertEquals(
                        id,
                        new ChainIdentity(
                                id, ZERO, BASE, QUOTE, ZERO, HASH
                        )
                                .deploymentId()
                ),
                () -> assertEquals(
                        id,
                        new MarketSnapshot(id, ZERO, BASE, QUOTE, ONE, true)
                                .deploymentId()
                ),
                () -> assertEquals(
                        id,
                        snapshot(id, OrderStatus.OPEN, TEN, TEN)
                                .deploymentId()
                )
        );
    }

    @Test
    void rejectsNegativeChainAndBlockNumbers() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChainIdentity(
                                DEPLOYMENT, NEGATIVE, BASE, QUOTE, ZERO, HASH
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChainIdentity(
                                DEPLOYMENT, ONE, BASE, QUOTE, NEGATIVE, HASH
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new BlockReference(NEGATIVE, HASH, TIME)
                )
        );
    }

    @Test
    void rejectsMissingChainIdentityFields() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainIdentity(
                                null, ONE, BASE, QUOTE, ZERO, HASH
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainIdentity(
                                DEPLOYMENT, null, BASE, QUOTE, ZERO, HASH
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainIdentity(
                                DEPLOYMENT, ONE, null, QUOTE, ZERO, HASH
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainIdentity(
                                DEPLOYMENT, ONE, BASE, null, ZERO, HASH
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainIdentity(
                                DEPLOYMENT, ONE, BASE, QUOTE, null, HASH
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ChainIdentity(
                                DEPLOYMENT, ONE, BASE, QUOTE, ZERO, null
                        )
                )
        );
    }

    @Test
    void createsBlockReferenceAtZero() {
        BlockReference reference = new BlockReference(ZERO, HASH, TIME);

        assertEquals(ZERO, reference.number());
        assertEquals(HASH, reference.hash());
        assertEquals(TIME, reference.timestamp());
    }

    @Test
    void rejectsMissingBlockReferenceFields() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new BlockReference(null, HASH, TIME)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new BlockReference(ZERO, null, TIME)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new BlockReference(ZERO, HASH, null)
                )
        );
    }

    @Test
    void calculatesInclusiveRangeSize() {
        assertAll(
                () -> assertEquals(
                        BigInteger.valueOf(3),
                        new BlockRange(TEN, BigInteger.valueOf(12)).size()
                ),
                () -> assertEquals(
                        ONE, new BlockRange(TEN, TEN).size()
                ),
                () -> assertEquals(
                        ONE, new BlockRange(ZERO, ZERO).size()
                )
        );
    }

    @Test
    void rejectsReversedNegativeOrNullRanges() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new BlockRange(TEN, ONE)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new BlockRange(NEGATIVE, ONE)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new BlockRange(ZERO, NEGATIVE)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new BlockRange(null, ONE)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new BlockRange(ZERO, null)
                )
        );
    }

    @Test
    void createsMarketSnapshot() {
        MarketSnapshot snapshot = new MarketSnapshot(
                DEPLOYMENT, ZERO, BASE, QUOTE, TEN, false
        );

        assertAll(
                () -> assertEquals(ZERO, snapshot.marketId()),
                () -> assertEquals(BASE, snapshot.baseTokenAddress()),
                () -> assertEquals(QUOTE, snapshot.quoteTokenAddress()),
                () -> assertEquals(TEN, snapshot.baseLotSizeAtoms()),
                () -> assertFalse(snapshot.active())
        );
    }

    @Test
    void rejectsIdenticalMarketTokens() {
        EthereumAddress first =
                new EthereumAddress("0x" + "ab".repeat(20));
        EthereumAddress second =
                new EthereumAddress("0x" + "AB".repeat(20));

        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketSnapshot(
                        DEPLOYMENT, ONE, first, second, TEN, true
                )
        );
    }

    @Test
    void rejectsInvalidMarketNumbers() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new MarketSnapshot(
                                DEPLOYMENT, NEGATIVE, BASE, QUOTE, ONE, true
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new MarketSnapshot(
                                DEPLOYMENT, ONE, BASE, QUOTE, ZERO, true
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new MarketSnapshot(
                                DEPLOYMENT, ONE, BASE, QUOTE, NEGATIVE, true
                        )
                )
        );
    }

    @Test
    void rejectsMissingMarketFields() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MarketSnapshot(
                                null, ONE, BASE, QUOTE, ONE, true
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MarketSnapshot(
                                DEPLOYMENT, null, BASE, QUOTE, ONE, true
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MarketSnapshot(
                                DEPLOYMENT, ONE, null, QUOTE, ONE, true
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MarketSnapshot(
                                DEPLOYMENT, ONE, BASE, null, ONE, true
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MarketSnapshot(
                                DEPLOYMENT, ONE, BASE, QUOTE, null, true
                        )
                )
        );
    }

    @Test
    void acceptsValidOrderStatusesAndDeterminesMatchability() {
        assertAll(
                () -> assertTrue(
                        snapshot(OrderStatus.OPEN, TEN).isMatchable()
                ),
                () -> assertTrue(
                        snapshot(OrderStatus.PARTIALLY_FILLED, ONE)
                                .isMatchable()
                ),
                () -> assertFalse(
                        snapshot(OrderStatus.FILLED, ZERO).isMatchable()
                )
        );

        for (BigInteger remaining : List.of(ZERO, ONE, TEN)) {
            OrderSnapshot cancelled =
                    snapshot(OrderStatus.CANCELLED, remaining);

            assertEquals(remaining, cancelled.remainingLots());
            assertFalse(cancelled.isMatchable());
        }
    }

    @Test
    void rejectsInvalidStatusQuantityCombinations() {
        assertAll(
                () -> assertInvalidStatus(OrderStatus.OPEN, ZERO),
                () -> assertInvalidStatus(OrderStatus.OPEN, ONE),
                () -> assertInvalidStatus(OrderStatus.PARTIALLY_FILLED, ZERO),
                () -> assertInvalidStatus(OrderStatus.PARTIALLY_FILLED, TEN),
                () -> assertInvalidStatus(OrderStatus.FILLED, ONE),
                () -> assertInvalidStatus(OrderStatus.FILLED, TEN)
        );

        for (OrderStatus status : OrderStatus.values()) {
            assertInvalidStatus(status, NEGATIVE);
            assertInvalidStatus(status, BigInteger.valueOf(11));
        }
    }

    @Test
    void rejectsInvalidOrderIdsPriceAndOriginalQuantity() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new OrderSnapshot(
                                DEPLOYMENT, NEGATIVE, ONE, BASE,
                                OrderSide.BUY, ONE, TEN, TEN, OrderStatus.OPEN
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new OrderSnapshot(
                                DEPLOYMENT, ONE, NEGATIVE, BASE,
                                OrderSide.BUY, ONE, TEN, TEN, OrderStatus.OPEN
                        )
                )
        );

        for (BigInteger invalid : List.of(ZERO, NEGATIVE)) {
            assertAll(
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> new OrderSnapshot(
                                    DEPLOYMENT, ONE, ONE, BASE,
                                    OrderSide.BUY, invalid, TEN, TEN,
                                    OrderStatus.OPEN
                            )
                    ),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> snapshot(
                                    DEPLOYMENT, OrderStatus.FILLED,
                                    invalid, ZERO
                            )
                    )
            );
        }
    }

    @Test
    void rejectsMissingOrderFields() {
        // Each array starts valid; replace one required component at a time.
        for (int index = 0; index < 9; index++) {
            Object[] values = {
                    DEPLOYMENT, ONE, ONE, BASE, OrderSide.BUY,
                    ONE, TEN, TEN, OrderStatus.OPEN
            };
            values[index] = null;

            assertThrows(
                    NullPointerException.class,
                    () -> new OrderSnapshot(
                            (String) values[0],
                            (BigInteger) values[1],
                            (BigInteger) values[2],
                            (EthereumAddress) values[3],
                            (OrderSide) values[4],
                            (BigInteger) values[5],
                            (BigInteger) values[6],
                            (BigInteger) values[7],
                            (OrderStatus) values[8]
                    ),
                    "Expected null rejection for component index " + index
            );
        }
    }

    @Test
    void largeBigIntegerValuesRemainExact() {
        BigInteger huge = new BigInteger("100000000000000000000");
        BigInteger next = huge.add(ONE);

        ChainIdentity identity = new ChainIdentity(
                DEPLOYMENT, huge, BASE, QUOTE, next, HASH
        );
        BlockReference block = new BlockReference(huge, HASH, TIME);
        BlockRange range = new BlockRange(ZERO, huge);
        MarketSnapshot market = new MarketSnapshot(
                DEPLOYMENT, huge, BASE, QUOTE, next, true
        );
        OrderSnapshot order = new OrderSnapshot(
                DEPLOYMENT,
                huge,
                next,
                BASE,
                OrderSide.SELL,
                huge,
                next,
                huge,
                OrderStatus.PARTIALLY_FILLED
        );

        assertAll(
                () -> assertEquals(huge, identity.chainId()),
                () -> assertEquals(next, identity.deploymentBlockNumber()),
                () -> assertEquals(huge, block.number()),
                () -> assertEquals(next, range.size()),
                () -> assertEquals(
                        BigInteger.TWO, new BlockRange(huge, next).size()
                ),
                () -> assertEquals(huge, market.marketId()),
                () -> assertEquals(next, market.baseLotSizeAtoms()),
                () -> assertEquals(huge, order.orderId()),
                () -> assertEquals(next, order.marketId()),
                () -> assertEquals(huge, order.limitPriceQuoteAtomsPerLot()),
                () -> assertEquals(next, order.originalLots()),
                () -> assertEquals(huge, order.remainingLots()),
                () -> assertTrue(order.isMatchable())
        );
    }

    private static OrderSnapshot snapshot(
            OrderStatus status,
            BigInteger remaining
    ) {
        return snapshot(DEPLOYMENT, status, TEN, remaining);
    }

    private static OrderSnapshot snapshot(
            String deploymentId,
            OrderStatus status,
            BigInteger original,
            BigInteger remaining
    ) {
        return new OrderSnapshot(
                deploymentId,
                ONE,
                ONE,
                BASE,
                OrderSide.BUY,
                BigInteger.valueOf(200),
                original,
                remaining,
                status
        );
    }

    private static void assertInvalidStatus(
            OrderStatus status,
            BigInteger remaining
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(status, remaining),
                () -> "Expected rejection for status=" + status
                        + ", remaining=" + remaining
        );
    }
}
