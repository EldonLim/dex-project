package com.dex.backend.api.market;

import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MarketQueryService {

    private static final String QUANTITY_UNIT = "LOTS";
    private static final String PRICE_UNIT = "QUOTE_ATOMS_PER_LOT";

    private final MarketRepository marketRepository;
    private final String deploymentId;

    public MarketQueryService(MarketRepository marketRepository, DexProperties properties) {
        this.marketRepository = marketRepository;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    @Transactional(readOnly = true)
    public List<MarketResponse> findMarkets() {
        return marketRepository.findAllByDeploymentIdOrderByMarketId(deploymentId).stream()
                .map(this::toResponse)
                .toList();
    }

    private MarketResponse toResponse(MarketEntity market) {
        return new MarketResponse(
                market.getId().getMarketId().toString(),
                new TokenResponse(
                        market.getBaseTokenAddress().value(),
                        market.getBaseSymbol(),
                        market.getBaseDecimals()
                ),
                new TokenResponse(
                        market.getQuoteTokenAddress().value(),
                        market.getQuoteSymbol(),
                        market.getQuoteDecimals()
                ),
                market.getBaseLotSizeAtoms().toString(),
                QUANTITY_UNIT,
                PRICE_UNIT,
                market.isActive()
        );
    }
}
