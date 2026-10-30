package com.dex.backend.persistence.trade;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigInteger;
import java.util.List;

public interface TradeRepository extends JpaRepository<TradeEntity, TradeEntityId> {

    @Query("""
            SELECT trade
            FROM TradeEntity trade
            WHERE trade.id.deploymentId = :deploymentId
              AND trade.marketId = :marketId
              AND (
                    :cursorBlockNumber IS NULL
                    OR trade.blockNumber < :cursorBlockNumber
                    OR (
                        trade.blockNumber = :cursorBlockNumber
                        AND trade.transactionIndex < :cursorTransactionIndex
                    )
                    OR (
                        trade.blockNumber = :cursorBlockNumber
                        AND trade.transactionIndex = :cursorTransactionIndex
                        AND trade.id.logIndex < :cursorLogIndex
                    )
              )
            ORDER BY trade.blockNumber DESC,
                     trade.transactionIndex DESC,
                     trade.id.logIndex DESC
            """)
    List<TradeEntity> findRecentTrades(
            @Param("deploymentId") String deploymentId,
            @Param("marketId") BigInteger marketId,
            @Param("cursorBlockNumber") BigInteger cursorBlockNumber,
            @Param("cursorTransactionIndex") BigInteger cursorTransactionIndex,
            @Param("cursorLogIndex") BigInteger cursorLogIndex,
            Pageable pageable
    );
}
