package com.dex.backend.persistence.trade;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<TradeEntity, TradeEntityId> {
}
