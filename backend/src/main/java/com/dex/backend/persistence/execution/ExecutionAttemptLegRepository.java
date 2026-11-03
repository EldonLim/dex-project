package com.dex.backend.persistence.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExecutionAttemptLegRepository
        extends JpaRepository<ExecutionAttemptLegEntity, ExecutionAttemptLegId> {

    List<ExecutionAttemptLegEntity> findByIdAttemptIdOrderByIdLegIndex(UUID attemptId);

    @Query("""
            SELECT l.buyOrderId AS buyOrderId,
                   l.sellOrderId AS sellOrderId
            FROM ExecutionAttemptLegEntity l, ExecutionAttemptEntity a
            WHERE l.id.attemptId = a.attemptId
              AND l.deploymentId = :deploymentId
              AND a.deploymentId = :deploymentId
              AND a.status IN :statuses
            ORDER BY a.createdAt ASC, a.attemptId ASC, l.id.legIndex ASC
            """)
    List<ReservedOrderPair> findReservedOrderPairs(
            @Param("deploymentId") String deploymentId,
            @Param("statuses") Collection<ExecutionAttemptStatus> statuses
    );
}
