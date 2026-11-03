package com.dex.backend.matcher;

import com.dex.backend.market.Market;
import com.dex.backend.order.Order;
import com.dex.backend.order.OrderSide;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public final class OrderMatcher {

    private static final Comparator<Order> AGE_PRIORITY =
            Comparator.comparing(Order::creationPosition)
                    .thenComparing(Order::orderId);

    private static final Comparator<Order> BID_PRIORITY =
            Comparator.comparing(Order::limitPriceQuoteAtomsPerLot)
                    .reversed()
                    .thenComparing(AGE_PRIORITY);

    private static final Comparator<Order> ASK_PRIORITY =
            Comparator.comparing(Order::limitPriceQuoteAtomsPerLot)
                    .thenComparing(AGE_PRIORITY);

    public Optional<MatchProposal> findNextMatch(
            Market market,
            Collection<Order> orders
    ) {
        return findNextBatch(market, orders, 1)
                .map(batch -> batch.legs().getFirst());
    }

    public Optional<MatchBatch> findNextBatch(
            Market market,
            Collection<Order> orders,
            int maxLegs
    ) {
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(orders, "orders must not be null");

        if (maxLegs < 1 || maxLegs > MatchBatch.MAX_LEGS) {
            throw new IllegalArgumentException("maxLegs must be between 1 and 10");
        }

        List<Order> snapshot = validateAndCopyOrders(market, orders);

        if (!market.active()) {
            return Optional.empty();
        }

        List<VirtualOrder> virtualOrders = snapshot.stream()
                .filter(Order::isMatchable)
                .map(order -> new VirtualOrder(order, order.remainingLots()))
                .toList();
        List<MatchProposal> legs = new ArrayList<>();

        while (legs.size() < maxLegs) {
            Optional<VirtualPair> pair = selectVirtualPair(virtualOrders);
            if (pair.isEmpty()) {
                break;
            }
            VirtualPair selected = pair.orElseThrow();
            BigInteger fillLots = selected.bid().remainingLots()
                    .min(selected.ask().remainingLots());
            MatchProposal proposal = toProposal(
                    selected.bid().order(),
                    selected.ask().order(),
                    fillLots
            );
            legs.add(proposal);
            selected.bid().fill(fillLots);
            selected.ask().fill(fillLots);
        }

        return legs.isEmpty()
                ? Optional.empty()
                : Optional.of(new MatchBatch(
                        market.deploymentId(),
                        market.marketId(),
                        legs
                ));
    }

    private List<Order> validateAndCopyOrders(
            Market market,
            Collection<Order> orders
    ) {
        List<Order> snapshot = new ArrayList<>(orders.size());
        Set<BigInteger> orderIds = new HashSet<>();

        for (Order order : orders) {
            Objects.requireNonNull(order, "orders must not contain null values");

            if (!market.deploymentId().equals(order.deploymentId())) {
                throw new IllegalArgumentException(
                        "Every order must belong to the market deployment"
                );
            }
            if (!market.marketId().equals(order.marketId())) {
                throw new IllegalArgumentException(
                        "Every order must belong to the supplied market"
                );
            }
            if (!orderIds.add(order.orderId())) {
                throw new IllegalArgumentException(
                        "Order IDs must be unique within matcher input"
                );
            }

            snapshot.add(order);
        }

        return List.copyOf(snapshot);
    }

    private Optional<VirtualPair> selectVirtualPair(
            List<VirtualOrder> orders
    ) {
        List<VirtualOrder> bids = orders.stream()
                .filter(VirtualOrder::hasRemaining)
                .filter(order -> order.order().side() == OrderSide.BUY)
                .sorted((left, right) -> BID_PRIORITY.compare(
                        left.order(), right.order()
                ))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<VirtualOrder> asks = orders.stream()
                .filter(VirtualOrder::hasRemaining)
                .filter(order -> order.order().side() == OrderSide.SELL)
                .sorted((left, right) -> ASK_PRIORITY.compare(
                        left.order(), right.order()
                ))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        while (!bids.isEmpty() && !asks.isEmpty()) {
            VirtualOrder bestBid = bids.getFirst();
            VirtualOrder bestAsk = asks.getFirst();

            if (!pricesCross(bestBid.order(), bestAsk.order())) {
                return Optional.empty();
            }
            if (!bestBid.order().owner().equals(bestAsk.order().owner())) {
                return Optional.of(new VirtualPair(bestBid, bestAsk));
            }

            if (AGE_PRIORITY.compare(bestBid.order(), bestAsk.order()) < 0) {
                Optional<VirtualOrder> alternativeAsk = asks.stream()
                        .skip(1)
                        .takeWhile(ask -> pricesCross(
                                bestBid.order(), ask.order()
                        ))
                        .filter(ask -> !bestBid.order().owner().equals(
                                ask.order().owner()
                        ))
                        .findFirst();
                if (alternativeAsk.isPresent()) {
                    return Optional.of(new VirtualPair(
                            bestBid,
                            alternativeAsk.orElseThrow()
                    ));
                }
                bids.removeFirst();
            } else {
                Optional<VirtualOrder> alternativeBid = bids.stream()
                        .skip(1)
                        .takeWhile(bid -> pricesCross(
                                bid.order(), bestAsk.order()
                        ))
                        .filter(bid -> !bid.order().owner().equals(
                                bestAsk.order().owner()
                        ))
                        .findFirst();
                if (alternativeBid.isPresent()) {
                    return Optional.of(new VirtualPair(
                            alternativeBid.orElseThrow(),
                            bestAsk
                    ));
                }
                asks.removeFirst();
            }
        }
        return Optional.empty();
    }

    private boolean pricesCross(Order bid, Order ask) {
        return bid.limitPriceQuoteAtomsPerLot()
                .compareTo(ask.limitPriceQuoteAtomsPerLot()) >= 0;
    }

    private MatchProposal toProposal(
            Order bid,
            Order ask,
            BigInteger fillLots
    ) {
        BigInteger executionPrice = AGE_PRIORITY.compare(bid, ask) < 0
                ? bid.limitPriceQuoteAtomsPerLot()
                : ask.limitPriceQuoteAtomsPerLot();

        return new MatchProposal(
                bid.deploymentId(),
                bid.marketId(),
                bid.orderId(),
                ask.orderId(),
                fillLots,
                executionPrice
        );
    }

    private static final class VirtualOrder {
        private final Order order;
        private BigInteger remainingLots;

        private VirtualOrder(Order order, BigInteger remainingLots) {
            this.order = order;
            this.remainingLots = remainingLots;
        }

        private Order order() {
            return order;
        }

        private BigInteger remainingLots() {
            return remainingLots;
        }

        private boolean hasRemaining() {
            return remainingLots.signum() > 0;
        }

        private void fill(BigInteger quantity) {
            remainingLots = remainingLots.subtract(quantity);
        }
    }

    private record VirtualPair(VirtualOrder bid, VirtualOrder ask) {
    }
}
