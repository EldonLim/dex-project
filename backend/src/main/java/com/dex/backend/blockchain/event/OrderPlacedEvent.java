package com.dex.backend.blockchain.event;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.order.Order;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;

import java.math.BigInteger;
import java.util.Objects;

public record OrderPlacedEvent(
        EventMetadata metadata,
        BigInteger orderId,
        BigInteger marketId,
        EthereumAddress owner,
        OrderSide side,
        BigInteger limitPriceQuoteAtomsPerLot,
        BigInteger originalLots
) implements DexEvent {

    public OrderPlacedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");

        // A placement represents an initially open, completely unfilled order.
        new Order(
                metadata.deploymentId(),
                orderId,
                marketId,
                owner,
                side,
                limitPriceQuoteAtomsPerLot,
                originalLots,
                originalLots,
                OrderStatus.OPEN,
                metadata.position(),
                metadata.transactionHash(),
                metadata.blockHash(),
                metadata.blockTimestamp()
        );
    }

    @Override
    public DexEventType type() {
        return DexEventType.ORDER_PLACED;
    }
}