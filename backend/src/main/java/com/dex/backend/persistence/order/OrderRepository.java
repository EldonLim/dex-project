package com.dex.backend.persistence.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigInteger;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, OrderEntityId> {

    @Query("""
            SELECT o
            FROM OrderEntity o
            WHERE o.id.deploymentId = :deploymentId
              AND o.marketId = :marketId
              AND o.side = com.dex.backend.order.OrderSide.BUY
              AND o.status IN (
                    com.dex.backend.order.OrderStatus.OPEN,
                    com.dex.backend.order.OrderStatus.PARTIALLY_FILLED
              )
              AND o.remainingLots > 0
            ORDER BY o.limitPriceQuoteAtomsPerLot DESC,
                     o.creationBlockNumber ASC,
                     o.creationTransactionIndex ASC,
                     o.creationLogIndex ASC,
                     o.id.orderId ASC
            """)
    List<OrderEntity> findEligibleBids(
            @Param("deploymentId") String deploymentId,
            @Param("marketId") BigInteger marketId,
            Pageable pageable
    );

    @Query("""
            SELECT o
            FROM OrderEntity o
            WHERE o.id.deploymentId = :deploymentId
              AND o.marketId = :marketId
              AND o.side = com.dex.backend.order.OrderSide.SELL
              AND o.status IN (
                    com.dex.backend.order.OrderStatus.OPEN,
                    com.dex.backend.order.OrderStatus.PARTIALLY_FILLED
              )
              AND o.remainingLots > 0
            ORDER BY o.limitPriceQuoteAtomsPerLot ASC,
                     o.creationBlockNumber ASC,
                     o.creationTransactionIndex ASC,
                     o.creationLogIndex ASC,
                     o.id.orderId ASC
            """)
    List<OrderEntity> findEligibleAsks(
            @Param("deploymentId") String deploymentId,
            @Param("marketId") BigInteger marketId,
            Pageable pageable
    );
}
