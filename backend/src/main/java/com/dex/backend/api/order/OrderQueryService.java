package com.dex.backend.api.order;

import com.dex.backend.api.CursorPageResponse;
import com.dex.backend.api.error.ApiResourceNotFoundException;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.config.DexProperties;
import com.dex.backend.order.OrderStatus;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.List;

@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderCursorCodec cursorCodec;
    private final String deploymentId;

    public OrderQueryService(
            OrderRepository orderRepository,
            OrderCursorCodec cursorCodec,
            DexProperties properties
    ) {
        this.orderRepository = orderRepository;
        this.cursorCodec = cursorCodec;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<OrderResponse> findOrders(
            String owner,
            BigInteger marketId,
            OrderStatus status,
            String cursor,
            int limit
    ) {
        OrderCursorCodec.Position position = cursor == null
                ? null
                : cursorCodec.decode(cursor);

        List<OrderEntity> orders = orderRepository.findWalletOrders(
                deploymentId,
                new EthereumAddress(owner),
                marketId,
                status,
                position == null ? null : position.blockNumber(),
                position == null ? null : position.transactionIndex(),
                position == null ? null : position.logIndex(),
                position == null ? null : position.orderId(),
                PageRequest.of(0, limit + 1)
        );

        boolean hasMore = orders.size() > limit;
        List<OrderEntity> pageOrders = orders.subList(0, Math.min(orders.size(), limit));
        String nextCursor = hasMore
                ? cursorCodec.encode(toPosition(pageOrders.getLast()))
                : null;

        return new CursorPageResponse<>(
                pageOrders.stream().map(this::toResponse).toList(),
                nextCursor
        );
    }

    @Transactional(readOnly = true)
    public OrderResponse findOrder(BigInteger orderId) {
        return orderRepository.findByDeploymentIdAndOrderId(deploymentId, orderId)
                .map(this::toResponse)
                .orElseThrow(ApiResourceNotFoundException::new);
    }

    private OrderCursorCodec.Position toPosition(OrderEntity order) {
        return new OrderCursorCodec.Position(
                order.getCreationBlockNumber(),
                order.getCreationTransactionIndex(),
                order.getCreationLogIndex(),
                order.getId().getOrderId()
        );
    }

    private OrderResponse toResponse(OrderEntity order) {
        return new OrderResponse(
                order.getId().getOrderId().toString(),
                order.getMarketId().toString(),
                order.getOwnerAddress().value(),
                order.getSide().name(),
                order.getLimitPriceQuoteAtomsPerLot().toString(),
                order.getOriginalLots().toString(),
                order.getOriginalLots().subtract(order.getRemainingLots()).toString(),
                order.getRemainingLots().toString(),
                order.getStatus().name(),
                new OrderCreationPositionResponse(
                        order.getCreationBlockNumber().toString(),
                        order.getCreationTransactionIndex().toString(),
                        order.getCreationLogIndex().toString()
                ),
                order.getCreationTransactionHash().value(),
                order.getBlockTimestamp().toString()
        );
    }
}
