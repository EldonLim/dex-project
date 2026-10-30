package com.dex.backend.blockchain.event;

import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.order.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DexEventTests {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger NEGATIVE = BigInteger.valueOf(-1);

    private static final EthereumAddress BASE =
            new EthereumAddress("0x" + "11".repeat(20));

    private static final EthereumAddress QUOTE =
            new EthereumAddress("0x" + "22".repeat(20));

    private static final EthereumAddress DEX =
            new EthereumAddress("0x" + "33".repeat(20));

    private static final Hash256 TX_HASH =
            new Hash256("0x" + "aa".repeat(32));

    private static final Hash256 BLOCK_HASH =
            new Hash256("0x" + "bb".repeat(32));

    private static final Instant TIME =
            Instant.parse("2026-10-18T12:00:00Z");

    @Test
    void createsAllEventTypes() {
        Fixture fixture = new Fixture();

        MarketRegisteredEvent market = fixture.market();
        OrderPlacedEvent placed = fixture.placed();
        OrderCancelledEvent cancelled = fixture.cancelled();
        TradeExecutedEvent trade = fixture.trade();

        assertAll(
                () -> assertEquals(
                        DexEventType.MARKET_REGISTERED, market.type()
                ),
                () -> assertEquals(
                        DexEventType.ORDER_PLACED, placed.type()
                ),
                () -> assertEquals(
                        DexEventType.ORDER_CANCELLED, cancelled.type()
                ),
                () -> assertEquals(
                        DexEventType.TRADE_EXECUTED, trade.type()
                ),
                () -> assertEquals(fixture.metadata, market.metadata()),
                () -> assertEquals(fixture.metadata, placed.metadata()),
                () -> assertEquals(fixture.metadata, cancelled.metadata()),
                () -> assertEquals(fixture.metadata, trade.metadata()),
                () -> assertEquals(ONE, market.marketId()),
                () -> assertEquals(OrderSide.BUY, placed.side()),
                () -> assertEquals(
                        BigInteger.TEN, placed.originalLots()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(101), cancelled.orderId()
                ),
                () -> assertEquals(
                        BigInteger.TWO, trade.fillLots()
                ),
                () -> assertEquals(
                        fixture.buyer, trade.buyer()
                )
        );
    }

    @Test
    void stripsSymbolsAndPreservesCase() {
        Fixture fixture = new Fixture();
        fixture.baseSymbol = " \tApple\u2003";
        fixture.quoteSymbol = "\nusdToken ";

        MarketRegisteredEvent event = fixture.market();

        assertEquals("Apple", event.baseSymbol());
        assertEquals("usdToken", event.quoteSymbol());
    }

    @Test
    void rejectsInvalidDeploymentIds() {
        for (String id : List.of("", " \t\n", "d".repeat(129))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EventMetadata(
                            id, DEX, TX_HASH, BLOCK_HASH,
                            position(1, 0, 0), TIME
                    )
            );
        }

        EventMetadata valid = new EventMetadata(
                "d".repeat(128), DEX, TX_HASH, BLOCK_HASH,
                position(1, 0, 0), TIME
        );

        assertEquals(128, valid.deploymentId().length());
    }

    @Test
    void rejectsMissingMetadataComponents() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new EventMetadata(
                                null, DEX, TX_HASH, BLOCK_HASH,
                                position(1, 0, 0), TIME
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new EventMetadata(
                                "local-dex-001", null, TX_HASH, BLOCK_HASH,
                                position(1, 0, 0), TIME
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new EventMetadata(
                                "local-dex-001", DEX, null, BLOCK_HASH,
                                position(1, 0, 0), TIME
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new EventMetadata(
                                "local-dex-001", DEX, TX_HASH, null,
                                position(1, 0, 0), TIME
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new EventMetadata(
                                "local-dex-001", DEX, TX_HASH, BLOCK_HASH,
                                null, TIME
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new EventMetadata(
                                "local-dex-001", DEX, TX_HASH, BLOCK_HASH,
                                position(1, 0, 0), null
                        )
                )
        );
    }

    @Test
    void allEventsRequireMetadata() {
        Fixture fixture = new Fixture();
        fixture.metadata = null;

        assertAll(
                () -> assertThrows(
                        NullPointerException.class, () -> fixture.market()
                ),
                () -> assertThrows(
                        NullPointerException.class, () -> fixture.placed()
                ),
                () -> assertThrows(
                        NullPointerException.class, () -> fixture.cancelled()
                ),
                () -> assertThrows(
                        NullPointerException.class, () -> fixture.trade()
                )
        );
    }

    @Test
    void rejectsNegativeMarketIdsForAllEvents() {
        Fixture fixture = new Fixture();
        fixture.marketId = NEGATIVE;

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class, () -> fixture.market()
                ),
                () -> assertThrows(
                        IllegalArgumentException.class, () -> fixture.placed()
                ),
                () -> assertThrows(
                        IllegalArgumentException.class, () -> fixture.trade()
                )
        );
    }

    @Test
    void rejectsNegativeOrderIds() {
        Fixture fixture = new Fixture();
        fixture.orderId = NEGATIVE;

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class, () -> fixture.placed()
                ),
                () -> assertThrows(
                        IllegalArgumentException.class, () -> fixture.cancelled()
                ),
                () -> assertInvalidTrade(f -> f.buyOrderId = NEGATIVE),
                () -> assertInvalidTrade(f -> f.sellOrderId = NEGATIVE)
        );
    }

    @Test
    void rejectsIdenticalTokensAfterNormalization() {
        Fixture fixture = new Fixture();
        fixture.base = new EthereumAddress("0x" + "ab".repeat(20));
        fixture.quote = new EthereumAddress("0x" + "AB".repeat(20));

        assertThrows(
                IllegalArgumentException.class, () -> fixture.market()
        );
    }

    @Test
    void rejectsInvalidSymbols() {
        for (String symbol : List.of("", " \t\n", "s".repeat(33))) {
            assertAll(
                    () -> assertInvalidMarket(f -> f.baseSymbol = symbol),
                    () -> assertInvalidMarket(f -> f.quoteSymbol = symbol)
            );
        }
    }

    @Test
    void rejectsInvalidDecimals() {
        for (int decimals : new int[]{-1, 256}) {
            assertAll(
                    () -> assertInvalidMarket(f -> f.baseDecimals = decimals),
                    () -> assertInvalidMarket(f -> f.quoteDecimals = decimals)
            );
        }
    }

    @Test
    void acceptsMarketBoundaryValues() {
        for (int decimals : new int[]{0, 255}) {
            Fixture fixture = new Fixture();
            fixture.marketId = ZERO;
            fixture.baseSymbol = " " + "a".repeat(32) + " ";
            fixture.quoteSymbol = " " + "B".repeat(32) + " ";
            fixture.baseDecimals = decimals;
            fixture.quoteDecimals = decimals;
            fixture.lotSize = ONE;

            MarketRegisteredEvent event = fixture.market();

            assertAll(
                    () -> assertEquals(ZERO, event.marketId()),
                    () -> assertEquals("a".repeat(32), event.baseSymbol()),
                    () -> assertEquals("B".repeat(32), event.quoteSymbol()),
                    () -> assertEquals(decimals, event.baseDecimals()),
                    () -> assertEquals(decimals, event.quoteDecimals()),
                    () -> assertEquals(ONE, event.baseLotSizeAtoms())
            );
        }
    }

    @Test
    void rejectsZeroOrNegativePositiveQuantities() {
        for (BigInteger invalid : List.of(ZERO, NEGATIVE)) {
            assertAll(
                    () -> assertInvalidMarket(f -> f.lotSize = invalid),
                    () -> assertInvalidPlacement(f -> f.price = invalid),
                    () -> assertInvalidPlacement(f -> f.original = invalid),
                    () -> assertInvalidTrade(f -> f.fill = invalid),
                    () -> assertInvalidTrade(f -> f.price = invalid)
            );
        }
    }

    @Test
    void rejectsNonPositiveTradeTokenAmounts() {
        for (BigInteger invalid : List.of(ZERO, NEGATIVE)) {
            assertAll(
                    () -> assertInvalidTrade(f -> f.baseAmount = invalid),
                    () -> assertInvalidTrade(f -> f.quoteAmount = invalid)
            );
        }
    }

    @Test
    void tradeIdMayBeNullOrNonNegative() {
        Fixture fixture = new Fixture();

        fixture.tradeId = null;
        assertNull(fixture.trade().tradeId());

        fixture.tradeId = ZERO;
        assertEquals(ZERO, fixture.trade().tradeId());

        fixture.tradeId = ONE;
        assertEquals(ONE, fixture.trade().tradeId());

        assertInvalidTrade(f -> f.tradeId = NEGATIVE);
    }

    @Test
    void rejectsIdenticalBuyAndSellOrders() {
        assertInvalidTrade(f -> f.sellOrderId = f.buyOrderId);
    }

    @Test
    void sortsMixedEventsByBlockThenTransactionThenLog() {
        Fixture fixture = new Fixture();

        fixture.metadata = metadata(position(10, 5, 9));
        DexEvent first = fixture.market();

        fixture.metadata = metadata(position(11, 0, 0));
        DexEvent second = fixture.placed();

        fixture.metadata = metadata(position(11, 0, 1));
        DexEvent third = fixture.cancelled();

        fixture.metadata = metadata(position(11, 1, 2));
        DexEvent fourth = fixture.trade();

        fixture.metadata = metadata(position(12, 0, 0));
        DexEvent fifth = fixture.placed();

        List<DexEvent> events = new ArrayList<>(
                List.of(fourth, second, fifth, first, third)
        );

        events.sort(null);

        assertEquals(
                List.of(first, second, third, fourth, fifth),
                events
        );
    }

    @Test
    void sortsEventsWithBlockNumbersLargerThanLongMaxValue() {
        BigInteger earlierBlock = BigInteger.valueOf(Long.MAX_VALUE)
                .add(ONE);

        // A difference of 2^64 would disappear if converted to long.
        BigInteger laterBlock = earlierBlock.add(ONE.shiftLeft(64));

        Fixture fixture = new Fixture();

        fixture.metadata = metadata(
                new ChainPosition(earlierBlock, ZERO, ZERO)
        );
        DexEvent earlier = fixture.placed();

        fixture.metadata = metadata(
                new ChainPosition(laterBlock, ZERO, ZERO)
        );
        DexEvent later = fixture.trade();

        List<DexEvent> events = new ArrayList<>(List.of(later, earlier));
        events.sort(null);

        assertAll(
                () -> assertEquals(List.of(earlier, later), events),
                () -> assertTrue(earlier.compareTo(later) < 0),
                () -> assertTrue(later.compareTo(earlier) > 0),
                () -> assertEquals(
                        earlierBlock, earlier.metadata().position().blockNumber()
                ),
                () -> assertEquals(
                        laterBlock, later.metadata().position().blockNumber()
                )
        );
    }

    @Test
    void rejectsComparisonWithNull() {
        DexEvent event = new Fixture().placed();

        assertThrows(
                NullPointerException.class,
                () -> event.compareTo(null)
        );
    }

    private static void assertInvalidMarket(Consumer<Fixture> change) {
        Fixture fixture = new Fixture();
        change.accept(fixture);

        assertThrows(
                IllegalArgumentException.class, () -> fixture.market()
        );
    }

    private static void assertInvalidPlacement(Consumer<Fixture> change) {
        Fixture fixture = new Fixture();
        change.accept(fixture);

        assertThrows(
                IllegalArgumentException.class, () -> fixture.placed()
        );
    }

    private static void assertInvalidTrade(Consumer<Fixture> change) {
        Fixture fixture = new Fixture();
        change.accept(fixture);

        assertThrows(
                IllegalArgumentException.class, () -> fixture.trade()
        );
    }

    private static ChainPosition position(long block, long transaction, long log) {
        return new ChainPosition(
                BigInteger.valueOf(block),
                BigInteger.valueOf(transaction),
                BigInteger.valueOf(log)
        );
    }

    private static EventMetadata metadata(ChainPosition position) {
        return new EventMetadata(
                "local-dex-001",
                DEX,
                TX_HASH,
                BLOCK_HASH,
                position,
                TIME
        );
    }

    private static final class Fixture {

        private EventMetadata metadata =
                DexEventTests.metadata(position(100, 0, 0));

        private BigInteger marketId = ONE;
        private EthereumAddress base = BASE;
        private EthereumAddress quote = QUOTE;
        private String baseSymbol = "Apple";
        private String quoteSymbol = "usdToken";
        private int baseDecimals = 2;
        private int quoteDecimals = 6;
        private BigInteger lotSize = BigInteger.valueOf(1000);

        private BigInteger orderId = BigInteger.valueOf(101);
        private EthereumAddress owner = BASE;
        private OrderSide side = OrderSide.BUY;
        private BigInteger price = BigInteger.valueOf(200);
        private BigInteger original = BigInteger.TEN;
        private BigInteger tradeId = ONE;
        private BigInteger buyOrderId = BigInteger.valueOf(101);
        private BigInteger sellOrderId = BigInteger.valueOf(102);
        private BigInteger fill = BigInteger.TWO;
        private EthereumAddress buyer = BASE;
        private EthereumAddress seller = QUOTE;
        private BigInteger baseAmount = BigInteger.valueOf(2000);
        private BigInteger quoteAmount = BigInteger.valueOf(400);

        private MarketRegisteredEvent market() {
            return new MarketRegisteredEvent(
                    metadata,
                    marketId,
                    base,
                    quote,
                    baseSymbol,
                    quoteSymbol,
                    baseDecimals,
                    quoteDecimals,
                    lotSize
            );
        }

        private OrderPlacedEvent placed() {
            return new OrderPlacedEvent(
                    metadata,
                    orderId,
                    marketId,
                    owner,
                    side,
                    price,
                    original
            );
        }

        private OrderCancelledEvent cancelled() {
            return new OrderCancelledEvent(
                    metadata,
                    orderId
            );
        }

        private TradeExecutedEvent trade() {
            return new TradeExecutedEvent(
                    metadata,
                    tradeId,
                    marketId,
                    buyOrderId,
                    sellOrderId,
                    fill,
                    price,
                    buyer,
                    seller,
                    baseAmount,
                    quoteAmount
            );
        }
    }
}
