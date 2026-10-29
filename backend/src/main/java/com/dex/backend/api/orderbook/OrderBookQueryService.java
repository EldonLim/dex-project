package com.dex.backend.api.orderbook;

import com.dex.backend.api.error.ApiResourceNotFoundException;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.List;

@Service
public class OrderBookQueryService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final String deploymentId;

    public OrderBookQueryService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            DexProperties properties
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    @Transactional(readOnly = true)
    public OrderBookResponse getOrderBook(BigInteger marketId, int limit) {
        if (!marketRepository.existsById(new MarketEntityId(deploymentId, marketId))) {
            throw new ApiResourceNotFoundException();
        }

        PageRequest page = PageRequest.of(0, limit + 1);
        List<OrderEntity> bids = orderRepository.findEligibleBids(deploymentId, marketId, page);
        List<OrderEntity> asks = orderRepository.findEligibleAsks(deploymentId, marketId, page);

        return new OrderBookResponse(
                marketId.toString(),
                limit,
                toSideResponse(bids, limit),
                toSideResponse(asks, limit)
        );
    }

    private OrderBookSideResponse toSideResponse(List<OrderEntity> orders, int limit) {
        return new OrderBookSideResponse(
                orders.stream()
                        .limit(limit)
                        .map(this::toOrderResponse)
                        .toList(),
                orders.size() > limit
        );
    }

    private OrderBookOrderResponse toOrderResponse(OrderEntity order) {
        return new OrderBookOrderResponse(
                order.getId().getOrderId().toString(),
                order.getSide().name(),
                order.getLimitPriceQuoteAtomsPerLot().toString(),
                order.getRemainingLots().toString(),
                new CreationPositionResponse(
                        order.getCreationBlockNumber().toString(),
                        order.getCreationTransactionIndex().toString(),
                        order.getCreationLogIndex().toString()
                )
        );
    }
}
