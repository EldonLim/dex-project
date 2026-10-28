package com.dex.backend.persistence.market;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketRepository extends JpaRepository<MarketEntity, MarketEntityId> {
}
