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
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(orders, "orders must not be null");

        List<Order> snapshot = validateAndCopyOrders(market, orders);

        if (!market.active()) {
            return Optional.empty();
        }

        List<Order> bids = snapshot.stream()
                .filter(Order::isMatchable)
                .filter(order -> order.side() == OrderSide.BUY)
                .sorted(BID_PRIORITY)
                .toList();
        List<Order> asks = snapshot.stream()
                .filter(Order::isMatchable)
                .filter(order -> order.side() == OrderSide.SELL)
                .sorted(ASK_PRIORITY)
                .toList();

        return selectMatch(new ArrayList<>(bids), new ArrayList<>(asks));
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

    private Optional<MatchProposal> selectMatch(
            List<Order> bids,
            List<Order> asks
    ) {
        while (!bids.isEmpty() && !asks.isEmpty()) {
            Order bestBid = bids.getFirst();
            Order bestAsk = asks.getFirst();

            if (!pricesCross(bestBid, bestAsk)) {
                return Optional.empty();
            }

            if (!bestBid.owner().equals(bestAsk.owner())) {
                return Optional.of(toProposal(bestBid, bestAsk));
            }

            if (AGE_PRIORITY.compare(bestBid, bestAsk) < 0) {
                Optional<Order> alternativeAsk = firstAlternativeAsk(bestBid, asks);

                if (alternativeAsk.isPresent()) {
                    return Optional.of(toProposal(bestBid, alternativeAsk.orElseThrow()));
                }

                bids.removeFirst();
            } else {
                Optional<Order> alternativeBid = firstAlternativeBid(bids, bestAsk);

                if (alternativeBid.isPresent()) {
                    return Optional.of(toProposal(alternativeBid.orElseThrow(), bestAsk));
                }

                asks.removeFirst();
            }
        }

        return Optional.empty();
    }

    private Optional<Order> firstAlternativeAsk(Order bid, List<Order> asks) {
        return asks.stream()
                .skip(1)
                .takeWhile(ask -> pricesCross(bid, ask))
                .filter(ask -> !bid.owner().equals(ask.owner()))
                .findFirst();
    }

    private Optional<Order> firstAlternativeBid(List<Order> bids, Order ask) {
        return bids.stream()
                .skip(1)
                .takeWhile(bid -> pricesCross(bid, ask))
                .filter(bid -> !bid.owner().equals(ask.owner()))
                .findFirst();
    }

    private boolean pricesCross(Order bid, Order ask) {
        return bid.limitPriceQuoteAtomsPerLot()
                .compareTo(ask.limitPriceQuoteAtomsPerLot()) >= 0;
    }

    private MatchProposal toProposal(Order bid, Order ask) {
        BigInteger fillLots = bid.remainingLots().min(ask.remainingLots());
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
}
