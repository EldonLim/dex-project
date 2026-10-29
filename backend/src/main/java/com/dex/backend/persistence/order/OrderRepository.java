package com.dex.backend.persistence.order;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.order.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

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

    @Query("""
            SELECT o
            FROM OrderEntity o
            WHERE o.id.deploymentId = :deploymentId
              AND o.id.orderId = :orderId
            """)
    Optional<OrderEntity> findByDeploymentIdAndOrderId(
            @Param("deploymentId") String deploymentId,
            @Param("orderId") BigInteger orderId
    );

    @Query("""
            SELECT o
            FROM OrderEntity o
            WHERE o.id.deploymentId = :deploymentId
              AND o.ownerAddress = :owner
              AND (:marketId IS NULL OR o.marketId = :marketId)
              AND (:status IS NULL OR o.status = :status)
              AND (
                    :cursorBlockNumber IS NULL
                    OR o.creationBlockNumber < :cursorBlockNumber
                    OR (
                        o.creationBlockNumber = :cursorBlockNumber
                        AND o.creationTransactionIndex < :cursorTransactionIndex
                    )
                    OR (
                        o.creationBlockNumber = :cursorBlockNumber
                        AND o.creationTransactionIndex = :cursorTransactionIndex
                        AND o.creationLogIndex < :cursorLogIndex
                    )
                    OR (
                        o.creationBlockNumber = :cursorBlockNumber
                        AND o.creationTransactionIndex = :cursorTransactionIndex
                        AND o.creationLogIndex = :cursorLogIndex
                        AND o.id.orderId < :cursorOrderId
                    )
              )
            ORDER BY o.creationBlockNumber DESC,
                     o.creationTransactionIndex DESC,
                     o.creationLogIndex DESC,
                     o.id.orderId DESC
            """)
    List<OrderEntity> findWalletOrders(
            @Param("deploymentId") String deploymentId,
            @Param("owner") EthereumAddress owner,
            @Param("marketId") BigInteger marketId,
            @Param("status") OrderStatus status,
            @Param("cursorBlockNumber") BigInteger cursorBlockNumber,
            @Param("cursorTransactionIndex") BigInteger cursorTransactionIndex,
            @Param("cursorLogIndex") BigInteger cursorLogIndex,
            @Param("cursorOrderId") BigInteger cursorOrderId,
            Pageable pageable
    );
}
