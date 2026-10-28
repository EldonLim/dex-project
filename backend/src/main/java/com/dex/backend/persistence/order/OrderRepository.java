package com.dex.backend.persistence.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, OrderEntityId> {
}
