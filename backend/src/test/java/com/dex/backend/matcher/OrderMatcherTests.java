package com.dex.backend.matcher;

import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.market.Market;
import com.dex.backend.order.Order;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderMatcherTests {

    private static final String DEPLOYMENT_ID = "matching-test";
    private static final BigInteger MARKET_ID = BigInteger.ONE;

    private final OrderMatcher matcher = new OrderMatcher();

    @Test
    void returnsEmptyForInactiveEmptyOneSidedAndNonCrossedBooks() {
        Order bid = order(1, OrderSide.BUY, 1, 100, 10, 1);
        Order crossedAsk = order(2, OrderSide.SELL, 2, 90, 10, 2);
        Order nonCrossedAsk = order(3, OrderSide.SELL, 3, 101, 10, 3);

        assertAll(
                () -> assertTrue(matcher.findNextMatch(market(true), List.of()).isEmpty()),
                () -> assertTrue(
                        matcher.findNextMatch(
                                market(false),
                                List.of(bid, crossedAsk)
                        ).isEmpty()
                ),
                () -> assertTrue(
                        matcher.findNextMatch(market(true), List.of(bid)).isEmpty()
                ),
                () -> assertTrue(
                        matcher.findNextMatch(market(true), List.of(crossedAsk)).isEmpty()
                ),
                () -> assertTrue(
                        matcher.findNextMatch(
                                market(true),
                                List.of(bid, nonCrossedAsk)
                        ).isEmpty()
                )
        );
    }

    @Test
    void appliesPricePriorityIndependentlyOfInputOrder() {
        Order lowerBid = order(1, OrderSide.BUY, 1, 110, 10, 1);
        Order bestBid = order(2, OrderSide.BUY, 2, 120, 10, 2);
        Order bestAsk = order(3, OrderSide.SELL, 3, 90, 10, 3);
        Order higherAsk = order(4, OrderSide.SELL, 4, 100, 10, 4);
        MatchProposal expected = proposal(2, 3, 10, 120);

        List<Order> firstOrder = List.of(lowerBid, higherAsk, bestAsk, bestBid);
        List<Order> secondOrder = List.of(bestBid, bestAsk, lowerBid, higherAsk);

        assertAll(
                () -> assertEquals(
                        expected,
                        matcher.findNextMatch(market(true), firstOrder).orElseThrow()
                ),
                () -> assertEquals(
                        expected,
                        matcher.findNextMatch(market(true), secondOrder).orElseThrow()
                ),
                () -> assertEquals(
                        expected,
                        matcher.findNextMatch(market(true), firstOrder).orElseThrow()
                )
        );
    }

    @Test
    void usesCompleteCreationPositionAndOrderIdForEqualPricePriority() {
        Order laterBid = fixture(30, OrderSide.BUY, 1)
                .price(110).position(10, 1, 0).build();
        Order tiedBidWithHigherId = fixture(20, OrderSide.BUY, 2)
                .price(110).position(10, 0, 5).build();
        Order expectedBid = fixture(10, OrderSide.BUY, 3)
                .price(110).position(10, 0, 5).build();
        Order laterAsk = fixture(50, OrderSide.SELL, 4)
                .price(100).position(11, 1, 0).build();
        Order tiedAskWithHigherId = fixture(40, OrderSide.SELL, 5)
                .price(100).position(11, 0, 7).build();
        Order expectedAsk = fixture(35, OrderSide.SELL, 6)
                .price(100).position(11, 0, 7).build();

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(
                        laterAsk,
                        tiedBidWithHigherId,
                        expectedAsk,
                        laterBid,
                        tiedAskWithHigherId,
                        expectedBid
                )
        ).orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.TEN, result.buyOrderId()),
                () -> assertEquals(BigInteger.valueOf(35), result.sellOrderId())
        );
    }

    @Test
    void fillsSmallerRemainderAndUsesOlderBuyPriceWithoutMutatingOrders() {
        Order bid = fixture(1, OrderSide.BUY, 1)
                .price(105).quantity(40, 40, OrderStatus.OPEN).position(1, 0, 0).build();
        Order ask = fixture(2, OrderSide.SELL, 2)
                .price(90).quantity(100, 60, OrderStatus.PARTIALLY_FILLED)
                .position(2, 0, 0).build();

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(ask, bid)
        ).orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.valueOf(40), result.fillLots()),
                () -> assertEquals(
                        BigInteger.valueOf(105),
                        result.executionPriceQuoteAtomsPerLot()
                ),
                () -> assertEquals(BigInteger.valueOf(40), bid.remainingLots()),
                () -> assertEquals(OrderStatus.OPEN, bid.status()),
                () -> assertEquals(BigInteger.valueOf(60), ask.remainingLots()),
                () -> assertEquals(OrderStatus.PARTIALLY_FILLED, ask.status())
        );
    }

    @Test
    void usesOlderSellPriceAndSupportsPartiallyFilledOrders() {
        Order ask = fixture(1, OrderSide.SELL, 1)
                .price(100).quantity(50, 20, OrderStatus.PARTIALLY_FILLED)
                .position(1, 0, 0).build();
        Order bid = fixture(2, OrderSide.BUY, 2)
                .price(110).quantity(100, 70, OrderStatus.PARTIALLY_FILLED)
                .position(2, 0, 0).build();

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(bid, ask)
        ).orElseThrow();

        assertAll(
                () -> assertEquals(BigInteger.valueOf(20), result.fillLots()),
                () -> assertEquals(
                        BigInteger.valueOf(100),
                        result.executionPriceQuoteAtomsPerLot()
                )
        );
    }

    @Test
    void usesLowerOrderIdToDetermineOlderOrderWhenPositionsAreIdentical() {
        Order bid = fixture(5, OrderSide.BUY, 1)
                .price(110).position(10, 1, 2).build();
        Order ask = fixture(6, OrderSide.SELL, 2)
                .price(100).position(10, 1, 2).build();

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(ask, bid)
        ).orElseThrow();

        assertEquals(
                BigInteger.valueOf(110),
                result.executionPriceQuoteAtomsPerLot()
        );
    }

    @Test
    void excludesFilledAndCancelledOrders() {
        Order filledBid = fixture(1, OrderSide.BUY, 1)
                .price(1_000).quantity(10, 0, OrderStatus.FILLED).build();
        Order cancelledAsk = fixture(2, OrderSide.SELL, 2)
                .price(1).quantity(10, 10, OrderStatus.CANCELLED).build();
        Order eligibleBid = order(3, OrderSide.BUY, 3, 110, 10, 3);
        Order eligibleAsk = order(4, OrderSide.SELL, 4, 100, 10, 4);

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(filledBid, eligibleAsk, cancelledAsk, eligibleBid)
        ).orElseThrow();

        assertEquals(proposal(3, 4, 10, 110), result);
    }

    @Test
    void keepsOlderBidAndScansLowerPriorityAsksAfterSelfTrade() {
        Order olderBid = order(1, OrderSide.BUY, 1, 110, 10, 1);
        Order selfAsk = order(2, OrderSide.SELL, 1, 90, 10, 2);
        Order alternativeAsk = order(3, OrderSide.SELL, 2, 100, 10, 3);

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(alternativeAsk, selfAsk, olderBid)
        ).orElseThrow();

        assertEquals(proposal(1, 3, 10, 110), result);
    }

    @Test
    void keepsOlderAskAndScansLowerPriorityBidsAfterSelfTrade() {
        Order olderAsk = order(1, OrderSide.SELL, 1, 100, 10, 1);
        Order selfBid = order(2, OrderSide.BUY, 1, 120, 10, 2);
        Order alternativeBid = order(3, OrderSide.BUY, 2, 110, 10, 3);

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(alternativeBid, selfBid, olderAsk)
        ).orElseThrow();

        assertEquals(proposal(3, 1, 10, 100), result);
    }

    @Test
    void reconsidersSelfAskWhenOlderBidHasNoAlternativeAsk() {
        Order olderBid = order(1, OrderSide.BUY, 1, 120, 10, 1);
        Order selfAsk = order(2, OrderSide.SELL, 1, 100, 10, 2);
        Order alternativeBid = order(3, OrderSide.BUY, 2, 110, 10, 3);

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(alternativeBid, selfAsk, olderBid)
        ).orElseThrow();

        assertEquals(proposal(3, 2, 10, 100), result);
    }

    @Test
    void reconsidersSelfBidWhenOlderAskHasNoAlternativeBid() {
        Order olderAsk = order(1, OrderSide.SELL, 1, 90, 10, 1);
        Order selfBid = order(2, OrderSide.BUY, 1, 120, 10, 2);
        Order alternativeAsk = order(3, OrderSide.SELL, 2, 100, 10, 3);

        MatchProposal result = matcher.findNextMatch(
                market(true),
                List.of(alternativeAsk, selfBid, olderAsk)
        ).orElseThrow();

        assertEquals(proposal(2, 3, 10, 120), result);
    }

    @Test
    void returnsEmptyWhenEveryCrossedPairWouldBeASelfTrade() {
        List<Order> orders = List.of(
                order(1, OrderSide.BUY, 1, 120, 10, 1),
                order(2, OrderSide.BUY, 1, 110, 10, 2),
                order(3, OrderSide.SELL, 1, 90, 10, 3),
                order(4, OrderSide.SELL, 1, 100, 10, 4)
        );

        assertTrue(matcher.findNextMatch(market(true), orders).isEmpty());
    }

    @Test
    void rejectsNullDuplicateAndIncorrectlyScopedOrders() {
        Order valid = order(1, OrderSide.BUY, 1, 110, 10, 1);
        Order duplicate = fixture(1, OrderSide.SELL, 2).price(100).build();
        Order wrongDeployment = fixture(2, OrderSide.SELL, 2)
                .deploymentId("other-deployment").build();
        Order wrongMarket = fixture(3, OrderSide.SELL, 2)
                .marketId(BigInteger.TWO).build();
        List<Order> containingNull = new ArrayList<>(Arrays.asList(valid, null));

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> matcher.findNextMatch(null, List.of())
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> matcher.findNextMatch(market(true), null)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> matcher.findNextMatch(market(true), containingNull)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> matcher.findNextMatch(market(true), List.of(valid, duplicate))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> matcher.findNextMatch(market(true), List.of(wrongDeployment))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> matcher.findNextMatch(market(true), List.of(wrongMarket))
                )
        );
    }

    @Test
    void preservesBigIntegerPrecisionBeyondLongRange() {
        BigInteger marketId = new BigInteger("90071992547409930000000001");
        BigInteger buyId = new BigInteger("90071992547409930000000002");
        BigInteger sellId = new BigInteger("90071992547409930000000003");
        BigInteger buyPrice = new BigInteger("90071992547409930000000100");
        BigInteger sellPrice = new BigInteger("90071992547409930000000099");
        BigInteger buyRemaining = new BigInteger("90071992547409930000000050");
        BigInteger sellRemaining = new BigInteger("90071992547409930000000040");
        Market market = market(true, marketId);
        Order bid = fixture(buyId, OrderSide.BUY, 1)
                .marketId(marketId)
                .price(buyPrice)
                .quantity(buyRemaining, buyRemaining, OrderStatus.OPEN)
                .position(1, 0, 0)
                .build();
        Order ask = fixture(sellId, OrderSide.SELL, 2)
                .marketId(marketId)
                .price(sellPrice)
                .quantity(sellRemaining, sellRemaining, OrderStatus.OPEN)
                .position(2, 0, 0)
                .build();

        MatchProposal result = matcher.findNextMatch(
                market,
                List.of(ask, bid)
        ).orElseThrow();

        assertAll(
                () -> assertEquals(buyId, result.buyOrderId()),
                () -> assertEquals(sellId, result.sellOrderId()),
                () -> assertEquals(sellRemaining, result.fillLots()),
                () -> assertEquals(buyPrice, result.executionPriceQuoteAtomsPerLot())
        );
    }

    private static Market market(boolean active) {
        return market(active, MARKET_ID);
    }

    private static Market market(boolean active, BigInteger marketId) {
        return new Market(
                DEPLOYMENT_ID,
                marketId,
                address(100),
                address(101),
                "TKA",
                "TKB",
                18,
                18,
                BigInteger.valueOf(1_000_000),
                active
        );
    }

    private static MatchProposal proposal(
            long buyOrderId,
            long sellOrderId,
            long fillLots,
            long price
    ) {
        return new MatchProposal(
                DEPLOYMENT_ID,
                MARKET_ID,
                BigInteger.valueOf(buyOrderId),
                BigInteger.valueOf(sellOrderId),
                BigInteger.valueOf(fillLots),
                BigInteger.valueOf(price)
        );
    }

    private static Order order(
            long orderId,
            OrderSide side,
            long ownerSeed,
            long price,
            long remaining,
            long block
    ) {
        return fixture(orderId, side, ownerSeed)
                .price(price)
                .quantity(remaining, remaining, OrderStatus.OPEN)
                .position(block, 0, 0)
                .build();
    }

    private static OrderFixture fixture(
            long orderId,
            OrderSide side,
            long ownerSeed
    ) {
        return fixture(BigInteger.valueOf(orderId), side, ownerSeed);
    }

    private static OrderFixture fixture(
            BigInteger orderId,
            OrderSide side,
            long ownerSeed
    ) {
        return new OrderFixture(orderId, side, address(ownerSeed));
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }

    private static final class OrderFixture {

        private String deploymentId = DEPLOYMENT_ID;
        private final BigInteger orderId;
        private BigInteger marketId = MARKET_ID;
        private final EthereumAddress owner;
        private final OrderSide side;
        private BigInteger price = BigInteger.valueOf(100);
        private BigInteger original = BigInteger.TEN;
        private BigInteger remaining = BigInteger.TEN;
        private OrderStatus status = OrderStatus.OPEN;
        private ChainPosition position = new ChainPosition(
                BigInteger.ONE,
                BigInteger.ZERO,
                BigInteger.ZERO
        );

        private OrderFixture(
                BigInteger orderId,
                OrderSide side,
                EthereumAddress owner
        ) {
            this.orderId = orderId;
            this.side = side;
            this.owner = owner;
        }

        private OrderFixture deploymentId(String value) {
            deploymentId = value;
            return this;
        }

        private OrderFixture marketId(BigInteger value) {
            marketId = value;
            return this;
        }

        private OrderFixture price(long value) {
            return price(BigInteger.valueOf(value));
        }

        private OrderFixture price(BigInteger value) {
            price = value;
            return this;
        }

        private OrderFixture quantity(
                long originalLots,
                long remainingLots,
                OrderStatus orderStatus
        ) {
            return quantity(
                    BigInteger.valueOf(originalLots),
                    BigInteger.valueOf(remainingLots),
                    orderStatus
            );
        }

        private OrderFixture quantity(
                BigInteger originalLots,
                BigInteger remainingLots,
                OrderStatus orderStatus
        ) {
            original = originalLots;
            remaining = remainingLots;
            status = orderStatus;
            return this;
        }

        private OrderFixture position(long block, long transaction, long log) {
            position = new ChainPosition(
                    BigInteger.valueOf(block),
                    BigInteger.valueOf(transaction),
                    BigInteger.valueOf(log)
            );
            return this;
        }

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
                    hash(1),
                    hash(2),
                    Instant.parse("2026-01-01T00:00:00Z")
            );
        }
    }
}
