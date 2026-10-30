package com.dex.backend.persistence.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionAttemptRepository
        extends JpaRepository<ExecutionAttemptEntity, UUID> {

    Optional<ExecutionAttemptEntity> findByAttemptIdAndDeploymentId(
            UUID attemptId,
            String deploymentId
    );

    @Query("""
            SELECT a.buyOrderId AS buyOrderId,
                   a.sellOrderId AS sellOrderId
            FROM ExecutionAttemptEntity a
            WHERE a.deploymentId = :deploymentId
              AND a.status IN :statuses
            """)
    List<ReservedOrderPair> findReservedOrderPairs(
            @Param("deploymentId") String deploymentId,
            @Param("statuses") Collection<ExecutionAttemptStatus> statuses
    );

    @Query("""
            SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END
            FROM ExecutionAttemptEntity a
            WHERE a.deploymentId = :deploymentId
              AND a.status IN :statuses
            """)
    boolean existsUnresolved(
            @Param("deploymentId") String deploymentId,
            @Param("statuses") Collection<ExecutionAttemptStatus> statuses
    );
}
