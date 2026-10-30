package com.dex.backend.api.trade;

import com.dex.backend.api.CursorPageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;

@RestController
@RequestMapping("/api/v1/markets/{marketId}/trades")
public class TradeController {

    private final TradeQueryService tradeQueryService;

    public TradeController(TradeQueryService tradeQueryService) {
        this.tradeQueryService = tradeQueryService;
    }

    @GetMapping
    public CursorPageResponse<TradeResponse> getTrades(
            @PathVariable @PositiveOrZero BigInteger marketId,
            @RequestParam(required = false) @Size(min = 1, max = 512) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return tradeQueryService.findTrades(marketId, cursor, limit);
    }
}
