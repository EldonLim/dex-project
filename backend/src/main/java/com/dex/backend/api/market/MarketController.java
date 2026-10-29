package com.dex.backend.api.market;

import com.dex.backend.api.ItemsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/markets")
public class MarketController {

    private final MarketQueryService marketQueryService;

    public MarketController(MarketQueryService marketQueryService) {
        this.marketQueryService = marketQueryService;
    }

    @GetMapping
    public ItemsResponse<MarketResponse> getMarkets() {
        return new ItemsResponse<>(marketQueryService.findMarkets());
    }
}
