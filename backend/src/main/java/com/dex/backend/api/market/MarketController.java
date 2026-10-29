package com.dex.backend.api.market;

import com.dex.backend.api.ItemsResponse;
import com.dex.backend.api.orderbook.OrderBookQueryService;
import com.dex.backend.api.orderbook.OrderBookResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;

@RestController
@RequestMapping("/api/v1/markets")
public class MarketController {

    private final MarketQueryService marketQueryService;
    private final OrderBookQueryService orderBookQueryService;

    public MarketController(
            MarketQueryService marketQueryService,
            OrderBookQueryService orderBookQueryService
    ) {
        this.marketQueryService = marketQueryService;
        this.orderBookQueryService = orderBookQueryService;
    }

    @GetMapping
    public ItemsResponse<MarketResponse> getMarkets() {
        return new ItemsResponse<>(marketQueryService.findMarkets());
    }

    @GetMapping("/{marketId}/orderbook")
    public OrderBookResponse getOrderBook(
            @PathVariable @PositiveOrZero BigInteger marketId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return orderBookQueryService.getOrderBook(marketId, limit);
    }
}
