package com.dex.backend.api.order;

import com.dex.backend.api.CursorPageResponse;
import com.dex.backend.order.OrderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final String ADDRESS_PATTERN = "(?i)^0x[0-9a-f]{40}$";

    private final OrderQueryService orderQueryService;

    public OrderController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    @GetMapping
    public CursorPageResponse<OrderResponse> getOrders(
            @RequestParam
            @NotBlank
            @Pattern(regexp = ADDRESS_PATTERN, message = "must be a valid Ethereum address")
            String owner,
            @RequestParam(required = false) @PositiveOrZero BigInteger marketId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @Size(min = 1, max = 512) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return orderQueryService.findOrders(owner, marketId, status, cursor, limit);
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(
            @PathVariable @PositiveOrZero BigInteger orderId
    ) {
        return orderQueryService.findOrder(orderId);
    }
}
