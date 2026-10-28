package com.dex.backend.blockchain.event;

import java.util.Objects;

public sealed interface DexEvent extends Comparable<DexEvent>
        permits MarketRegisteredEvent,
                OrderPlacedEvent,
                OrderCancelledEvent,
                TradeExecutedEvent {

    EventMetadata metadata();

    DexEventType type();

    @Override
    default int compareTo(DexEvent other) {
        Objects.requireNonNull(other, "other must not be null");

        return metadata().position()
                .compareTo(other.metadata().position());
    }
}