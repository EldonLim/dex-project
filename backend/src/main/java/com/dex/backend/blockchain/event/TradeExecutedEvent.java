package com.dex.backend.blockchain.event;

import com.dex.backend.trade.Trade;

import java.math.BigInteger;
import java.util.Objects;

public record TradeExecutedEvent(
        EventMetadata metadata,
        BigInteger tradeId,
        BigInteger marketId,
        BigInteger buyOrderId,
        BigInteger sellOrderId,
        BigInteger fillLots,
        BigInteger executionPriceQuoteAtomsPerLot,
        BigInteger buyOrderRemainingLots,
        BigInteger sellOrderRemainingLots
) implements DexEvent {

    public TradeExecutedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");

        new Trade(
                metadata.deploymentId(),
                metadata.transactionHash(),
                tradeId,
                marketId,
                buyOrderId,
                sellOrderId,
                fillLots,
                executionPriceQuoteAtomsPerLot,
                buyOrderRemainingLots,
                sellOrderRemainingLots,
                metadata.position(),
                metadata.blockHash(),
                metadata.blockTimestamp()
        );
    }

    @Override
    public DexEventType type() {
        return DexEventType.TRADE_EXECUTED;
    }
}