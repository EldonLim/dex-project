package com.dex.backend.blockchain.event;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.market.Market;

import java.math.BigInteger;
import java.util.Objects;

public record MarketRegisteredEvent(
        EventMetadata metadata,
        BigInteger marketId,
        EthereumAddress baseTokenAddress,
        EthereumAddress quoteTokenAddress,
        String baseSymbol,
        String quoteSymbol,
        int baseDecimals,
        int quoteDecimals,
        BigInteger baseLotSizeAtoms
) implements DexEvent {

    public MarketRegisteredEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");

        Market validated = new Market(
                metadata.deploymentId(),
                marketId,
                baseTokenAddress,
                quoteTokenAddress,
                baseSymbol,
                quoteSymbol,
                baseDecimals,
                quoteDecimals,
                baseLotSizeAtoms,
                true
        );

        // Store the stripped symbols produced by Market validation.
        baseSymbol = validated.baseSymbol();
        quoteSymbol = validated.quoteSymbol();
    }

    @Override
    public DexEventType type() {
        return DexEventType.MARKET_REGISTERED;
    }
}