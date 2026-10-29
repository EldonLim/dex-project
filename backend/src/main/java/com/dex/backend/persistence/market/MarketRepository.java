package com.dex.backend.persistence.market;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MarketRepository extends JpaRepository<MarketEntity, MarketEntityId> {

    @Query("""
            SELECT market
            FROM MarketEntity market
            WHERE market.id.deploymentId = :deploymentId
            ORDER BY market.id.marketId ASC
            """)
    List<MarketEntity> findAllByDeploymentIdOrderByMarketId(
            @Param("deploymentId") String deploymentId
    );
}
